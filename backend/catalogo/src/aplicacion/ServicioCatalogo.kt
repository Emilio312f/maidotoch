package catalogo.aplicacion

import catalogo.datos.Categoria
import catalogo.datos.ProductoCarta
import catalogo.datos.RepositorioCatalogo
import comun.contratos.ApiCatalogo
import comun.contratos.ApiSeguridad
import comun.contratos.ApiSesion
import comun.contratos.EvaluacionResumen
import comun.contratos.FichaProducto
import comun.contratos.OpcionesPerfil
import comun.datos.FuenteConexiones
import comun.validarIdioma
import kotlinx.serialization.Serializable

@Serializable
data class Carta(val idioma: String, val categorias: List<Categoria>, val productos: List<ProductoCarta>)

/** apto: se puede pedir. no_apto: excluido por las reglas. consultar: sin dato suficiente, derivar al mozo (RF14). */
@Serializable
data class ProductoParaSesion(val producto: ProductoCarta, val aptitud: String? = null)

@Serializable
data class CartaSesion(val idioma: String, val perfilCompleto: Boolean, val categorias: List<Categoria>, val productos: List<ProductoParaSesion>)

/**
 * Servicio de Catálogo con caché en memoria de la carta y de las opciones del perfil, invalidada al
 * cambiar disponibilidad, validación, fotos o el catálogo. La aptitud por sesión nunca se cachea: la
 * calcula siempre el Servicio de Seguridad Alimentaria.
 */
class ServicioCatalogo(
    private val fuente: FuenteConexiones,
    private val sesion: ApiSesion,
    private val seguridad: ApiSeguridad,
    private val vigenciaCacheMs: Long = 30_000,
    private val reloj: () -> Long = System::currentTimeMillis,
) : ApiCatalogo {
    private data class Entrada<T>(val valor: T, val creada: Long)

    private val cartas = java.util.concurrent.ConcurrentHashMap<String, Entrada<Carta>>()
    private val opciones = java.util.concurrent.ConcurrentHashMap<String, Entrada<OpcionesPerfil>>()

    fun carta(idioma: String): Carta {
        validarIdioma(idioma)
        cartas[idioma]?.takeIf { reloj() - it.creada < vigenciaCacheMs }?.let { return it.valor }
        val (categorias, productos) = fuente.usar { conn ->
            val repo = RepositorioCatalogo(conn)
            repo.categorias(idioma) to repo.carta(idioma)
        }
        val alergenos = seguridad.alergenos(idioma).associateBy { it.productoId }
        val carta = Carta(
            idioma, categorias,
            productos.map { p ->
                ProductoCarta(
                    p.id, p.categoriaId, p.tipo, p.destino, p.precioCentimos, p.fotoUrl, p.disponible, p.nombre, p.descripcion,
                    p.historia, p.etiquetas, alergenos[p.id]?.alergenos.orEmpty(), alergenos[p.id]?.trazas.orEmpty(),
                )
            },
        )
        cartas[idioma] = Entrada(carta, reloj())
        return carta
    }

    fun invalidar() {
        cartas.clear()
        opciones.clear()
    }

    fun cartaParaSesion(sesionId: String): CartaSesion {
        val estado = sesion.sesionAbierta(sesionId)
        val aptitudes = if (estado.perfilCompleto) {
            val lista = seguridad.candidatos(sesionId)
            val seguros = lista.seguros.map { it.productoId }.toSet()
            lista.evaluaciones.associate { evaluacion ->
                evaluacion.productoId to when {
                    evaluacion.productoId in seguros -> "apto"
                    evaluacion.estado == EvaluacionResumen.SIN_DATO -> "consultar"
                    else -> "no_apto"
                }
            }
        } else {
            emptyMap()
        }
        val carta = carta(estado.idioma)
        return CartaSesion(
            idioma = carta.idioma,
            perfilCompleto = estado.perfilCompleto,
            categorias = carta.categorias,
            productos = carta.productos.map { ProductoParaSesion(it, aptitudes[it.id]) },
        )
    }

    override fun disponibles(idioma: String): List<FichaProducto> {
        validarIdioma(idioma)
        return fuente.usar { RepositorioCatalogo(it).disponibles(idioma) }
    }

    override fun nombres(ids: Collection<Long>, idioma: String): Map<Long, String> {
        validarIdioma(idioma)
        return fuente.usar { RepositorioCatalogo(it).nombres(ids, idioma) }
    }

    /** Opciones del perfil breve (RF07): restricciones del Servicio de Seguridad y preferencias del catálogo. */
    override fun opciones(idioma: String): OpcionesPerfil {
        validarIdioma(idioma)
        opciones[idioma]?.takeIf { reloj() - it.creada < vigenciaCacheMs }?.let { return it.valor }
        val valor = OpcionesPerfil(seguridad.restricciones(idioma), fuente.usar { RepositorioCatalogo(it).preferencias(idioma) })
        opciones[idioma] = Entrada(valor, reloj())
        return valor
    }

    override fun existe(productoId: Long): Boolean = fuente.usar { RepositorioCatalogo(it).existe(productoId) }
}
