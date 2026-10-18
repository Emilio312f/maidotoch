package aplicacion

import datos.FuenteConexiones
import datos.NuevaRecomendacion
import datos.RepositorioRecomendacion
import datos.RepositorioSeguridadAlimentaria
import datos.consultar
import datos.enTransaccion
import dominio.recomendacion.CandidatoParaIA
import dominio.recomendacion.CandidatoRecomendable
import dominio.recomendacion.ContextoPerfil
import dominio.recomendacion.GeneradorExplicaciones
import dominio.recomendacion.Motor
import dominio.recomendacion.OrdenadorExterno
import dominio.recomendacion.RankingPorReglas
import dominio.recomendacion.SolicitudOrdenamiento
import dominio.recomendacion.Texto
import dominio.recomendacion.ValidadorRespuestaIA
import dominio.restricciones.VERSION_REGLAS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.SQLException

@Serializable
data class Recomendado(
    val recomendacionId: Long,
    val productoId: Long,
    val posicion: Int,
    val nombre: String,
    val descripcion: String,
    val precioCentimos: Long,
    val explicacion: String,
)

/** Oferta de derivación al mozo: `restriccion_incierta` cuando hay platos sin dato suficiente (RF14). */
@Serializable
data class Derivacion(val motivo: String, val productos: List<String>)

@Serializable
data class RespuestaAsistente(
    val recomendaciones: List<Recomendado>,
    val motor: String? = null,
    val sinCandidatos: Boolean,
    val derivacion: Derivacion? = null,
    /** Platos disponibles que no se pudieron confirmar para el perfil (sin dato). */
    val platosSinConfirmar: Int,
)

/**
 * Orquestador de Recomendación (Facade): ejecuta siempre la cadena
 * disponibilidad → restricciones → ranking → explicación.
 *
 * El proveedor de IA solo recibe la lista ya declarada segura. Si no responde a tiempo, falla o se
 * agotó el límite de consultas de la sesión, se usa el ranking por reglas sobre la misma lista.
 */
class OrquestadorRecomendacion(
    private val fuente: FuenteConexiones,
    private val ordenador: OrdenadorExterno?,
    private val maximoRecomendaciones: Int = 3,
    private val limiteConsultasIa: Int = 10,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Preparacion(
        val revisionPerfil: Long,
        val contexto: ContextoPerfil,
        val candidatos: List<CandidatoRecomendable>,
        val sinDato: Map<Long, String>,
        val consultasIa: Int,
    )

    suspend fun recomendar(sesionId: String, consulta: String): RespuestaAsistente {
        val texto = consulta.trim().take(LARGO_MAXIMO_CONSULTA)
        var intento = 0
        while (true) {
            val inicio = System.nanoTime()
            val preparacion = withContext(Dispatchers.IO) { preparar(sesionId) }
            val derivacion = derivacion(preparacion, texto)

            if (preparacion.candidatos.isEmpty()) {
                withContext(Dispatchers.IO) {
                    fuente.usar { RepositorioRecomendacion(it).registrarConsulta(sesionId, "ninguno", milis(inicio), 0) }
                }
                return RespuestaAsistente(
                    recomendaciones = emptyList(),
                    sinCandidatos = true,
                    derivacion = derivacion ?: Derivacion(
                        if (preparacion.sinDato.isEmpty()) "asistencia" else "restriccion_incierta",
                        emptyList(),
                    ),
                    platosSinConfirmar = preparacion.sinDato.size,
                )
            }

            val (ordenados, motor) = rankear(preparacion, texto)
            val elegidos = ordenados.take(maximoRecomendaciones)
            val nuevas = elegidos.mapIndexed { i, candidato ->
                NuevaRecomendacion(
                    sesionId = sesionId,
                    productoId = candidato.productoId,
                    revisionProducto = candidato.revisionProducto,
                    revisionPerfil = preparacion.revisionPerfil,
                    posicion = i + 1,
                    explicacion = GeneradorExplicaciones.explicar(candidato, preparacion.contexto, texto, preparacion.candidatos.size),
                    motor = motor.valor,
                    versionReglas = VERSION_REGLAS,
                )
            }

            try {
                val recomendados = withContext(Dispatchers.IO) { persistir(sesionId, nuevas, elegidos, motor, milis(inicio)) }
                return RespuestaAsistente(recomendados, motor.valor, false, derivacion, preparacion.sinDato.size)
            } catch (e: SQLException) {
                // El catálogo o el perfil cambiaron mientras se calculaba: se recalcula, nunca se fuerza el insert.
                if (intento++ >= 1 || e.message?.contains("Recomendacion bloqueada") != true) throw e
                log.info("Recomendación recalculada por cambio de revisión")
            }
        }
    }

    private fun preparar(sesionId: String): Preparacion = fuente.usar { conn ->
        val seguros = ServicioCandidatosSeguros(RepositorioSeguridadAlimentaria(conn)).calcular(sesionId)
        val repo = RepositorioRecomendacion(conn)
        val contexto = repo.contexto(sesionId)
        Preparacion(
            revisionPerfil = seguros.revisionPerfil,
            contexto = contexto,
            candidatos = repo.candidatos(seguros.seguros.associate { it.productoId to it.revisionProducto }, contexto.idioma),
            sinDato = repo.nombres(seguros.sinDato.map { it.productoId }, contexto.idioma),
            consultasIa = repo.consultasConIa(sesionId),
        )
    }

    /** Se ofrece derivar si la consulta menciona un plato que no se pudo confirmar para el perfil. */
    private fun derivacion(preparacion: Preparacion, consulta: String): Derivacion? {
        val palabras = Texto.palabras(consulta)
        val mencionados = preparacion.sinDato.values.filter { nombre -> Texto.palabras(nombre).any { it in palabras } }
        return if (mencionados.isEmpty()) null else Derivacion("restriccion_incierta", mencionados.sorted())
    }

    private suspend fun rankear(preparacion: Preparacion, consulta: String): Pair<List<CandidatoRecomendable>, Motor> {
        val proveedor = ordenador
        if (proveedor != null && consulta.isNotBlank() && preparacion.consultasIa < limiteConsultasIa) {
            val solicitud = SolicitudOrdenamiento(
                consulta = consulta,
                maximo = maximoRecomendaciones,
                candidatos = preparacion.candidatos.map {
                    CandidatoParaIA(it.productoId, it.nombre, it.descripcion, it.preferencias.values.sorted())
                },
            )
            try {
                val porId = preparacion.candidatos.associateBy { it.productoId }
                val ids = ValidadorRespuestaIA.filtrar(proveedor.ordenar(solicitud), porId.keys)
                if (ids.isNotEmpty()) return ids.map(porId::getValue) to Motor.IA
                log.warn("El proveedor de IA no devolvió ids válidos; se usa el ranking por reglas")
            } catch (e: TimeoutCancellationException) {
                log.warn("El proveedor de IA no respondió a tiempo; se usa el ranking por reglas")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Falla del proveedor de IA ({}); se usa el ranking por reglas", e.javaClass.simpleName)
            }
        }
        return RankingPorReglas.seleccionar(preparacion.candidatos, preparacion.contexto, consulta) to Motor.FALLBACK
    }

    private fun persistir(
        sesionId: String,
        nuevas: List<NuevaRecomendacion>,
        elegidos: List<CandidatoRecomendable>,
        motor: Motor,
        latenciaMs: Long,
    ): List<Recomendado> = fuente.usar { conn ->
        conn.enTransaccion {
            val repo = RepositorioRecomendacion(conn)
            val recomendados = nuevas.zip(elegidos).map { (nueva, candidato) ->
                val id = repo.insertar(nueva)
                repo.registrarEvento(id, "mostrada")
                val precio = conn.consultar("SELECT precio_centimos FROM productos WHERE id = ?", candidato.productoId) { it.getLong(1) }.single()
                Recomendado(id, candidato.productoId, nueva.posicion, candidato.nombre, candidato.descripcion, precio, nueva.explicacion)
            }
            repo.registrarConsulta(sesionId, motor.valor, latenciaMs, recomendados.size)
            recomendados
        }
    }

    private fun milis(inicioNanos: Long) = (System.nanoTime() - inicioNanos) / 1_000_000

    private companion object {
        const val LARGO_MAXIMO_CONSULTA = 300
    }
}
