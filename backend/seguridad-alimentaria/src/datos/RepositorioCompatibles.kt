package seguridad.datos

import comun.contratos.AlergenoFicha
import comun.contratos.AlergenosProducto
import comun.contratos.EvaluacionFicha
import comun.contratos.FichaSeguridad
import comun.contratos.ItemAVerificar
import comun.contratos.OpcionPerfil
import comun.contratos.ProductoPedible
import comun.datos.consultar
import java.sql.Connection

/**
 * Lecturas de seguridad alimentaria fuera del motor: qué se puede pedir según v_productos_compatibles,
 * listas de restricciones y la ficha de alérgenos que se muestra en la carta y al validar.
 */
class RepositorioCompatibles(private val conn: Connection) {

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

    /** Ítems que ya no pasarían la confirmación: dejaron de ser compatibles o cambiaron nombre, precio o destino. */
    fun itemsNoConfirmables(sesionId: String, items: List<ItemAVerificar>): List<Long> =
        items.filter { item ->
            val vigente = pedible(sesionId, item.productoId)
            vigente == null || vigente.nombre != item.nombre || vigente.precioCentimos != item.precioCentimos || vigente.destino != item.destino
        }.map { it.itemId }

    fun restricciones(idioma: String): List<OpcionPerfil> {
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        return conn.consultar("SELECT id, codigo, tipo, $columna FROM restricciones ORDER BY tipo, $columna") {
            OpcionPerfil(it.getLong(1), it.getString(2), it.getString(3), it.getString(4))
        }
    }

    /** Alérgenos validados por producto: declarados como contenido o vía ingredientes, y trazas. */
    fun alergenos(idioma: String): List<AlergenosProducto> {
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        val filas = conn.consultar(
            """
            SELECT pa.producto_id, a.$columna, pa.origen FROM producto_alergenos pa JOIN alergenos a ON a.id = pa.alergeno_id
            UNION
            SELECT pi.producto_id, a.$columna, 'contiene' FROM producto_ingredientes pi
            JOIN ingrediente_alergenos ia ON ia.ingrediente_id = pi.ingrediente_id JOIN alergenos a ON a.id = ia.alergeno_id
            ORDER BY 2
            """.trimIndent(),
        ) { Triple(it.getLong(1), it.getString(2), it.getString(3)) }
        return filas.groupBy { it.first }.map { (productoId, deProducto) ->
            val contiene = deProducto.filter { it.third == "contiene" }.map { it.second }.distinct()
            AlergenosProducto(
                productoId,
                contiene,
                deProducto.filter { it.third == "trazas" }.map { it.second }.distinct() - contiene.toSet(),
            )
        }
    }

    fun ficha(productoId: Long): FichaSeguridad = FichaSeguridad(
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
        ) { AlergenoFicha(it.getString(1), it.getString(2)) },
        evaluaciones = conn.consultar(
            """
            SELECT r.nombre_es, pr.resultado, pr.evidencia, pr.validado_por, pr.validado_en
            FROM producto_restricciones pr JOIN restricciones r ON r.id = pr.restriccion_id
            WHERE pr.producto_id = ? ORDER BY r.tipo, r.nombre_es
            """.trimIndent(),
            productoId,
        ) { EvaluacionFicha(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getString(5)) },
        restriccionesSinEvaluar = conn.consultar(
            """
            SELECT nombre_es FROM restricciones r
            WHERE NOT EXISTS (SELECT 1 FROM producto_restricciones pr WHERE pr.producto_id = ? AND pr.restriccion_id = r.id)
            ORDER BY tipo, nombre_es
            """.trimIndent(),
            productoId,
        ) { it.getString(1) },
    )
}
