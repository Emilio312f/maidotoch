package datos

import kotlinx.serialization.Serializable
import java.sql.Connection
import java.util.UUID

@Serializable
data class ItemPedido(
    val id: Long,
    val productoId: Long,
    val nombre: String,
    val cantidad: Int,
    val precioUnitarioCentimos: Long,
    val destino: String,
    val recomendacionId: Long? = null,
)

@Serializable
data class EstadoComanda(val destino: String, val estado: String)

@Serializable
data class Pedido(
    val id: String,
    val estado: String,
    val creadoEn: String,
    val confirmadoEn: String? = null,
    val totalCentimos: Long,
    val items: List<ItemPedido>,
    val comandas: List<EstadoComanda>,
)

data class ItemConPedido(val item: ItemPedido, val pedidoId: String, val sesionId: String, val estadoPedido: String)

class RepositorioPedidos(private val conn: Connection) {

    fun borrador(sesionId: String): String? = conn.consultar(
        "SELECT id FROM pedidos WHERE sesion_id = ? AND estado = 'borrador' ORDER BY creado_en LIMIT 1",
        sesionId,
    ) { it.getString(1) }.singleOrNull()

    fun crearBorrador(sesionId: String): String {
        val id = UUID.randomUUID().toString()
        conn.ejecutar("INSERT INTO pedidos(id, sesion_id) VALUES (?, ?)", id, sesionId)
        return id
    }

    fun pedido(pedidoId: String): Pedido? {
        val items = conn.consultar(
            """
            SELECT id, producto_id, nombre_snapshot, cantidad, precio_unitario_centimos, destino, recomendacion_id
            FROM pedido_items WHERE pedido_id = ? ORDER BY id
            """.trimIndent(),
            pedidoId,
        ) {
            ItemPedido(it.getLong(1), it.getLong(2), it.getString(3), it.getInt(4), it.getLong(5), it.getString(6), it.getObject(7)?.let { _ -> it.getLong(7) })
        }
        val comandas = conn.consultar("SELECT destino, estado FROM comandas WHERE pedido_id = ? ORDER BY destino", pedidoId) {
            EstadoComanda(it.getString(1), it.getString(2))
        }
        return conn.consultar(
            "SELECT p.id, p.estado, p.creado_en, p.confirmado_en, t.total_centimos FROM pedidos p JOIN v_totales_pedidos t ON t.id = p.id WHERE p.id = ?",
            pedidoId,
        ) { Pedido(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5), items, comandas) }.singleOrNull()
    }

    fun pedidosEnviados(sesionId: String): List<Pedido> = conn.consultar(
        "SELECT id FROM pedidos WHERE sesion_id = ? AND estado <> 'borrador' ORDER BY confirmado_en DESC",
        sesionId,
    ) { it.getString(1) }.mapNotNull { pedido(it) }

    fun item(itemId: Long): ItemConPedido? = conn.consultar(
        """
        SELECT i.id, i.producto_id, i.nombre_snapshot, i.cantidad, i.precio_unitario_centimos, i.destino, i.recomendacion_id,
               i.pedido_id, i.sesion_id, p.estado
        FROM pedido_items i JOIN pedidos p ON p.id = i.pedido_id WHERE i.id = ?
        """.trimIndent(),
        itemId,
    ) {
        ItemConPedido(
            ItemPedido(it.getLong(1), it.getLong(2), it.getString(3), it.getInt(4), it.getLong(5), it.getString(6), it.getObject(7)?.let { _ -> it.getLong(7) }),
            it.getString(8), it.getString(9), it.getString(10),
        )
    }.singleOrNull()

    fun itemDelProducto(pedidoId: String, productoId: Long, recomendacionId: Long?): Long? = conn.consultar(
        "SELECT id FROM pedido_items WHERE pedido_id = ? AND producto_id = ? AND recomendacion_id IS ?",
        pedidoId, productoId, recomendacionId,
    ) { it.getLong(1) }.firstOrNull()

    fun agregarItem(pedidoId: String, sesionId: String, producto: ProductoPedible, cantidad: Int, recomendacionId: Long?): Long = conn.insertar(
        """
        INSERT INTO pedido_items(pedido_id, sesion_id, producto_id, cantidad, precio_unitario_centimos, nombre_snapshot, destino, recomendacion_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        pedidoId, sesionId, producto.productoId, cantidad, producto.precioCentimos, producto.nombre, producto.destino, recomendacionId,
    )

    fun cambiarCantidad(itemId: Long, cantidad: Int) {
        conn.ejecutar("UPDATE pedido_items SET cantidad = ? WHERE id = ?", cantidad, itemId)
    }

    fun eliminarItem(itemId: Long) {
        conn.ejecutar("DELETE FROM pedido_items WHERE id = ?", itemId)
    }

    /** El trigger pedido_confirmar revalida cada ítem contra v_productos_compatibles y crea las comandas. */
    fun confirmar(pedidoId: String) {
        conn.ejecutar(
            "UPDATE pedidos SET estado = 'confirmado', confirmado_en = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ? AND estado = 'borrador'",
            pedidoId,
        )
    }

    /** Ítems que ya no pasarían la confirmación: dejaron de ser compatibles o cambiaron nombre, precio o destino. */
    fun itemsNoConfirmables(pedidoId: String, sesionId: String): List<Long> = conn.consultar(
        """
        SELECT i.id FROM pedido_items i WHERE i.pedido_id = ? AND NOT EXISTS (
          SELECT 1 FROM v_productos_compatibles c JOIN sesiones s ON s.id = c.sesion_id
          JOIN producto_textos t ON t.producto_id = c.producto_id AND t.idioma = s.idioma
          WHERE c.sesion_id = ? AND c.producto_id = i.producto_id AND c.precio_centimos = i.precio_unitario_centimos
            AND c.destino = i.destino AND t.nombre = i.nombre_snapshot)
        """.trimIndent(),
        pedidoId, sesionId,
    ) { it.getLong(1) }
}
