package datos

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
)

@Serializable
data class CambioDisponibilidad(val productoId: Long, val producto: String, val disponible: Boolean, val registradoPor: String, val fecha: String, val motivo: String)

@Serializable
data class TextoProducto(val idioma: String, val nombre: String, val descripcion: String, val historia: String? = null)

@Serializable
data class AlergenoProducto(val alergeno: String, val origen: String)

@Serializable
data class EvaluacionRestriccion(val restriccion: String, val resultado: String, val evidencia: String, val validadoPor: String, val validadoEn: String)

@Serializable
data class DetalleProducto(
    val producto: ProductoBackoffice,
    val textos: List<TextoProducto>,
    val ingredientes: List<String>,
    val alergenos: List<AlergenoProducto>,
    val preferencias: List<String>,
    val evaluaciones: List<EvaluacionRestriccion>,
    val restriccionesSinEvaluar: List<String>,
    val validadoPor: String? = null,
    val validadoEn: String? = null,
)

/** Producto que una sesión puede pedir ahora mismo: está en v_productos_compatibles. */
data class ProductoPedible(val productoId: Long, val nombre: String, val precioCentimos: Long, val destino: String)

class RepositorioCatalogo(private val conn: Connection) {

    fun categorias(idioma: String): List<Categoria> {
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        return conn.consultar("SELECT id, codigo, $columna, orden FROM categorias ORDER BY orden, id") {
            Categoria(it.getLong(1), it.getString(2), it.getString(3), it.getInt(4))
        }
    }

    /** Carta visible: productos activos y validados con texto en el idioma pedido (RF02 · RF12). */
    fun carta(idioma: String): List<ProductoCarta> {
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        val etiquetas = conn.consultar(
            "SELECT pp.producto_id, pr.$columna FROM producto_preferencias pp JOIN preferencias pr ON pr.id = pp.preferencia_id ORDER BY 2",
        ) { it.getLong(1) to it.getString(2) }.groupBy({ it.first }, { it.second })
        val alergenos = conn.consultar(
            """
            SELECT pa.producto_id, a.$columna, pa.origen FROM producto_alergenos pa JOIN alergenos a ON a.id = pa.alergeno_id
            UNION
            SELECT pi.producto_id, a.$columna, 'contiene' FROM producto_ingredientes pi
            JOIN ingrediente_alergenos ia ON ia.ingrediente_id = pi.ingrediente_id JOIN alergenos a ON a.id = ia.alergeno_id
            ORDER BY 2
            """.trimIndent(),
        ) { Triple(it.getLong(1), it.getString(2), it.getString(3)) }.groupBy { it.first }

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
            val deProducto = alergenos[id].orEmpty()
            val contiene = deProducto.filter { it.third == "contiene" }.map { it.second }.distinct()
            ProductoCarta(
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
                alergenos = contiene,
                trazas = deProducto.filter { it.third == "trazas" }.map { it.second }.distinct() - contiene.toSet(),
            )
        }
    }

    fun productosBackoffice(): List<ProductoBackoffice> = conn.consultar(
        """
        SELECT p.id, p.codigo, COALESCE(t.nombre, p.codigo), c.nombre_es, p.destino, p.precio_centimos,
               p.activo, p.disponible, p.validado_en IS NOT NULL, p.revision
        FROM productos p JOIN categorias c ON c.id = p.categoria_id
        LEFT JOIN producto_textos t ON t.producto_id = p.id AND t.idioma = 'es'
        ORDER BY c.orden, p.id
        """.trimIndent(),
    ) {
        ProductoBackoffice(
            it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getLong(6),
            it.getInt(7) == 1, it.getInt(8) == 1, it.getInt(9) == 1, it.getLong(10),
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

    fun historialDisponibilidad(limite: Int): List<CambioDisponibilidad> = conn.consultar(
        """
        SELECT h.producto_id, COALESCE(t.nombre, p.codigo), h.disponible, pe.identificador, h.fecha, h.motivo
        FROM disponibilidad_historial h JOIN productos p ON p.id = h.producto_id JOIN personal pe ON pe.id = h.registrado_por
        LEFT JOIN producto_textos t ON t.producto_id = p.id AND t.idioma = 'es'
        ORDER BY h.id DESC LIMIT ?
        """.trimIndent(),
        limite,
    ) { CambioDisponibilidad(it.getLong(1), it.getString(2), it.getInt(3) == 1, it.getString(4), it.getString(5), it.getString(6)) }

    fun detalle(productoId: Long): DetalleProducto? {
        val producto = productoBackoffice(productoId) ?: return null
        val validacion = conn.consultar(
            "SELECT pe.identificador, p.validado_en FROM productos p LEFT JOIN personal pe ON pe.id = p.validado_por WHERE p.id = ?",
            productoId,
        ) { it.getString(1) to it.getString(2) }.single()
        val evaluaciones = conn.consultar(
            """
            SELECT r.nombre_es, pr.resultado, pr.evidencia, pe.identificador, pr.validado_en
            FROM producto_restricciones pr JOIN restricciones r ON r.id = pr.restriccion_id JOIN personal pe ON pe.id = pr.validado_por
            WHERE pr.producto_id = ? ORDER BY r.tipo, r.nombre_es
            """.trimIndent(),
            productoId,
        ) { EvaluacionRestriccion(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5)) }
        return DetalleProducto(
            producto = producto,
            textos = conn.consultar(
                "SELECT idioma, nombre, descripcion, historia FROM producto_textos WHERE producto_id = ? ORDER BY idioma DESC",
                productoId,
            ) { TextoProducto(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) },
            ingredientes = conn.consultar(
                "SELECT i.nombre_es FROM producto_ingredientes pi JOIN ingredientes i ON i.id = pi.ingrediente_id WHERE pi.producto_id = ? ORDER BY 1",
                productoId,
            ) { it.getString(1) },
            alergenos = conn.consultar(
                """
                SELECT a.nombre_es, pa.origen FROM producto_alergenos pa JOIN alergenos a ON a.id = pa.alergeno_id WHERE pa.producto_id = ?
                UNION
                SELECT a.nombre_es, 'vía ingrediente: ' || i.nombre_es FROM producto_ingredientes pi
                JOIN ingredientes i ON i.id = pi.ingrediente_id
                JOIN ingrediente_alergenos ia ON ia.ingrediente_id = i.id JOIN alergenos a ON a.id = ia.alergeno_id
                WHERE pi.producto_id = ?
                ORDER BY 1, 2
                """.trimIndent(),
                productoId, productoId,
            ) { AlergenoProducto(it.getString(1), it.getString(2)) },
            preferencias = conn.consultar(
                "SELECT pr.nombre_es FROM producto_preferencias pp JOIN preferencias pr ON pr.id = pp.preferencia_id WHERE pp.producto_id = ? ORDER BY 1",
                productoId,
            ) { it.getString(1) },
            evaluaciones = evaluaciones,
            restriccionesSinEvaluar = conn.consultar(
                """
                SELECT nombre_es FROM restricciones r
                WHERE NOT EXISTS (SELECT 1 FROM producto_restricciones pr WHERE pr.producto_id = ? AND pr.restriccion_id = r.id)
                ORDER BY tipo, nombre_es
                """.trimIndent(),
                productoId,
            ) { it.getString(1) },
            validadoPor = validacion.first,
            validadoEn = validacion.second,
        )
    }

    /** Los triggers exigen personal activo de cocina o administración, textos es/en e ingredientes. */
    fun validar(productoId: Long, personalId: Long) {
        conn.ejecutar(
            "UPDATE productos SET validado_por = ?, validado_en = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?",
            personalId, productoId,
        )
    }

    fun pedible(sesionId: String, productoId: Long): ProductoPedible? = conn.consultar(
        """
        SELECT c.producto_id, t.nombre, c.precio_centimos, c.destino
        FROM v_productos_compatibles c
        JOIN sesiones s ON s.id = c.sesion_id
        JOIN producto_textos t ON t.producto_id = c.producto_id AND t.idioma = s.idioma
        WHERE c.sesion_id = ? AND c.producto_id = ?
        """.trimIndent(),
        sesionId, productoId,
    ) { ProductoPedible(it.getLong(1), it.getString(2), it.getLong(3), it.getString(4)) }.singleOrNull()
}
