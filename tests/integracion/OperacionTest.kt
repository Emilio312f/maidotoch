package integracion

import comun.Conflicto
import comun.NoAutenticado
import comun.NoEncontrado
import comun.Prohibido
import comun.SesionNoDisponible
import comun.SolicitudInvalida
import comun.datos.consultar
import soporte.Escenario
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Comandas, llamados, back-office, puestos y sesiones sobre el catálogo FICTICIO. */
class OperacionTest {
    private val e = Escenario()

    @AfterTest
    fun cerrar() = e.close()

    private fun pedidoConfirmado(sesion: String = e.sesion()): String {
        e.app.pedidos.agregar(sesion, e.producto("FICTICIO-P04"), 1, null)
        e.app.pedidos.agregar(sesion, e.producto("FICTICIO-B01"), 1, null)
        return e.app.pedidos.confirmar(sesion, true).id
    }

    private fun estadoPedido(id: String) = e.db.conn.consultar("SELECT estado FROM pedidos WHERE id = ?", id) { it.getString(1) }.single()

    @Test
    fun `el pedido pasa a enviado cuando cocina y barra reciben y a completado cuando todo se entrega`() {
        val pedido = pedidoConfirmado()
        val cocina = e.app.comandas.activas(e.cocina, "cocina").single()
        val barra = e.app.comandas.activas(e.barra, "barra").single()
        assertEquals("T1", cocina.puesto)
        assertEquals(listOf("Ensalada de quinua de prueba [FICTICIO]"), cocina.items.map { it.nombre })

        e.app.comandas.avanzar(e.cocina, cocina.id, "recibida")
        assertEquals("confirmado", estadoPedido(pedido))
        e.app.comandas.avanzar(e.barra, barra.id, "recibida")
        assertEquals("enviado", estadoPedido(pedido))

        listOf("preparando", "lista", "entregada").forEach {
            e.app.comandas.avanzar(e.cocina, cocina.id, it)
            e.app.comandas.avanzar(e.barra, barra.id, it)
        }
        assertEquals("completado", estadoPedido(pedido))
        assertTrue(e.app.comandas.activas(e.cocina, "cocina").isEmpty())
    }

    @Test
    fun `cada consulta de una comanda pendiente cuenta como intento de entrega`() {
        pedidoConfirmado()
        e.app.comandas.activas(e.cocina, "cocina")
        val comanda = e.app.comandas.activas(e.cocina, "cocina").single()

        assertEquals(2, comanda.intentos)
        e.app.comandas.avanzar(e.cocina, comanda.id, "recibida")
        assertEquals(2, e.app.comandas.activas(e.cocina, "cocina").single().intentos)
    }

    @Test
    fun `barra no opera comandas de cocina y no se saltan estados`() {
        pedidoConfirmado()
        val cocina = e.app.comandas.activas(e.cocina, "cocina").single()

        assertFailsWith<Prohibido> { e.app.comandas.avanzar(e.barra, cocina.id, "recibida") }
        assertFailsWith<Prohibido> { e.app.comandas.activas(e.sala, "cocina") }
        val error = assertFailsWith<Conflicto> { e.app.comandas.avanzar(e.cocina, cocina.id, "lista") }
        assertEquals("transicion_invalida", error.codigo)
    }

    @Test
    fun `el llamado al mozo no se duplica y lo atiende sala`() {
        val sesion = e.sesion()
        val llamado = e.app.atencion.llamar(sesion, "asistencia", null)
        assertEquals(llamado.id, e.app.atencion.llamar(sesion, "asistencia", null).id)
        e.app.atencion.llamar(sesion, "restriccion_incierta", e.producto("FICTICIO-P03"))

        val pendientes = e.app.atencion.pendientes(e.sala)
        assertEquals(listOf("asistencia", "restriccion_incierta"), pendientes.map { it.motivo })
        assertEquals("Arroz salteado con langostinos de prueba [FICTICIO]", pendientes.last().producto)

        assertFailsWith<Prohibido> { e.app.atencion.atender(e.cocina, llamado.id) }
        assertEquals("atendido", e.app.atencion.atender(e.sala, llamado.id).estado)
        assertEquals(1, e.app.atencion.pendientes(e.sala).size)
        assertFailsWith<SolicitudInvalida> { e.app.atencion.llamar(sesion, "otro", null) }
    }

    @Test
    fun `login del back-office`() {
        val sesion = e.app.personal.iniciarSesion("cocina-prueba", Escenario.CLAVE)
        assertEquals("cocina", e.app.personal.autenticar(sesion.token).rol)

        assertFailsWith<NoAutenticado> { e.app.personal.iniciarSesion("cocina-prueba", "clave-equivocada") }
        assertFailsWith<NoAutenticado> { e.app.personal.iniciarSesion("no-existe", Escenario.CLAVE) }
        assertFailsWith<NoAutenticado> { e.app.personal.autenticar("token-inventado") }

        e.app.personal.cerrarSesion(sesion.token)
        assertFailsWith<NoAutenticado> { e.app.personal.autenticar(sesion.token) }

        e.db.ejecutar("UPDATE personal SET activo = 0 WHERE identificador = 'sala-prueba'")
        assertFailsWith<NoAutenticado> { e.app.personal.iniciarSesion("sala-prueba", Escenario.CLAVE) }
    }

    @Test
    fun `la clave se guarda como hash y el identificador no puede ser un nombre de persona`() {
        val guardado = e.db.conn.consultar("SELECT hash FROM personal_credenciales") { it.getString(1) }
        assertFalse(guardado.any { Escenario.CLAVE in it })
        assertFailsWith<SolicitudInvalida> { e.app.personal.crear("Juan Pérez", "sala", Escenario.CLAVE) }
        assertFailsWith<SolicitudInvalida> { e.app.personal.crear("sala-2", "sala", "corta") }
    }

    @Test
    fun `el cambio de disponibilidad queda en el historial y la carta lo refleja de inmediato`() {
        val sesion = e.sesion()
        val ensalada = e.producto("FICTICIO-P04")
        assertTrue(e.app.catalogo.cartaParaSesion(sesion).productos.first { it.producto.id == ensalada }.producto.disponible)

        e.app.backoffice.cambiarDisponibilidad(e.cocina, ensalada, false, "Se acabó la quinua")

        val enCarta = e.app.catalogo.cartaParaSesion(sesion).productos.first { it.producto.id == ensalada }
        assertFalse(enCarta.producto.disponible)
        val ultimo = e.app.backoffice.historial(e.cocina).first()
        assertEquals(Triple(ensalada, "cocina-prueba", "Se acabó la quinua"), Triple(ultimo.productoId, ultimo.registradoPor, ultimo.motivo))
        assertFailsWith<SolicitudInvalida> { e.app.backoffice.cambiarDisponibilidad(e.cocina, ensalada, true, " ") }
        assertFailsWith<Prohibido> { e.app.backoffice.cambiarDisponibilidad(e.cocina, e.producto("FICTICIO-B01"), false, "x") }
        assertFailsWith<Prohibido> { e.app.backoffice.cambiarDisponibilidad(e.sala, ensalada, true, "x") }
    }

    @Test
    fun `la aptitud de cada plato en la carta sale del motor de restricciones`() {
        val sesion = e.sesion(restricciones = listOf("FICTICIO-alergia-mani", "FICTICIO-alergia-gluten"))

        val aptitud = e.app.catalogo.cartaParaSesion(sesion).productos.associate { it.producto.id to it.aptitud }

        assertEquals("apto", aptitud[e.producto("FICTICIO-P01")])
        assertEquals("no_apto", aptitud[e.producto("FICTICIO-P02")]) // trazas de maní
        assertEquals("no_apto", aptitud[e.producto("FICTICIO-P03")]) // gluten vía salsa de soya
        assertEquals("no_apto", aptitud[e.producto("FICTICIO-P05")]) // gluten solo vía ingrediente
        assertEquals("no_apto", aptitud[e.producto("FICTICIO-P06")]) // trazas de maní
        assertEquals("apto", aptitud[e.producto("FICTICIO-B02")])
    }

    @Test
    fun `un plato sin evaluacion para la restriccion aparece como consultar al mozo`() {
        val sesion = e.sesion(restricciones = listOf("FICTICIO-vegana"))

        val aptitud = e.app.catalogo.cartaParaSesion(sesion).productos.associate { it.producto.id to it.aptitud }

        assertEquals("consultar", aptitud[e.producto("FICTICIO-B02")])
        assertEquals("apto", aptitud[e.producto("FICTICIO-P04")])
    }

    @Test
    fun `cocina revisa y revalida un producto modificado`() {
        val ensalada = e.producto("FICTICIO-P04")
        e.db.ejecutar("UPDATE producto_textos SET descripcion = 'Nueva descripción ficticia' WHERE producto_id = ? AND idioma = 'es'", ensalada)
        val detalle = e.app.backoffice.detalle(e.cocina, ensalada)
        assertFalse(detalle.producto.validado)
        assertEquals(6, detalle.evaluaciones.size)

        assertFailsWith<Prohibido> { e.app.backoffice.validar(e.sala, ensalada) }
        assertFailsWith<Prohibido> { e.app.backoffice.validar(e.barra, ensalada) }
        val validado = e.app.backoffice.validar(e.cocina, ensalada)
        assertTrue(validado.producto.validado)
        assertEquals("cocina-prueba", validado.validadoPor)
    }

    @Test
    fun `un puesto desactivado no abre sesiones y vuelve al pedido tradicional`() {
        val puesto = e.app.puestos.crearPuesto(e.admin, "B7", "barra")
        assertEquals(2, puesto.puesto.accesos.size)
        assertTrue(puesto.enlaceNfc!!.startsWith("http://localhost:5173/m/"))
        val token = puesto.puesto.accesos.first { it.canal == "qr" }.token
        e.app.sesiones.iniciar(token, "en")

        e.app.puestos.cambiarActivoPuesto(e.admin, puesto.puesto.id, false)

        val error = assertFailsWith<Prohibido> { e.app.sesiones.iniciar(token, "es") }
        assertEquals("puesto_inactivo", error.codigo)
        assertFailsWith<NoEncontrado> { e.app.sesiones.iniciar("token-inexistente", "es") }
        assertFailsWith<Prohibido> { e.app.puestos.crearPuesto(e.cocina, "B8", "barra") }
        assertFailsWith<Conflicto> { e.app.puestos.crearPuesto(e.admin, "B7", "barra") }
    }

    @Test
    fun `cerrar la sesion descarta el perfil y bloquea nuevas operaciones`() {
        val sesion = e.sesion(restricciones = listOf("FICTICIO-alergia-mani"), preferencias = listOf("FICTICIO-picante"))

        e.app.sesiones.cerrar(sesion)

        val perfil = e.db.conn.consultar(
            "SELECT (SELECT count(*) FROM sesion_restricciones WHERE sesion_id = ?) + (SELECT count(*) FROM sesion_preferencias WHERE sesion_id = ?)",
            sesion, sesion,
        ) { it.getInt(1) }.single()
        assertEquals(0, perfil)
        assertFailsWith<SesionNoDisponible> { e.app.pedidos.carrito(sesion) }
    }

    @Test
    fun `las sesiones abandonadas se cierran solas`() {
        val vieja = e.sesion(restricciones = listOf("FICTICIO-alergia-mani"))
        val nueva = e.sesion()
        e.db.ejecutar("UPDATE sesiones SET inicio = '2026-01-01T00:00:00.000Z' WHERE id = ?", vieja)

        assertEquals(1, e.app.sesiones.cerrarVencidas(6))

        assertFailsWith<SesionNoDisponible> { e.app.sesiones.estado(vieja) }
        assertTrue(e.app.sesiones.estado(nueva).abierta)
    }

    @Test
    fun `metricas del piloto`() {
        pedidoConfirmado()
        e.app.piloto.encuesta(e.sesion(), 5)

        val metricas = e.app.piloto.metricas(e.admin)

        assertEquals(1, metricas.pedidosConfirmados)
        assertEquals(4200L + 1800L, metricas.ticketPromedioCentimos)
        assertEquals(5.0, metricas.satisfaccionPromedio)
        assertFailsWith<Prohibido> { e.app.piloto.metricas(e.cocina) }
        assertFailsWith<SolicitudInvalida> { e.app.piloto.encuesta(e.sesion(), 6) }
    }
}
