package integracion

import comun.PersonalAutenticado
import comun.Servicio
import comun.http.CABECERA_PERSONAL
import comun.http.codificarPersonal
import gateway.Enrutamiento
import soporte.Escenario
import soporte.SistemaHttp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seguridad de la arquitectura de servicios (Diseño v1.1 §9): token de servicio, rutas internas
 * fuera del borde, identidad del personal solo desde el Gateway, límite de solicitudes y bus de
 * eventos para las pantallas. Datos FICTICIOS.
 */
class GatewayTest {
    private val e = Escenario()

    @AfterTest
    fun cerrar() = e.close()

    @Test
    fun `ningun servicio atiende sin el token emitido para el`() {
        SistemaHttp(e.db).use { sistema ->
            val sesion = e.sesion(restricciones = listOf("FICTICIO-alergia-mani"))
            val cuerpo = """{"sesionId":"$sesion"}"""

            val sinToken = sistema.directo(Servicio.SEGURIDAD, "POST", "/interno/seguridad/candidatos", cuerpo)
            assertEquals(401, sinToken.statusCode())
            assertTrue("servicio_no_autorizado" in sinToken.body())

            val tokenAjeno = sistema.directo(Servicio.SEGURIDAD, "POST", "/interno/seguridad/candidatos", cuerpo, sistema.conToken(Servicio.CATALOGO))
            assertEquals(401, tokenAjeno.statusCode())

            val conToken = sistema.directo(Servicio.SEGURIDAD, "POST", "/interno/seguridad/candidatos", cuerpo, sistema.conToken(Servicio.SEGURIDAD))
            assertEquals(200, conToken.statusCode(), conToken.body())

            // Tampoco /salud ni las rutas públicas responden a quien no pasa por el gateway.
            assertEquals(401, sistema.directo(Servicio.SESION, "GET", "/salud").statusCode())
            assertEquals(401, sistema.directo(Servicio.CATALOGO, "GET", "/api/opciones-perfil").statusCode())
        }
    }

    @Test
    fun `el gateway no expone las rutas internas`() {
        SistemaHttp(e.db).use { sistema ->
            val sesion = e.sesion()
            assertEquals(404, sistema.pedir("POST", "/interno/seguridad/candidatos", """{"sesionId":"$sesion"}""").statusCode())
            assertEquals(404, sistema.pedir("GET", "/api/interno/catalogo/disponibles?idioma=es").statusCode())
        }
        assertNull(Enrutamiento.destino("/interno/seguridad/candidatos"))
        assertNull(Enrutamiento.destino("/api/sesionesx"))
        assertEquals(Servicio.RECOMENDACION, Enrutamiento.destino("/api/sesion/asistente"))
        assertEquals(Servicio.SESION, Enrutamiento.destino("/api/sesion/perfil"))
    }

    @Test
    fun `una identidad de personal enviada desde fuera se ignora`() {
        SistemaHttp(e.db).use { sistema ->
            val falsa = codificarPersonal(PersonalAutenticado(e.admin.id, "admin-prueba", "administrador"))

            val respuesta = sistema.pedir(
                "GET", "/api/backoffice/metricas",
                cabeceras = mapOf(CABECERA_PERSONAL to falsa, "X-Servicio-Token" to "cualquiera"),
            )

            assertEquals(401, respuesta.statusCode())
        }
    }

    @Test
    fun `el gateway limita las solicitudes por sesion`() {
        SistemaHttp(e.db, mapOf("MAIDOTOUCH_LIMITE_SESION_MINUTO" to "3")).use { sistema ->
            val sesion = e.sesion()
            val estados = (1..4).map { sistema.pedir("GET", "/api/sesion", cabeceras = mapOf("X-Sesion" to sesion)).statusCode() }

            assertEquals(listOf(200, 200, 200, 429), estados)
            // Otra sesión no se ve afectada.
            assertEquals(200, sistema.pedir("GET", "/api/sesion", cabeceras = mapOf("X-Sesion" to e.sesion())).statusCode())
        }
    }

    @Test
    fun `la pantalla de cocina recibe la comanda por el bus de eventos`() {
        SistemaHttp(e.db).use { sistema ->
            val token = e.app.personal.iniciarSesion("cocina-prueba", Escenario.CLAVE).token
            val solicitud = HttpRequest.newBuilder(URI.create(sistema.url(Servicio.GATEWAY) + "/api/backoffice/eventos?canal=cocina"))
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val flujo = sistema.http.send(solicitud, HttpResponse.BodyHandlers.ofInputStream())
            assertEquals(200, flujo.statusCode())
            val lector = BufferedReader(InputStreamReader(flujo.body()))
            assertEquals(": conectado", lector.readLine())

            val evento = CompletableFuture.supplyAsync {
                generateSequence { lector.readLine() }.first { it.startsWith("event:") }
            }
            // Un pedido confirmado por la API pública, con un plato de cocina.
            val sesion = e.sesion()
            val plato = e.producto("FICTICIO-P01")
            sistema.pedir("POST", "/api/sesion/pedido/items", """{"productoId":$plato,"cantidad":1}""", mapOf("X-Sesion" to sesion))
            assertEquals(200, sistema.pedir("POST", "/api/sesion/pedido/confirmar", """{"confirmo":true}""", mapOf("X-Sesion" to sesion)).statusCode())

            assertEquals("event: comanda_nueva", evento.get(5, TimeUnit.SECONDS))
            flujo.body().close()
        }
    }

    @Test
    fun `la barra no puede escuchar el canal de cocina`() {
        SistemaHttp(e.db).use { sistema ->
            val token = e.app.personal.iniciarSesion("barra-prueba", Escenario.CLAVE).token
            val solicitud = HttpRequest.newBuilder(URI.create(sistema.url(Servicio.GATEWAY) + "/api/backoffice/eventos?canal=cocina"))
                .header("Authorization", "Bearer $token")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()

            assertEquals(403, sistema.http.send(solicitud, HttpResponse.BodyHandlers.ofString()).statusCode())
        }
    }
}
