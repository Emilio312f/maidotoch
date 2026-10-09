package datos

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

@Serializable
data class Llamado(
    val id: Long,
    val motivo: String,
    val estado: String,
    val puesto: String,
    val producto: String? = null,
    val creadoEn: String,
    val atendidoEn: String? = null,
)

/** Comandas a cocina/barra (bandeja con clave de idempotencia) y llamados al mozo. */
class RepositorioOperacion(private val conn: Connection) {

    private val puestoDePedido = """
        (SELECT ps.codigo FROM pedidos pe JOIN sesiones s ON s.id = pe.sesion_id
         JOIN accesos a ON a.id = s.acceso_id JOIN puntos_servicio ps ON ps.id = a.punto_id WHERE pe.id = c.pedido_id)
    """.trimIndent()

    fun comandasActivas(destino: String): List<Comanda> = conn.consultar(
        """
        SELECT c.id, c.pedido_id, c.destino, c.estado, $puestoDePedido, c.creada_en, c.recibida_en, c.intentos
        FROM comandas c WHERE c.destino = ? AND c.estado <> 'entregada' ORDER BY c.creada_en
        """.trimIndent(),
        destino,
    ) { Comanda(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7), it.getInt(8), emptyList()) }
        .map { c ->
            c.copy(
                items = conn.consultar(
                    "SELECT nombre_snapshot, cantidad FROM pedido_items WHERE pedido_id = ? AND destino = ? ORDER BY id",
                    c.pedidoId, c.destino,
                ) { ItemComanda(it.getString(1), it.getInt(2)) },
            )
        }

    /** Comandas que ninguna pantalla confirmó en [minutos]: sala las toma a mano (respaldo manual, RNF03). */
    fun comandasAtrasadas(minutos: Int): List<Comanda> = conn.consultar(
        """
        SELECT c.id, c.pedido_id, c.destino, c.estado, $puestoDePedido, c.creada_en, c.recibida_en, c.intentos
        FROM comandas c WHERE c.estado = 'pendiente' AND c.creada_en < strftime('%Y-%m-%dT%H:%M:%fZ','now', ?)
        ORDER BY c.creada_en
        """.trimIndent(),
        "${-minutos} minutes",
    ) { Comanda(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7), it.getInt(8), emptyList()) }
        .map { c ->
            c.copy(
                items = conn.consultar(
                    "SELECT nombre_snapshot, cantidad FROM pedido_items WHERE pedido_id = ? AND destino = ? ORDER BY id",
                    c.pedidoId, c.destino,
                ) { ItemComanda(it.getString(1), it.getInt(2)) },
            )
        }

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

    private val selectLlamado = """
        SELECT l.id, l.motivo, l.estado, ps.codigo, t.nombre, l.creado_en, l.atendido_en
        FROM llamados_mozo l JOIN sesiones s ON s.id = l.sesion_id
        JOIN accesos a ON a.id = s.acceso_id JOIN puntos_servicio ps ON ps.id = a.punto_id
        LEFT JOIN producto_textos t ON t.producto_id = l.producto_id AND t.idioma = s.idioma
    """.trimIndent()

    private fun leerLlamado(rs: java.sql.ResultSet) =
        Llamado(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7))

    fun llamadoPendiente(sesionId: String, motivo: String): Long? = conn.consultar(
        "SELECT id FROM llamados_mozo WHERE sesion_id = ? AND motivo = ? AND estado = 'pendiente'",
        sesionId, motivo,
    ) { it.getLong(1) }.firstOrNull()

    fun crearLlamado(sesionId: String, motivo: String, productoId: Long?): Long = conn.insertar(
        "INSERT INTO llamados_mozo(sesion_id, motivo, producto_id) VALUES (?, ?, ?)",
        sesionId, motivo, productoId,
    )

    fun llamado(id: Long): Llamado? = conn.consultar("$selectLlamado WHERE l.id = ?", id, fila = ::leerLlamado).singleOrNull()

    fun llamadosDeSesion(sesionId: String): List<Llamado> =
        conn.consultar("$selectLlamado WHERE l.sesion_id = ? ORDER BY l.id DESC", sesionId, fila = ::leerLlamado)

    fun llamadosPendientes(): List<Llamado> =
        conn.consultar("$selectLlamado WHERE l.estado = 'pendiente' ORDER BY l.creado_en, l.id", fila = ::leerLlamado)

    fun atenderLlamado(id: Long, personalId: Long): Int = conn.ejecutar(
        "UPDATE llamados_mozo SET estado = 'atendido', atendido_por = ?, atendido_en = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ? AND estado = 'pendiente'",
        personalId, id,
    )
}
