package integracion

import aplicacion.Conflicto
import aplicacion.NoEncontrado
import aplicacion.SesionNoDisponible
import aplicacion.SolicitudInvalida
import datos.consultar
import kotlinx.coroutines.runBlocking
import soporte.Escenario
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Carrito, confirmación y comandas (RF05 · RF10 · RF11 · RF13) sobre el catálogo FICTICIO. */
class PedidoTest {
    private val e = Escenario()
    private val pedidos = e.app.pedidos

    @AfterTest
    fun cerrar() = e.close()

    @Test
    fun `no se puede agregar un producto incompatible con el perfil`() {
        val sesion = e.sesion(restricciones = listOf("FICTICIO-alergia-gluten"))

        // Gluten solo vía ingrediente (masa de trigo), sin alérgeno declarado en el producto.
        val error = assertFailsWith<Conflicto> { pedidos.agregar(sesion, e.producto("FICTICIO-P05"), 1, null) }
        assertEquals("producto_no_pedible", error.codigo)
    }

    @Test
    fun `sin perfil completo no se agregan productos`() {
        val sesion = e.app.sesiones.iniciar(e.tokenMesa, "es").sesionId

        val error = assertFailsWith<SesionNoDisponible> { pedidos.agregar(sesion, e.producto("FICTICIO-P04"), 1, null) }
        assertEquals("perfil_incompleto", error.codigo)
    }

    @Test
    fun `se agregan, modifican y eliminan items antes de confirmar`() {
        val sesion = e.sesion()
        val ensalada = e.producto("FICTICIO-P04")
        val bebida = e.producto("FICTICIO-B01")

        pedidos.agregar(sesion, ensalada, 1, null)
        pedidos.agregar(sesion, ensalada, 1, null)
        var carrito = pedidos.agregar(sesion, bebida, 2, null)
        assertEquals(listOf(2, 2), carrito.borrador!!.items.map { it.cantidad })
        assertEquals(2 * 4200L + 2 * 1800L, carrito.borrador!!.totalCentimos)

        val itemBebida = carrito.borrador!!.items.first { it.productoId == bebida }.id
        carrito = pedidos.cambiarCantidad(sesion, itemBebida, 1)
        assertEquals(2 * 4200L + 1800L, carrito.borrador!!.totalCentimos)

        carrito = pedidos.eliminar(sesion, itemBebida)
        assertEquals(listOf(ensalada), carrito.borrador!!.items.map { it.productoId })
    }

    @Test
    fun `una sesion no puede tocar los items de otra`() {
        val propia = e.sesion()
        val ajena = e.sesion()
        val item = pedidos.agregar(propia, e.producto("FICTICIO-P04"), 1, null).borrador!!.items.single().id

        assertFailsWith<NoEncontrado> { pedidos.cambiarCantidad(ajena, item, 5) }
        assertFailsWith<NoEncontrado> { pedidos.eliminar(ajena, item) }
        assertNull(pedidos.carrito(ajena).borrador)
    }

    @Test
    fun `sin confirmacion explicita el pedido no se envia`() {
        val sesion = e.sesion()
        pedidos.agregar(sesion, e.producto("FICTICIO-P04"), 1, null)

        val error = assertFailsWith<SolicitudInvalida> { pedidos.confirmar(sesion, confirmo = false) }
        assertEquals("confirmacion_requerida", error.codigo)
        assertEquals("borrador", pedidos.carrito(sesion).borrador!!.estado)
        assertEquals(0, e.db.conn.consultar("SELECT count(*) FROM comandas") { it.getInt(1) }.single())
    }

    @Test
    fun `al confirmar se crea una comanda por destino y el pedido ya no se modifica`() {
        val sesion = e.sesion()
        pedidos.agregar(sesion, e.producto("FICTICIO-P04"), 1, null)
        pedidos.agregar(sesion, e.producto("FICTICIO-P01"), 1, null)
        val item = pedidos.agregar(sesion, e.producto("FICTICIO-B02"), 1, null).borrador!!.items.last().id

        val pedido = pedidos.confirmar(sesion, confirmo = true)

        assertEquals("confirmado", pedido.estado)
        assertEquals(listOf("barra", "cocina"), pedido.comandas.map { it.destino })
        assertTrue(pedido.comandas.all { it.estado == "pendiente" })
        val error = assertFailsWith<Conflicto> { pedidos.cambiarCantidad(sesion, item, 3) }
        assertEquals("pedido_ya_enviado", error.codigo)
        assertNull(pedidos.carrito(sesion).borrador)
        assertEquals(1, pedidos.carrito(sesion).enviados.size)
    }

    @Test
    fun `un item que dejo de estar disponible bloquea la confirmacion e indica cual es`() {
        val sesion = e.sesion()
        val ensalada = e.producto("FICTICIO-P04")
        val item = pedidos.agregar(sesion, ensalada, 1, null).borrador!!.items.single().id
        e.app.backoffice.cambiarDisponibilidad(e.cocina, ensalada, false, "FICTICIO: se acabó")

        val error = assertFailsWith<Conflicto> { pedidos.confirmar(sesion, confirmo = true) }

        assertEquals("revisar_items", error.codigo)
        assertEquals(listOf(item), error.detalle)
    }

    @Test
    fun `agregar desde una recomendacion la marca aceptada y modificarla la marca modificada`() = runBlocking<Unit> {
        val sesion = e.sesion(preferencias = listOf("FICTICIO-ligero"))
        val recomendado = e.app.orquestador.recomendar(sesion, "").recomendaciones.first()

        val item = pedidos.agregar(sesion, recomendado.productoId, 1, recomendado.recomendacionId).borrador!!.items.single()
        pedidos.cambiarCantidad(sesion, item.id, 2)

        val eventos = e.db.conn.consultar(
            "SELECT accion FROM recomendacion_eventos WHERE recomendacion_id = ? ORDER BY id",
            recomendado.recomendacionId,
        ) { it.getString(1) }
        assertEquals(listOf("mostrada", "aceptada", "modificada"), eventos)
    }

    @Test
    fun `una recomendacion de otro producto no se puede usar al agregar`() = runBlocking<Unit> {
        val sesion = e.sesion()
        val recomendado = e.app.orquestador.recomendar(sesion, "").recomendaciones.first()
        val otro = listOf("FICTICIO-P04", "FICTICIO-B01").map(e::producto).first { it != recomendado.productoId }

        assertFailsWith<SolicitudInvalida> { pedidos.agregar(sesion, otro, 1, recomendado.recomendacionId) }
    }

    @Test
    fun `rechazar una recomendacion queda registrado`() = runBlocking<Unit> {
        val sesion = e.sesion()
        val recomendado = e.app.orquestador.recomendar(sesion, "").recomendaciones.first()

        e.app.trazabilidad.registrar(sesion, recomendado.recomendacionId, "rechazada")
        e.app.trazabilidad.registrar(sesion, recomendado.recomendacionId, "rechazada")

        val metricas = e.app.backoffice.metricas(e.admin)
        assertEquals(1, metricas.rechazadas)
        assertFailsWith<NoEncontrado> { e.app.trazabilidad.registrar(e.sesion(), recomendado.recomendacionId, "aceptada") }
    }
}
