package comun.datos

import comun.SolicitudInvalida
import java.sql.Connection

/**
 * Utilidades de la carga del catálogo validado: solo escribe lo que cambió, porque un UPDATE
 * innecesario dispararía los triggers que anulan la validación de un producto.
 */
class CargaPorCodigo(private val conn: Connection) {

    /** Inserta por código o actualiza solo si algún valor cambió. */
    fun referencia(tabla: String, codigo: String, valores: LinkedHashMap<String, Any?>): Long {
        val columnas = valores.keys.joinToString(", ")
        val actual = conn.consultar("SELECT id, $columnas FROM $tabla WHERE codigo = ?", codigo) { rs ->
            rs.getLong(1) to valores.keys.mapIndexed { i, _ -> rs.getObject(i + 2)?.let { if (it is Int) it.toLong() else it } }
        }.singleOrNull()
        if (actual == null) {
            val marcas = (listOf("codigo") + valores.keys).joinToString(", ") { "?" }
            return conn.insertar("INSERT INTO $tabla(codigo, $columnas) VALUES ($marcas)", codigo, *valores.values.toTypedArray())
        }
        val (id, existentes) = actual
        if (existentes != valores.values.toList()) {
            val asignaciones = valores.keys.joinToString(", ") { "$it = ?" }
            conn.ejecutar("UPDATE $tabla SET $asignaciones WHERE id = ?", *valores.values.toTypedArray(), id)
        }
        return id
    }

    fun id(tabla: String, codigo: String): Long =
        conn.consultar("SELECT id FROM $tabla WHERE codigo = ?", codigo) { it.getLong(1) }.singleOrNull()
            ?: invalido("Código desconocido en $tabla: $codigo")

    fun <T> sincronizar(actuales: Set<T>, deseados: Set<T>, insertar: (T) -> Unit, borrar: (T) -> Unit) {
        (deseados - actuales).forEach(insertar)
        (actuales - deseados).forEach(borrar)
    }

    fun invalido(mensaje: String): Nothing = throw SolicitudInvalida("catalogo_invalido", mensaje)
}
