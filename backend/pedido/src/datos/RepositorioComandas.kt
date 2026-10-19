package pedido.datos

import comun.datos.consultar
import comun.datos.ejecutar
import kotlinx.serialization.Serializable
import java.sql.Connection

@Serializable
data class ItemComanda(val nombre: String, val cantidad: Int)

@Serializable
data class Comanda(
    val id: Long,
    val pedidoId: String,
    val destino: String,
    val estado: String,
    val puesto: String,
    val creadaEn: String,
    val recibidaEn: String? = null,
    val intentos: Int,
    val items: List<ItemComanda>,
)

/** Comanda leída de la base con la sesión de su pedido; el código de puesto lo da el Servicio de Sesión. */
data class ComandaConSesion(val comanda: Comanda, val sesionId: String)

/** Comandas a cocina/barra: la tabla comandas es la bandeja, con clave de idempotencia. */
class RepositorioComandas(private val conn: Connection) {

    private val seleccion = """
        SELECT c.id, c.pedido_id, c.destino, c.estado, pe.sesion_id, c.creada_en, c.recibida_en, c.intentos
        FROM comandas c JOIN pedidos pe ON pe.id = c.pedido_id
    """.trimIndent()

    private fun leer(rs: java.sql.ResultSet) = ComandaConSesion(
        Comanda(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), "", rs.getString(6), rs.getString(7), rs.getInt(8), emptyList()),
        rs.getString(5),
    )

    private fun conItems(c: ComandaConSesion) = c.copy(
        comanda = c.comanda.copy(
            items = conn.consultar(
                "SELECT nombre_snapshot, cantidad FROM pedido_items WHERE pedido_id = ? AND destino = ? ORDER BY id",
                c.comanda.pedidoId, c.comanda.destino,
            ) { ItemComanda(it.getString(1), it.getInt(2)) },
        ),
    )

    fun comandasActivas(destino: String): List<ComandaConSesion> =
        conn.consultar("$seleccion WHERE c.destino = ? AND c.estado <> 'entregada' ORDER BY c.creada_en", destino, fila = ::leer).map(::conItems)

    /** Comandas que ninguna pantalla confirmó en [minutos]: sala las toma a mano (respaldo manual, RNF03). */
    fun comandasAtrasadas(minutos: Int): List<ComandaConSesion> = conn.consultar(
        "$seleccion WHERE c.estado = 'pendiente' AND c.creada_en < strftime('%Y-%m-%dT%H:%M:%fZ','now', ?) ORDER BY c.creada_en",
        "${-minutos} minutes",
        fila = ::leer,
    ).map(::conItems)

    /** Cada entrega a la pantalla de una comanda aún no recibida cuenta como un intento. */
    fun registrarIntentos(destino: String) {
        conn.ejecutar("UPDATE comandas SET intentos = intentos + 1 WHERE destino = ? AND estado = 'pendiente'", destino)
    }

    fun comanda(id: Long): Pair<String, String>? =
        conn.consultar("SELECT pedido_id, destino FROM comandas WHERE id = ?", id) { it.getString(1) to it.getString(2) }.singleOrNull()

    /** Las transiciones válidas las impone el trigger comanda_transicion. */
    fun cambiarEstadoComanda(id: Long, estado: String) {
        if (estado == "recibida") {
            conn.ejecutar("UPDATE comandas SET estado = ?, recibida_en = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?", estado, id)
        } else {
            conn.ejecutar("UPDATE comandas SET estado = ? WHERE id = ?", estado, id)
        }
    }

    /** Pedido confirmado → enviado cuando todas sus comandas fueron recibidas; → completado al entregarse todas. */
    fun sincronizarPedido(pedidoId: String) {
        conn.ejecutar(
            """
            UPDATE pedidos SET estado = 'enviado' WHERE id = ? AND estado = 'confirmado'
            AND NOT EXISTS (SELECT 1 FROM comandas WHERE pedido_id = ? AND estado = 'pendiente')
            """.trimIndent(),
            pedidoId, pedidoId,
        )
        conn.ejecutar(
            """
            UPDATE pedidos SET estado = 'completado' WHERE id = ? AND estado = 'enviado'
            AND NOT EXISTS (SELECT 1 FROM comandas WHERE pedido_id = ? AND estado <> 'entregada')
            """.trimIndent(),
            pedidoId, pedidoId,
        )
    }
}
