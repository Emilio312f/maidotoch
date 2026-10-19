package catalogo.datos

import comun.contratos.FichaProducto
import comun.contratos.OpcionPerfil
import comun.datos.consultar
import comun.datos.ejecutar
import kotlinx.serialization.Serializable
import java.sql.Connection

@Serializable
data class Categoria(val id: Long, val codigo: String, val nombre: String, val orden: Int)

@Serializable
data class ProductoCarta(
    val id: Long,
    val categoriaId: Long,
    val tipo: String,
    val destino: String,
    val precioCentimos: Long,
    val fotoUrl: String? = null,
    val disponible: Boolean,
    val nombre: String,
    val descripcion: String,
    val historia: String? = null,
    val etiquetas: List<String>,
    /** Alérgenos registrados y validados por cocina: declarados como contenido o vía ingredientes. */
    val alergenos: List<String>,
    val trazas: List<String>,
)

@Serializable
data class ProductoBackoffice(
    val id: Long,
    val codigo: String,
    val nombre: String,
    val categoria: String,
    val destino: String,
    val precioCentimos: Long,
    val activo: Boolean,
    val disponible: Boolean,
    val validado: Boolean,
    val revision: Long,
    val fotoUrl: String? = null,
)

@Serializable
data class CambioDisponibilidad(val productoId: Long, val producto: String, val disponible: Boolean, val registradoPor: String, val fecha: String, val motivo: String)

data class CambioRegistrado(val cambio: CambioDisponibilidad, val registradoPorId: Long)

@Serializable
data class TextoProducto(val idioma: String, val nombre: String, val descripcion: String, val historia: String? = null)

/** Producto de la carta sin los alérgenos, que agrega el Servicio de Seguridad Alimentaria. */
data class ProductoBase(
    val id: Long,
    val categoriaId: Long,
    val tipo: String,
    val destino: String,
    val precioCentimos: Long,
    val fotoUrl: String?,
    val disponible: Boolean,
    val nombre: String,
    val descripcion: String,
    val historia: String?,
    val etiquetas: List<String>,
)

class RepositorioCatalogo(private val conn: Connection) {

    fun categorias(idioma: String): List<Categoria> {
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        return conn.consultar("SELECT id, codigo, $columna, orden FROM categorias ORDER BY orden, id") {
            Categoria(it.getLong(1), it.getString(2), it.getString(3), it.getInt(4))
        }
    }

    /** Carta visible: productos activos y validados con texto en el idioma pedido (RF02 · RF12). */
    fun carta(idioma: String): List<ProductoBase> {
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        val etiquetas = conn.consultar(
            "SELECT pp.producto_id, pr.$columna FROM producto_preferencias pp JOIN preferencias pr ON pr.id = pp.preferencia_id ORDER BY 2",
        ) { it.getLong(1) to it.getString(2) }.groupBy({ it.first }, { it.second })

        return conn.consultar(
            """
            SELECT p.id, p.categoria_id, p.tipo, p.destino, p.precio_centimos, p.foto_url, p.disponible,
                   t.nombre, t.descripcion, t.historia
            FROM productos p
            JOIN producto_textos t ON t.producto_id = p.id AND t.idioma = ?
            JOIN categorias c ON c.id = p.categoria_id
            WHERE p.activo = 1 AND p.validado_en IS NOT NULL
            ORDER BY c.orden, p.id
            """.trimIndent(),
            idioma,
        ) { rs ->
            val id = rs.getLong(1)
            ProductoBase(
                id = id,
                categoriaId = rs.getLong(2),
                tipo = rs.getString(3),
                destino = rs.getString(4),
                precioCentimos = rs.getLong(5),
                fotoUrl = rs.getString(6),
                disponible = rs.getInt(7) == 1,
                nombre = rs.getString(8),
                descripcion = rs.getString(9),
                historia = rs.getString(10),
                etiquetas = etiquetas[id].orEmpty(),
            )
        }
    }

    /**
     * Primer eslabón de la cadena de recomendación: activos y disponibles con texto en el idioma, el
     * mismo criterio que FiltroDisponibilidad. La validación no se filtra aquí: un producto sin
     * validar llega al Servicio de Seguridad Alimentaria, que lo declara «sin dato» (RF14).
     */
    fun disponibles(idioma: String): List<FichaProducto> {
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        val preferencias = conn.consultar(
            "SELECT pp.producto_id, pr.id, pr.$columna FROM producto_preferencias pp JOIN preferencias pr ON pr.id = pp.preferencia_id",
        ) { Triple(it.getLong(1), it.getLong(2), it.getString(3)) }
            .groupBy { it.first }
            .mapValues { (_, filas) -> filas.associate { it.second to it.third } }
        return conn.consultar(
            """
            SELECT p.id, p.revision, t.nombre, t.descripcion, c.orden, p.precio_centimos
            FROM productos p JOIN producto_textos t ON t.producto_id = p.id AND t.idioma = ?
            JOIN categorias c ON c.id = p.categoria_id
            WHERE p.activo = 1 AND p.disponible = 1
            ORDER BY c.orden, p.id
            """.trimIndent(),
            idioma,
        ) { rs ->
            val id = rs.getLong(1)
            FichaProducto(id, rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getLong(6), preferencias[id].orEmpty())
        }
    }

    fun nombres(ids: Collection<Long>, idioma: String): Map<Long, String> = ids.distinct().associateWith { id ->
        conn.consultar("SELECT nombre FROM producto_textos WHERE producto_id = ? AND idioma = ?", id, idioma) { it.getString(1) }
            .singleOrNull() ?: ""
    }

    fun existe(productoId: Long): Boolean = conn.consultar("SELECT 1 FROM productos WHERE id = ?", productoId) { true }.isNotEmpty()

    fun preferencias(idioma: String): List<OpcionPerfil> {
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        return conn.consultar("SELECT id, codigo, $columna FROM preferencias ORDER BY $columna") {
            OpcionPerfil(it.getLong(1), it.getString(2), null, it.getString(3))
        }
    }

    fun productosBackoffice(): List<ProductoBackoffice> = conn.consultar(
        """
        SELECT p.id, p.codigo, COALESCE(t.nombre, p.codigo), c.nombre_es, p.destino, p.precio_centimos,
               p.activo, p.disponible, p.validado_en IS NOT NULL, p.revision, p.foto_url
        FROM productos p JOIN categorias c ON c.id = p.categoria_id
        LEFT JOIN producto_textos t ON t.producto_id = p.id AND t.idioma = 'es'
        ORDER BY c.orden, p.id
        """.trimIndent(),
    ) {
        ProductoBackoffice(
            it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getLong(6),
            it.getInt(7) == 1, it.getInt(8) == 1, it.getInt(9) == 1, it.getLong(10), it.getString(11),
        )
    }

    fun productoBackoffice(id: Long): ProductoBackoffice? = productosBackoffice().find { it.id == id }

    /** La disponibilidad solo cambia por el historial; el trigger actualiza productos.disponible. */
    fun cambiarDisponibilidad(productoId: Long, disponible: Boolean, personalId: Long, motivo: String) {
        conn.ejecutar(
            "INSERT INTO disponibilidad_historial(producto_id, disponible, registrado_por, motivo) VALUES (?, ?, ?, ?)",
            productoId, if (disponible) 1 else 0, personalId, motivo,
        )
    }

    /** El identificador de quien registró el cambio lo resuelve el Servicio de Sesión. */
    fun historialDisponibilidad(limite: Int): List<CambioRegistrado> = conn.consultar(
        """
        SELECT h.producto_id, COALESCE(t.nombre, p.codigo), h.disponible, h.registrado_por, h.fecha, h.motivo
        FROM disponibilidad_historial h JOIN productos p ON p.id = h.producto_id
        LEFT JOIN producto_textos t ON t.producto_id = p.id AND t.idioma = 'es'
        ORDER BY h.id DESC LIMIT ?
        """.trimIndent(),
        limite,
    ) { CambioRegistrado(CambioDisponibilidad(it.getLong(1), it.getString(2), it.getInt(3) == 1, "", it.getString(5), it.getString(6)), it.getLong(4)) }

    fun textos(productoId: Long): List<TextoProducto> = conn.consultar(
        "SELECT idioma, nombre, descripcion, historia FROM producto_textos WHERE producto_id = ? ORDER BY idioma DESC",
        productoId,
    ) { TextoProducto(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }

    fun preferenciasDeProducto(productoId: Long): List<String> = conn.consultar(
        "SELECT pr.nombre_es FROM producto_preferencias pp JOIN preferencias pr ON pr.id = pp.preferencia_id WHERE pp.producto_id = ? ORDER BY 1",
        productoId,
    ) { it.getString(1) }

    /** Quién validó el producto (id del personal) y cuándo. */
    fun validacion(productoId: Long): Pair<Long?, String?> = conn.consultar(
        "SELECT validado_por, validado_en FROM productos WHERE id = ?",
        productoId,
    ) { (it.getObject(1)?.let { _ -> it.getLong(1) }) to it.getString(2) }.single()

    /** El trigger invalidar_datos_producto anula la validación al cambiar la foto. */
    fun cambiarFoto(productoId: Long, url: String) {
        conn.ejecutar("UPDATE productos SET foto_url = ? WHERE id = ?", url, productoId)
    }

    /** Los triggers exigen personal activo de cocina o administración, textos es/en e ingredientes. */
    fun validar(productoId: Long, personalId: Long) {
        conn.ejecutar(
            "UPDATE productos SET validado_por = ?, validado_en = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?",
            personalId, productoId,
        )
    }

    fun revisiones(): Map<String, Long> = conn.consultar("SELECT codigo, revision FROM productos") { it.getString(1) to it.getLong(2) }.toMap()

    fun porValidar(): List<String> =
        conn.consultar("SELECT codigo FROM productos WHERE validado_en IS NULL AND activo = 1 ORDER BY codigo") { it.getString(1) }
}
