package aplicacion

import datos.Categoria
import datos.FuenteConexiones
import datos.ProductoCarta
import datos.RepositorioCatalogo
import datos.RepositorioSeguridadAlimentaria
import dominio.restricciones.EstadoSeguridad
import kotlinx.serialization.Serializable

@Serializable
data class Carta(val idioma: String, val categorias: List<Categoria>, val productos: List<ProductoCarta>)

/** apto: se puede pedir. no_apto: excluido por las reglas. consultar: sin dato suficiente, derivar al mozo (RF14). */
@Serializable
data class ProductoParaSesion(val producto: ProductoCarta, val aptitud: String? = null)

@Serializable
data class CartaSesion(val idioma: String, val perfilCompleto: Boolean, val categorias: List<Categoria>, val productos: List<ProductoParaSesion>)

/**
 * Servicio de Catálogo con caché en memoria de la carta, invalidada al cambiar disponibilidad o
 * validación. La aptitud por sesión nunca se cachea: se calcula siempre contra la base.
 */
class ServicioCatalogo(
    private val fuente: FuenteConexiones,
    private val vigenciaCacheMs: Long = 30_000,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    private data class Entrada(val carta: Carta, val creada: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entrada>()

    fun carta(idioma: String): Carta {
        validarIdioma(idioma)
        val vigente = cache[idioma]?.takeIf { reloj() - it.creada < vigenciaCacheMs }
        if (vigente != null) return vigente.carta
        val carta = fuente.usar { conn ->
            val repo = RepositorioCatalogo(conn)
            Carta(idioma, repo.categorias(idioma), repo.carta(idioma))
        }
        cache[idioma] = Entrada(carta, reloj())
        return carta
    }

    fun invalidar() = cache.clear()

    fun cartaParaSesion(sesionId: String): CartaSesion {
        val (estado, aptitudes) = fuente.usar { conn ->
            val estado = ServicioSesion.sesionAbierta(conn, sesionId)
            val aptitudes = if (estado.perfilCompleto) {
                val candidatos = ServicioCandidatosSeguros(RepositorioSeguridadAlimentaria(conn)).calcular(sesionId)
                val seguros = candidatos.seguros.map { it.productoId }.toSet()
                candidatos.evaluaciones.associate { evaluacion ->
                    evaluacion.productoId to when {
                        evaluacion.productoId in seguros -> "apto"
                        evaluacion.estado == EstadoSeguridad.SIN_DATO -> "consultar"
                        else -> "no_apto"
                    }
                }
            } else {
                emptyMap()
            }
            estado to aptitudes
        }
        val carta = carta(estado.idioma)
        return CartaSesion(
            idioma = carta.idioma,
            perfilCompleto = estado.perfilCompleto,
            categorias = carta.categorias,
            productos = carta.productos.map { ProductoParaSesion(it, aptitudes[it.id]) },
        )
    }
}
