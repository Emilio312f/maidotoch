package integracion

import comun.Conflicto
import comun.NoAutenticado
import comun.Prohibido
import comun.SolicitudInvalida
import kotlinx.coroutines.runBlocking
import piloto.ResultadoChequeo
import soporte.Escenario
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Cuentas del personal, comandas atrasadas, mediciones, reporte diario y Go/No-Go. Datos FICTICIOS. */
class PilotoTest {
    private val e = Escenario()

    @AfterTest
    fun cerrar() = e.close()

    @Test
    fun `administracion crea, desactiva y restablece cuentas desde el back-office`() {
        val cuenta = e.app.personal.crearDesdeBackoffice(e.admin, "sala-02", "sala", Escenario.CLAVE)
        assertTrue(cuenta.activo && cuenta.tieneClave)
        val token = e.app.personal.iniciarSesion("sala-02", Escenario.CLAVE).token

        e.app.personal.cambiarActivo(e.admin, cuenta.id, false)
        assertFailsWith<NoAutenticado> { e.app.personal.autenticar(token) }
        assertFailsWith<NoAutenticado> { e.app.personal.iniciarSesion("sala-02", Escenario.CLAVE) }

        e.app.personal.cambiarActivo(e.admin, cuenta.id, true)
        val otroToken = e.app.personal.iniciarSesion("sala-02", Escenario.CLAVE).token
        e.app.personal.restablecerClave(e.admin, cuenta.id, "otra-clave-ficticia")
        assertFailsWith<NoAutenticado> { e.app.personal.autenticar(otroToken) }
        e.app.personal.iniciarSesion("sala-02", "otra-clave-ficticia")

        assertFailsWith<Conflicto> { e.app.personal.cambiarActivo(e.admin, e.admin.id, false) }
        assertFailsWith<Prohibido> { e.app.personal.listar(e.cocina) }
        assertFailsWith<Prohibido> { e.app.personal.crearDesdeBackoffice(e.cocina, "sala-03", "sala", Escenario.CLAVE) }
    }

    @Test
    fun `sala ve las comandas que ninguna pantalla recibio`() {
        val sesion = e.sesion()
        e.app.pedidos.agregar(sesion, e.producto("FICTICIO-P04"), 1, null)
        e.app.pedidos.confirmar(sesion, true)
        Thread.sleep(5)

        assertTrue(e.app.comandas.atrasadas(e.sala, minutos = 2).isEmpty(), "Recién creada no está atrasada")
        val atrasada = e.app.comandas.atrasadas(e.sala, minutos = 0).single()
        assertEquals("T1", atrasada.puesto)
        assertEquals(listOf("Ensalada de quinua de prueba [FICTICIO]"), atrasada.items.map { it.nombre })
        assertFailsWith<Prohibido> { e.app.comandas.atrasadas(e.cocina) }

        e.app.comandas.avanzar(e.cocina, atrasada.id, "recibida")
        assertTrue(e.app.comandas.atrasadas(e.sala, minutos = 0).isEmpty())
    }

    @Test
    fun `mediciones de linea base y piloto se comparan por comensal`() {
        e.app.piloto.registrarMedicion(e.admin, "linea_base", "2026-10-01", 10, 100_000, 600, 8, "Observación en sala")
        e.app.piloto.registrarMedicion(e.admin, "linea_base", "2026-10-02", 30, 300_000, 400, 12, "Observación en sala")
        val datos = e.app.piloto.registrarMedicion(e.admin, "piloto", "2026-11-12", 20, 240_000, 300, 4, "Cierre de caja")

        val base = datos.resumen.first { it.fase == "linea_base" }
        assertEquals(40, base.comensales)
        assertEquals(10_000, base.ticketPorComensalCentimos)
        assertEquals(450, base.tiempoPedidoPromedioSegundos)
        assertEquals(0.5, base.consultasMozoPorComensal)
        assertEquals(12_000, datos.resumen.first { it.fase == "piloto" }.ticketPorComensalCentimos)
        assertEquals(3, datos.mediciones.size)

        assertFailsWith<SolicitudInvalida> { e.app.piloto.registrarMedicion(e.admin, "otra", "2026-10-01", 1, 1, 1, 1, "x") }
        assertFailsWith<SolicitudInvalida> { e.app.piloto.registrarMedicion(e.admin, "piloto", "12/11/2026", 1, 1, 1, 1, "x") }
        assertFailsWith<SolicitudInvalida> { e.app.piloto.registrarMedicion(e.admin, "piloto", "2026-11-12", 0, 1, 1, 1, "x") }
        assertFailsWith<Prohibido> { e.app.piloto.mediciones(e.cocina) }
    }

    @Test
    fun `el reporte diario resume la actividad del dia en Lima`() = runBlocking<Unit> {
        val sesion = e.sesion(preferencias = listOf("FICTICIO-ligero"))
        val recomendado = e.app.orquestador.recomendar(sesion, "algo ligero").recomendaciones.first()
        e.app.pedidos.agregar(sesion, recomendado.productoId, 1, recomendado.recomendacionId)
        e.app.pedidos.confirmar(sesion, true)
        e.app.atencion.llamar(sesion, "asistencia", null)

        val hoy = LocalDate.now(ZoneId.of("America/Lima")).toString()
        val reporte = e.app.piloto.reporteDiario(e.admin, hoy)

        assertEquals(1, reporte.sesiones)
        assertEquals(mapOf("fallback" to 1), reporte.consultasPorMotor)
        assertEquals(1, reporte.aceptadas)
        assertEquals(1, reporte.pedidosConfirmados)
        assertEquals(mapOf("asistencia" to 1), reporte.llamadosPorMotivo)
        assertTrue(reporte.cambiosDisponibilidad >= 8)
        assertTrue("Pedidos enviados: 1" in reporte.aTexto())
        assertEquals(0, e.app.piloto.reporteDiario(e.admin, "2026-01-01").sesiones)
    }

    @Test
    fun `la verificacion de Go-No-Go detecta datos ficticios, URL sin HTTPS y evaluaciones faltantes`() {
        // El soporte de pruebas crea un puesto con NFC pero sin QR de respaldo: debe detectarse.
        val sinQr = e.app.piloto.verificar().first { it.criterio == "Puestos con NFC y QR" }
        assertEquals(ResultadoChequeo.FALLA, sinQr.resultado)
        assertTrue("FICTICIO-mesa-1" in sinQr.detalle)
        e.db.ejecutar("UPDATE puntos_servicio SET activo = 0 WHERE codigo = 'FICTICIO-mesa-1'")

        val chequeos = e.app.piloto.verificar().associateBy { it.criterio }
        fun resultado(criterio: String, esperado: ResultadoChequeo) =
            chequeos.getValue(criterio).let { assertEquals(esperado, it.resultado, "$criterio: ${it.detalle}") }

        resultado("Catálogo piloto 100 % validado", ResultadoChequeo.OK)
        resultado("Cuentas del personal por rol", ResultadoChequeo.OK)
        resultado("Puestos con NFC y QR", ResultadoChequeo.OK)
        resultado("Sin datos FICTICIOS ni cuentas demo activas", ResultadoChequeo.FALLA)
        resultado("URL pública con HTTPS", ResultadoChequeo.FALLA)
        val evaluaciones = chequeos.getValue("Restricciones evaluadas en todos los productos")
        assertEquals(ResultadoChequeo.AVISO, evaluaciones.resultado)
        assertTrue("FICTICIO-B02 / FICTICIO-vegana" in evaluaciones.detalle, evaluaciones.detalle)
        assertEquals(ResultadoChequeo.AVISO, chequeos.getValue("Línea base medida").resultado)
    }

    @Test
    fun `un producto sin validar es un NO-GO`() {
        e.db.ejecutar("UPDATE productos SET precio_centimos = precio_centimos + 100 WHERE codigo = 'FICTICIO-P01'")

        val catalogo = e.app.piloto.verificar().first { it.criterio == "Catálogo piloto 100 % validado" }

        assertEquals(ResultadoChequeo.FALLA, catalogo.resultado)
        assertTrue("FICTICIO-P01" in catalogo.detalle)
    }
}
