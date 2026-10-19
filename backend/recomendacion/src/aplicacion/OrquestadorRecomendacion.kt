package recomendacion.aplicacion

import comun.SesionNoDisponible
import comun.contratos.ApiCatalogo
import comun.contratos.ApiIA
import comun.contratos.ApiRecomendacion
import comun.contratos.ApiSeguridad
import comun.contratos.ApiSesion
import comun.contratos.ApiTrazabilidad
import comun.contratos.CandidatoParaIA
import comun.contratos.SolicitudOrdenamiento
import comun.datos.FuenteConexiones
import comun.datos.enTransaccion
import dominio.recomendacion.CandidatoRecomendable
import dominio.recomendacion.ContextoPerfil
import dominio.recomendacion.GeneradorExplicaciones
import dominio.recomendacion.Motor
import dominio.recomendacion.RankingPorReglas
import dominio.recomendacion.Texto
import dominio.recomendacion.ValidadorRespuestaIA
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import recomendacion.datos.NuevaRecomendacion
import recomendacion.datos.RepositorioRecomendacion
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
 * Orquestador de Recomendación (Facade · Chain of Responsibility): ejecuta siempre la cadena
 * disponibilidad (Catálogo) → restricciones (Seguridad Alimentaria) → ranking → explicación.
 *
 * El Servicio Adaptador de IA solo recibe la lista ya declarada segura. Si no responde a tiempo,
 * falla o se agotó el límite de consultas de la sesión, se usa el ranking por reglas sobre la misma lista.
 */
class OrquestadorRecomendacion(
    private val fuente: FuenteConexiones,
    private val sesion: ApiSesion,
    private val catalogo: ApiCatalogo,
    private val seguridad: ApiSeguridad,
    private val ia: ApiIA?,
    private val trazabilidad: ApiTrazabilidad,
    private val maximoRecomendaciones: Int = 3,
    private val limiteConsultasIa: Int = 10,
    val timeoutIaMs: Long = 5_000,
) : ApiRecomendacion {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Preparacion(
        val revisionPerfil: Long,
        val versionReglas: String,
        val contexto: ContextoPerfil,
        val candidatos: List<CandidatoRecomendable>,
        val precios: Map<Long, Long>,
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
                    versionReglas = preparacion.versionReglas,
                )
            }

            try {
                val recomendados = withContext(Dispatchers.IO) { persistir(sesionId, nuevas, elegidos, preparacion.precios, motor, milis(inicio)) }
                return RespuestaAsistente(recomendados, motor.valor, false, derivacion, preparacion.sinDato.size)
            } catch (e: SQLException) {
                // El catálogo o el perfil cambiaron mientras se calculaba: se recalcula, nunca se fuerza el insert.
                if (intento++ >= 1 || e.message?.contains("Recomendacion bloqueada") != true) throw e
                log.info("Recomendación recalculada por cambio de revisión")
            }
        }
    }

    /** Los dos primeros eslabones de la cadena, cada uno en su servicio. Ningún dato sale hacia la IA aquí. */
    private fun preparar(sesionId: String): Preparacion {
        val estado = sesion.sesionAbierta(sesionId)
        if (!estado.perfilCompleto) throw SesionNoDisponible(perfilIncompleto = true)

        // 1 · Disponibilidad: lo que el Catálogo tiene activo, disponible y validado hoy.
        val disponibles = catalogo.disponibles(estado.idioma).associateBy { it.productoId }
        // 2 · Restricciones: el Servicio de Seguridad Alimentaria decide con reglas fijas sobre la base.
        val lista = seguridad.candidatos(sesionId, disponibles.keys)

        val opciones = catalogo.opciones(estado.idioma)
        val contexto = ContextoPerfil(
            idioma = estado.idioma,
            preferencias = opciones.preferencias.filter { it.id in estado.preferencias }.associate { it.id to it.nombre },
            restricciones = opciones.restricciones.filter { it.id in estado.restricciones }.map { it.nombre },
        )
        // Solo productos cuya ficha corresponde a la misma revisión que evaluó Seguridad.
        val candidatos = lista.seguros.mapNotNull { seguro ->
            disponibles[seguro.productoId]?.takeIf { it.revision == seguro.revisionProducto }?.let {
                CandidatoRecomendable(it.productoId, seguro.revisionProducto, it.nombre, it.descripcion, it.ordenCategoria, it.preferencias)
            }
        }
        return Preparacion(
            revisionPerfil = lista.revisionPerfil,
            versionReglas = lista.versionReglas,
            contexto = contexto,
            candidatos = candidatos,
            precios = candidatos.associate { it.productoId to disponibles.getValue(it.productoId).precioCentimos },
            sinDato = lista.sinDato.associateWith { disponibles[it]?.nombre.orEmpty() },
            consultasIa = fuente.usar { RepositorioRecomendacion(it).consultasConIa(sesionId) },
        )
    }

    /** Se ofrece derivar si la consulta menciona un plato que no se pudo confirmar para el perfil. */
    private fun derivacion(preparacion: Preparacion, consulta: String): Derivacion? {
        val palabras = Texto.palabras(consulta)
        val mencionados = preparacion.sinDato.values.filter { nombre -> Texto.palabras(nombre).any { it in palabras } }
        return if (mencionados.isEmpty()) null else Derivacion("restriccion_incierta", mencionados.sorted())
    }

    private suspend fun rankear(preparacion: Preparacion, consulta: String): Pair<List<CandidatoRecomendable>, Motor> {
        val proveedor = ia
        if (proveedor != null && consulta.isNotBlank() && preparacion.consultasIa < limiteConsultasIa) {
            // 3 · Ranking: solo la lista segura, como DTO mínimo, sale hacia el Servicio Adaptador de IA.
            val solicitud = SolicitudOrdenamiento(
                consulta = consulta,
                maximo = maximoRecomendaciones,
                candidatos = preparacion.candidatos.map {
                    CandidatoParaIA(it.productoId, it.nombre, it.descripcion, it.preferencias.values.sorted())
                },
            )
            try {
                val porId = preparacion.candidatos.associateBy { it.productoId }
                val ids = ValidadorRespuestaIA.filtrar(withTimeout(timeoutIaMs) { proveedor.ordenar(solicitud) }, porId.keys)
                if (ids.isNotEmpty()) return ids.map(porId::getValue) to Motor.IA
                log.warn("El proveedor de IA no devolvió ids válidos; se usa el ranking por reglas")
            } catch (e: TimeoutCancellationException) {
                log.warn("El proveedor de IA no respondió a tiempo; se usa el ranking por reglas")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Falla del proveedor de IA ({}); se usa el ranking por reglas", e.message ?: e.javaClass.simpleName)
            }
        }
        return RankingPorReglas.seleccionar(preparacion.candidatos, preparacion.contexto, consulta) to Motor.FALLBACK
    }

    /** 4 · Explicación ya construida: se guarda la recomendación (el trigger revisa revisiones) y se traza. */
    private fun persistir(
        sesionId: String,
        nuevas: List<NuevaRecomendacion>,
        elegidos: List<CandidatoRecomendable>,
        precios: Map<Long, Long>,
        motor: Motor,
        latenciaMs: Long,
    ): List<Recomendado> {
        val recomendados = fuente.usar { conn ->
            conn.enTransaccion {
                val repo = RepositorioRecomendacion(conn)
                val recomendados = nuevas.zip(elegidos).map { (nueva, candidato) ->
                    val id = repo.insertar(nueva)
                    Recomendado(id, candidato.productoId, nueva.posicion, candidato.nombre, candidato.descripcion, precios.getValue(candidato.productoId), nueva.explicacion)
                }
                repo.registrarConsulta(sesionId, motor.valor, latenciaMs, recomendados.size)
                recomendados
            }
        }
        recomendados.forEach { registrarMostrada(it.recomendacionId) }
        return recomendados
    }

    /** RF13: el evento `mostrada` va al Servicio de Trazabilidad, con un reintento. */
    private fun registrarMostrada(recomendacionId: Long) {
        repeat(2) { intento ->
            try {
                trazabilidad.registrarEvento(recomendacionId, "mostrada")
                return
            } catch (e: Exception) {
                if (intento == 1) log.warn("Evento mostrada sin registrar para la recomendación {}: {}", recomendacionId, e.message)
            }
        }
    }

    override fun productoDeRecomendacion(recomendacionId: Long, sesionId: String): Long? =
        fuente.usar { RepositorioRecomendacion(it).productoDeRecomendacion(recomendacionId, sesionId) }

    private fun milis(inicioNanos: Long) = (System.nanoTime() - inicioNanos) / 1_000_000

    private companion object {
        const val LARGO_MAXIMO_CONSULTA = 300
    }
}
