package comun.contratos

import comun.http.ClienteInterno
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpcionPerfil(val id: Long, val codigo: String, val tipo: String? = null, val nombre: String)

@Serializable
data class OpcionesPerfil(val restricciones: List<OpcionPerfil>, val preferencias: List<OpcionPerfil>)

/**
 * Datos de catálogo de un producto activo y disponible, en un idioma. Es lo que el Servicio de
 * Recomendación usa para el ranking y la explicación; no lleva alérgenos.
 */
@Serializable
data class FichaProducto(
    val productoId: Long,
    val revision: Long,
    val nombre: String,
    val descripcion: String,
    val ordenCategoria: Int,
    val precioCentimos: Long,
    /** Preferencias del producto: id → nombre en el idioma pedido. */
    val preferencias: Map<Long, String>,
)

@Serializable
data class Fichas(val productos: List<FichaProducto>)

@Serializable
data class Existe(val existe: Boolean)

/** API interna del Servicio de Catálogo. */
interface ApiCatalogo {
    /**
     * Primer eslabón de la cadena: productos activos y disponibles con texto en [idioma] (RF12). Si
     * están validados lo decide después el Servicio de Seguridad Alimentaria.
     */
    fun disponibles(idioma: String): List<FichaProducto>

    fun nombres(ids: Collection<Long>, idioma: String): Map<Long, String>

    fun opciones(idioma: String): OpcionesPerfil

    fun existe(productoId: Long): Boolean
}

class HttpApiCatalogo(private val cliente: ClienteInterno) : ApiCatalogo {
    override fun disponibles(idioma: String): List<FichaProducto> =
        cliente.get<Fichas>("/interno/catalogo/disponibles?idioma=${codificar(idioma)}").productos

    override fun nombres(ids: Collection<Long>, idioma: String): Map<Long, String> =
        if (ids.isEmpty()) emptyMap()
        else cliente.post<IdsIdiomaPeticion, Nombres>("/interno/catalogo/nombres", IdsIdiomaPeticion(ids.distinct(), idioma)).nombres

    override fun opciones(idioma: String): OpcionesPerfil = cliente.get("/api/opciones-perfil?idioma=${codificar(idioma)}")

    override fun existe(productoId: Long): Boolean = cliente.get<Existe>("/interno/catalogo/productos/$productoId/existe").existe
}

// ---- Archivo del catálogo validado (lo prepara el Responsable de Datos con cocina, Plan §6.3) ----

@Serializable
data class ArchivoCatalogo(
    val categorias: List<CategoriaJson> = emptyList(),
    val alergenos: List<ReferenciaJson> = emptyList(),
    val ingredientes: List<IngredienteJson> = emptyList(),
    val restricciones: List<RestriccionJson> = emptyList(),
    val preferencias: List<ReferenciaJson> = emptyList(),
    val productos: List<ProductoJson> = emptyList(),
)

@Serializable
data class CategoriaJson(val codigo: String, @SerialName("nombre_es") val nombreEs: String, @SerialName("nombre_en") val nombreEn: String, val orden: Int = 0)

@Serializable
data class ReferenciaJson(val codigo: String, @SerialName("nombre_es") val nombreEs: String, @SerialName("nombre_en") val nombreEn: String)

@Serializable
data class IngredienteJson(
    val codigo: String,
    @SerialName("nombre_es") val nombreEs: String,
    @SerialName("nombre_en") val nombreEn: String,
    val alergenos: List<String> = emptyList(),
)

@Serializable
data class RestriccionJson(
    val codigo: String,
    val tipo: String,
    @SerialName("nombre_es") val nombreEs: String,
    @SerialName("nombre_en") val nombreEn: String,
    val alergeno: String? = null,
)

@Serializable
data class TextoJson(val nombre: String, val descripcion: String, val historia: String? = null)

@Serializable
data class AlergenoJson(val alergeno: String, val origen: String)

@Serializable
data class EvaluacionJson(val restriccion: String, val resultado: String, val evidencia: String)

@Serializable
data class ProductoJson(
    val codigo: String,
    val categoria: String,
    val tipo: String,
    val destino: String,
    @SerialName("precio_centimos") val precioCentimos: Long,
    @SerialName("foto_url") val fotoUrl: String? = null,
    val activo: Boolean = true,
    val textos: Map<String, TextoJson>,
    val ingredientes: List<String>,
    val alergenos: List<AlergenoJson> = emptyList(),
    val preferencias: List<String> = emptyList(),
    val evaluaciones: List<EvaluacionJson> = emptyList(),
)

@Serializable
data class ImportarCatalogoPeticion(val contenido: String, val evaluador: String)

@Serializable
data class ResumenImportacion(val productosNuevos: Int, val productosModificados: Int, val productosSinCambios: Int, val productosPorValidar: List<String>)
