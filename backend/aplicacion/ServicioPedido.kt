package aplicacion

import datos.FuenteConexiones
import datos.Pedido
import datos.RepositorioCatalogo
import datos.RepositorioPedidos
import datos.RepositorioRecomendacion
import datos.enTransaccion
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.SQLException

@Serializable
data class Carrito(val borrador: Pedido? = null, val enviados: List<Pedido>)

/**
 * Servicio de Pedido (RF05 · RF10 · RF11). Solo se agregan productos que la sesión puede pedir
 * según v_productos_compatibles; el pedido se envía únicamente con confirmación explícita y no se
 * cancela desde la app una vez enviado.
 */
class ServicioPedido(private val fuente: FuenteConexiones) {

    fun carrito(sesionId: String): Carrito = fuente.usar { conn ->
        ServicioSesion.sesionAbierta(conn, sesionId)
        val repo = RepositorioPedidos(conn)
        Carrito(repo.borrador(sesionId)?.let(repo::pedido), repo.pedidosEnviados(sesionId))
    }

    fun agregar(sesionId: String, productoId: Long, cantidad: Int, recomendacionId: Long?): Carrito {
        validarCantidad(cantidad, minimo = 1)
        fuente.usar { conn ->
            conn.enTransaccion {
                val estado = ServicioSesion.sesionAbierta(conn, sesionId)
                if (!estado.perfilCompleto) throw SesionNoDisponible(perfilIncompleto = true)
                val producto = RepositorioCatalogo(conn).pedible(sesionId, productoId)
                    ?: throw Conflicto("producto_no_pedible", "El producto no está disponible o no es compatible con el perfil")
                val recomendaciones = RepositorioRecomendacion(conn)
                if (recomendacionId != null && recomendaciones.productoDeRecomendacion(recomendacionId, sesionId) != productoId) {
                    throw SolicitudInvalida("recomendacion_invalida", "La recomendación no corresponde a este producto o sesión")
                }
                val pedidos = RepositorioPedidos(conn)
                val pedidoId = pedidos.borrador(sesionId) ?: pedidos.crearBorrador(sesionId)
                val existente = pedidos.itemDelProducto(pedidoId, productoId, recomendacionId)
                if (existente != null) {
                    val actual = pedidos.item(existente)!!.item.cantidad
                    validarCantidad(actual + cantidad, minimo = 1)
                    pedidos.cambiarCantidad(existente, actual + cantidad)
                } else {
                    pedidos.agregarItem(pedidoId, sesionId, producto, cantidad, recomendacionId)
                }
                if (recomendacionId != null) registrarUnaVez(recomendaciones, recomendacionId, "aceptada")
            }
        }
        return carrito(sesionId)
    }

    fun cambiarCantidad(sesionId: String, itemId: Long, cantidad: Int): Carrito {
        validarCantidad(cantidad, minimo = 0)
        fuente.usar { conn ->
            conn.enTransaccion {
                val item = itemEditable(conn, sesionId, itemId)
                val pedidos = RepositorioPedidos(conn)
                if (cantidad == 0) pedidos.eliminarItem(itemId) else pedidos.cambiarCantidad(itemId, cantidad)
                item.recomendacionId?.let { registrarUnaVez(RepositorioRecomendacion(conn), it, "modificada") }
            }
        }
        return carrito(sesionId)
    }

    fun eliminar(sesionId: String, itemId: Long): Carrito = cambiarCantidad(sesionId, itemId, 0)

    /** RF10 · RPR09: sin `confirmo = true` el pedido no sale; los triggers revalidan cada ítem y crean las comandas. */
    fun confirmar(sesionId: String, confirmo: Boolean): Pedido {
        if (!confirmo) throw SolicitudInvalida("confirmacion_requerida", "El comensal debe confirmar explícitamente el pedido")
        return fuente.usar { conn ->
            conn.enTransaccion {
                ServicioSesion.sesionAbierta(conn, sesionId)
                val pedidos = RepositorioPedidos(conn)
                val pedidoId = pedidos.borrador(sesionId) ?: throw Conflicto("pedido_vacio", "No hay pedido en borrador")
                if (pedidos.pedido(pedidoId)!!.items.isEmpty()) throw Conflicto("pedido_vacio", "El pedido no tiene ítems")
                val invalidos = pedidos.itemsNoConfirmables(pedidoId, sesionId)
                if (invalidos.isNotEmpty()) {
                    throw Conflicto("revisar_items", "Hay ítems que ya no están disponibles o cambiaron", invalidos)
                }
                try {
                    pedidos.confirmar(pedidoId)
                } catch (e: SQLException) {
                    throw Conflicto("revisar_items", e.message ?: "Confirmación rechazada por la base")
                }
                pedidos.pedido(pedidoId)!!
            }
        }
    }

    private fun itemEditable(conn: Connection, sesionId: String, itemId: Long): datos.ItemPedido {
        ServicioSesion.sesionAbierta(conn, sesionId)
        val item = RepositorioPedidos(conn).item(itemId)
        // Un comensal solo opera sobre los ítems de su propia sesión (RPR09).
        if (item == null || item.sesionId != sesionId) throw NoEncontrado("item_no_encontrado", "Ítem inexistente")
        if (item.estadoPedido != "borrador") throw Conflicto("pedido_ya_enviado", "El pedido ya fue enviado; pide cambios al mozo")
        return item.item
    }

    private fun registrarUnaVez(repo: RepositorioRecomendacion, recomendacionId: Long, accion: String) {
        if (!repo.tieneEvento(recomendacionId, accion)) repo.registrarEvento(recomendacionId, accion)
    }

    private fun validarCantidad(cantidad: Int, minimo: Int) {
        if (cantidad < minimo || cantidad > CANTIDAD_MAXIMA) {
            throw SolicitudInvalida("cantidad_invalida", "La cantidad debe estar entre $minimo y $CANTIDAD_MAXIMA")
        }
    }

    private companion object {
        const val CANTIDAD_MAXIMA = 20
    }
}

/** Trazabilidad (RF13 · RNF09): eventos sobre recomendaciones mostradas, sin datos personales. */
class ServicioTrazabilidad(private val fuente: FuenteConexiones) {
    private val acciones = setOf("aceptada", "rechazada", "modificada")

    fun registrar(sesionId: String, recomendacionId: Long, accion: String) {
        if (accion !in acciones) throw SolicitudInvalida("accion_invalida", "Acción no válida: $accion")
        fuente.usar { conn ->
            ServicioSesion.sesionAbierta(conn, sesionId)
            val repo = RepositorioRecomendacion(conn)
            repo.productoDeRecomendacion(recomendacionId, sesionId)
                ?: throw NoEncontrado("recomendacion_no_encontrada", "Recomendación inexistente")
            if (!repo.tieneEvento(recomendacionId, accion)) repo.registrarEvento(recomendacionId, accion)
        }
    }
}
