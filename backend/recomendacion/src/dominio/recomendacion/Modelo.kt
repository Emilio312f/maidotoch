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
