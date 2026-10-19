package pedido.aplicacion

import comun.Conflicto
import comun.NoEncontrado
import comun.PersonalAutenticado
import comun.SesionNoDisponible
import comun.SolicitudInvalida
import comun.contratos.ApiRecomendacion
import comun.contratos.ApiSeguridad
import comun.contratos.ApiSesion
import comun.contratos.ApiTrazabilidad
import comun.contratos.Evento
import comun.contratos.PublicadorEventos
import comun.datos.FuenteConexiones
import comun.datos.enTransaccion
import comun.exigirRol
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import pedido.datos.Comanda
import pedido.datos.ComandaConSesion
import pedido.datos.ItemPedido
import pedido.datos.Pedido
import pedido.datos.RepositorioComandas
import pedido.datos.RepositorioPedidos
import java.sql.SQLException

@Serializable
data class Carrito(val borrador: Pedido? = null, val enviados: List<Pedido>)

/**
 * Servicio de Pedido (RF05 · RF10 · RF11). Solo se agregan productos que el Servicio de Seguridad
 * Alimentaria declara pedibles para la sesión; el pedido se envía únicamente con confirmación
 * explícita y no se cancela desde la app una vez enviado. Al confirmar, los triggers revalidan cada
 * ítem y crean las comandas, que se publican en el bus de eventos para cocina y barra.
 */
class ServicioPedido(
    private val fuente: FuenteConexiones,
    private val sesion: ApiSesion,
    private val seguridad: ApiSeguridad,
    private val recomendaciones: ApiRecomendacion,
    private val trazabilidad: ApiTrazabilidad,
    private val eventos: PublicadorEventos,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun carrito(sesionId: String): Carrito {
        sesion.sesionAbierta(sesionId)
        return leerCarrito(sesionId)
    }

    private fun leerCarrito(sesionId: String): Carrito = fuente.usar { conn ->
        val repo = RepositorioPedidos(conn)
        Carrito(repo.borrador(sesionId)?.let(repo::pedido), repo.pedidosEnviados(sesionId))
    }

    fun agregar(sesionId: String, productoId: Long, cantidad: Int, recomendacionId: Long?): Carrito {
        validarCantidad(cantidad, minimo = 1)
        val estado = sesion.sesionAbierta(sesionId)
        if (!estado.perfilCompleto) throw SesionNoDisponible(perfilIncompleto = true)
        val producto = seguridad.pedible(sesionId, productoId)
            ?: throw Conflicto("producto_no_pedible", "El producto no está disponible o no es compatible con el perfil")
        if (recomendacionId != null && recomendaciones.productoDeRecomendacion(recomendacionId, sesionId) != productoId) {
            throw SolicitudInvalida("recomendacion_invalida", "La recomendación no corresponde a este producto o sesión")
        }
        fuente.usar { conn ->
            conn.enTransaccion {
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
            }
        }
        if (recomendacionId != null) trazar(recomendacionId, "aceptada")
        return leerCarrito(sesionId)
    }

    fun cambiarCantidad(sesionId: String, itemId: Long, cantidad: Int): Carrito {
        validarCantidad(cantidad, minimo = 0)
        sesion.sesionAbierta(sesionId)
        val item = fuente.usar { conn ->
            conn.enTransaccion {
                val item = itemEditable(RepositorioPedidos(conn), sesionId, itemId)
                val pedidos = RepositorioPedidos(conn)
                if (cantidad == 0) pedidos.eliminarItem(itemId) else pedidos.cambiarCantidad(itemId, cantidad)
                item
            }
        }
        item.recomendacionId?.let { trazar(it, "modificada") }
        return leerCarrito(sesionId)
    }

    fun eliminar(sesionId: String, itemId: Long): Carrito = cambiarCantidad(sesionId, itemId, 0)

    /** RF10 · RPR09: sin `confirmo = true` el pedido no sale; los triggers revalidan cada ítem y crean las comandas. */
    fun confirmar(sesionId: String, confirmo: Boolean): Pedido {
        if (!confirmo) throw SolicitudInvalida("confirmacion_requerida", "El comensal debe confirmar explícitamente el pedido")
        sesion.sesionAbierta(sesionId)
        val (pedidoId, items) = fuente.usar { conn ->
            val pedidos = RepositorioPedidos(conn)
            val pedidoId = pedidos.borrador(sesionId) ?: throw Conflicto("pedido_vacio", "No hay pedido en borrador")
            val items = pedidos.itemsAVerificar(pedidoId)
            if (items.isEmpty()) throw Conflicto("pedido_vacio", "El pedido no tiene ítems")
            pedidoId to items
        }
        val invalidos = seguridad.itemsNoConfirmables(sesionId, items)
        if (invalidos.isNotEmpty()) {
            throw Conflicto("revisar_items", "Hay ítems que ya no están disponibles o cambiaron", invalidos)
        }
        val (pedido, comandas) = fuente.usar { conn ->
            conn.enTransaccion {
                val pedidos = RepositorioPedidos(conn)
                try {
                    pedidos.confirmar(pedidoId)
                } catch (e: SQLException) {
                    throw Conflicto("revisar_items", e.message ?: "Confirmación rechazada por la base")
                }
                pedidos.pedido(pedidoId)!! to pedidos.comandas(pedidoId)
            }
        }
        comandas.forEach { (id, destino) -> eventos.publicar(Evento(destino, Evento.COMANDA_NUEVA, id.toString())) }
        return pedido
    }

    private fun itemEditable(pedidos: RepositorioPedidos, sesionId: String, itemId: Long): ItemPedido {
        val item = pedidos.item(itemId)
        // Un comensal solo opera sobre los ítems de su propia sesión (RPR09).
        if (item == null || item.sesionId != sesionId) throw NoEncontrado("item_no_encontrado", "Ítem inexistente")
        if (item.estadoPedido != "borrador") throw Conflicto("pedido_ya_enviado", "El pedido ya fue enviado; pide cambios al mozo")
        return item.item
    }

    /** RF13: aceptada o modificada, una vez por recomendación; si la trazabilidad falla, el pedido sigue. */
    private fun trazar(recomendacionId: Long, accion: String) {
        try {
            trazabilidad.registrarEvento(recomendacionId, accion)
        } catch (e: Exception) {
            log.warn("Evento {} sin registrar para la recomendación {}: {}", accion, recomendacionId, e.message)
        }
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

/**
 * Comandas a cocina/barra (RF05). La tabla comandas es la bandeja: la pantalla de cada destino recibe
 * el aviso por el bus de eventos y además consulta periódicamente; cada consulta de una comanda
 * pendiente cuenta como intento de entrega, y la comanda queda pendiente hasta que la pantalla
 * confirma la recepción.
 */
class ServicioComandas(
    private val fuente: FuenteConexiones,
    private val sesion: ApiSesion,
    private val eventos: PublicadorEventos,
) {
    private val destinos = setOf("cocina", "barra")
    private val estados = setOf("recibida", "preparando", "lista", "entregada")

    fun activas(personal: PersonalAutenticado, destino: String): List<Comanda> {
        exigirDestino(personal, destino)
        val comandas = fuente.usar { conn ->
            val repo = RepositorioComandas(conn)
            repo.registrarIntentos(destino)
            repo.comandasActivas(destino)
        }
        return conPuesto(comandas)
    }

    /** Comandas sin recepción tras [minutos]: alerta para sala, que puede llevarla a mano (RNF03). */
    fun atrasadas(personal: PersonalAutenticado, minutos: Int = MINUTOS_ATRASO): List<Comanda> {
        personal.exigirRol("sala")
        return conPuesto(fuente.usar { RepositorioComandas(it).comandasAtrasadas(minutos) })
    }

    fun avanzar(personal: PersonalAutenticado, comandaId: Long, estado: String) {
        if (estado !in estados) throw SolicitudInvalida("estado_invalido", "Estado no válido: $estado")
        val destino = fuente.usar { conn ->
            val repo = RepositorioComandas(conn)
            val (pedidoId, destino) = repo.comanda(comandaId) ?: throw NoEncontrado("comanda_no_encontrada", "Comanda inexistente")
            exigirDestino(personal, destino)
            try {
                conn.enTransaccion {
                    repo.cambiarEstadoComanda(comandaId, estado)
                    repo.sincronizarPedido(pedidoId)
                }
            } catch (e: SQLException) {
                throw Conflicto("transicion_invalida", e.message ?: "Transición de comanda no permitida")
            }
            destino
        }
        eventos.publicar(Evento(destino, Evento.COMANDA_ACTUALIZADA, comandaId.toString()))
    }

    private fun conPuesto(comandas: List<ComandaConSesion>): List<Comanda> {
        val puestos = sesion.puestosDeSesiones(comandas.map { it.sesionId })
        return comandas.map { it.comanda.copy(puesto = puestos[it.sesionId] ?: "?") }
    }

    private fun exigirDestino(personal: PersonalAutenticado, destino: String) {
        if (destino !in destinos) throw SolicitudInvalida("destino_invalido", "Destino no válido: $destino")
        personal.exigirRol(destino)
    }

    companion object {
        /** El presupuesto es de 2 s hasta cocina/barra; a los 2 minutos sin recepción algo falló. */
        const val MINUTOS_ATRASO = 2
    }
}
