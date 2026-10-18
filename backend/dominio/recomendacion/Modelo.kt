package dominio.recomendacion

/** Producto ya declarado seguro, con los datos de catálogo que usan el ranking y la explicación. */
data class CandidatoRecomendable(
    val productoId: Long,
    val revisionProducto: Long,
    val nombre: String,
    val descripcion: String,
    val ordenCategoria: Int,
    /** Preferencias del producto: id → nombre en el idioma de la sesión. */
    val preferencias: Map<Long, String>,
)

/** Lo declarado por el comensal, en el idioma de la sesión. */
data class ContextoPerfil(
    val idioma: String,
    /** id → nombre de cada preferencia declarada. */
    val preferencias: Map<Long, String>,
    /** Nombres de las restricciones declaradas; solo los usa la explicación, nunca salen hacia la IA. */
    val restricciones: List<String>,
)

enum class Motor(val valor: String) { IA("ia"), FALLBACK("fallback") }

/**
 * DTO mínimo que puede salir hacia el proveedor externo de IA (RNF01 · RNF02): la consulta y los
 * candidatos ya filtrados. Sin alérgenos, sin ingredientes, sin restricciones y sin id de sesión.
 */
data class SolicitudOrdenamiento(val consulta: String, val maximo: Int, val candidatos: List<CandidatoParaIA>)

data class CandidatoParaIA(val id: Long, val nombre: String, val descripcion: String, val etiquetas: List<String>)

/** Puerto del Motor de Recomendación hacia la estrategia con IA; lo implementa la capa de integración. */
interface OrdenadorExterno {
    /** Devuelve ids en orden de relevancia. La respuesta no es confiable: se valida después. */
    suspend fun ordenar(solicitud: SolicitudOrdenamiento): List<Long>
}
