package dominio.disponibilidad

data class EstadoCatalogo(
    val productoId: Long,
    val activo: Boolean,
    val disponible: Boolean,
    val tieneTextoEnIdioma: Boolean,
)

/** Primer eslabón de la cadena: descarta lo que no está en carta antes de evaluar restricciones (RF12 · RPR04). */
object FiltroDisponibilidad {
    fun filtrar(productos: Collection<EstadoCatalogo>): List<EstadoCatalogo> =
        productos.filter { it.activo && it.disponible && it.tieneTextoEnIdioma }
}
