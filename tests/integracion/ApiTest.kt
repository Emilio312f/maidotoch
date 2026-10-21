package integracion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import soporte.Escenario
import soporte.SistemaHttp
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La API REST de punta a punta, como la usa la web-app: entra por el API Gateway y recorre los
 * servicios por HTTP, cada uno en su propio puerto y con su token. Datos FICTICIOS.
 */
class ApiTest {
    private val e = Escenario()
    private val sistema = SistemaHttp(e.db)

    @AfterTest
    fun cerrar() {
        sistema.close()
        e.close()
    }

    private fun HttpResponse<String>.json(): JsonElement = Json.parseToJsonElement(body())

    private val JsonElement.o get() = jsonObject

    private fun conSesion(sesion: String) = mapOf("X-Sesion" to sesion)

    @Test
    fun `flujo completo del comensal hasta la comanda en cocina`() {
        val creada = sistema.pedir("POST", "/api/sesiones", """{"token":"${e.tokenMesa}","idioma":"es"}""")
        assertEquals(201, creada.statusCode(), creada.body())
        val sesion = creada.json().o["sesionId"]!!.jsonPrimitive.content

        val opciones = sistema.pedir("GET", "/api/opciones-perfil?idioma=en").json().o
        val mani = opciones["restricciones"]!!.jsonArray.first { it.o["codigo"]!!.jsonPrimitive.content == "FICTICIO-alergia-mani" }.o["id"]!!.jsonPrimitive.long
        assertEquals("Peanut allergy", opciones["restricciones"]!!.jsonArray.first { it.o["id"]!!.jsonPrimitive.long == mani }.o["nombre"]!!.jsonPrimitive.content)

        val sinPerfil = sistema.pedir("POST", "/api/sesion/asistente", """{"consulta":"algo ligero"}""", conSesion(sesion))
        assertEquals(409, sinPerfil.statusCode())
        assertEquals("perfil_incompleto", sinPerfil.json().o["error"]!!.jsonPrimitive.content)

        assertEquals(200, sistema.pedir("PUT", "/api/sesion/perfil", """{"restricciones":[$mani],"preferencias":[]}""", conSesion(sesion)).statusCode())

        val carta = sistema.pedir("GET", "/api/sesion/carta", cabeceras = conSesion(sesion)).json().o
        assertEquals(8, carta["productos"]!!.jsonArray.size)

        val asistente = sistema.pedir("POST", "/api/sesion/asistente", """{"consulta":"algo ligero"}""", conSesion(sesion))
        assertEquals(200, asistente.statusCode(), asistente.body())
        // Sin ANTHROPIC_API_KEY el Servicio Adaptador de IA no está configurado: responde el ranking por reglas.
        assertEquals("fallback", asistente.json().o["motor"]!!.jsonPrimitive.content)
        val primera = asistente.json().o["recomendaciones"]!!.jsonArray.first().o
        val productoId = primera["productoId"]!!.jsonPrimitive.long
        val recomendacionId = primera["recomendacionId"]!!.jsonPrimitive.long
        assertTrue(primera["explicacion"]!!.jsonPrimitive.content.isNotBlank())

        val carrito = sistema.pedir(
            "POST", "/api/sesion/pedido/items",
            """{"productoId":$productoId,"cantidad":1,"recomendacionId":$recomendacionId}""", conSesion(sesion),
        ).json().o
        val item = carrito["borrador"]!!.o["items"]!!.jsonArray.single().o["id"]!!.jsonPrimitive.long
        assertEquals(200, sistema.pedir("PATCH", "/api/sesion/pedido/items/$item", """{"cantidad":2}""", conSesion(sesion)).statusCode())

        assertEquals(400, sistema.pedir("POST", "/api/sesion/pedido/confirmar", "{}", conSesion(sesion)).statusCode())

        val pedido = sistema.pedir("POST", "/api/sesion/pedido/confirmar", """{"confirmo":true}""", conSesion(sesion)).json().o
        assertEquals("confirmado", pedido["estado"]!!.jsonPrimitive.content)

        // Trazabilidad entre servicios: mostrada (Recomendación), aceptada y modificada (Pedido).
        val eventos = e.db.conn.prepareStatement("SELECT accion FROM recomendacion_eventos WHERE recomendacion_id = ? ORDER BY id").use { st ->
            st.setLong(1, recomendacionId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        assertEquals(listOf("mostrada", "aceptada", "modificada"), eventos)

        val destino = pedido["comandas"]!!.jsonArray.single().o["destino"]!!.jsonPrimitive.content
        val tokenDestino = login(if (destino == "barra") "barra-prueba" else "cocina-prueba")
        val comandas = sistema.pedir("GET", "/api/backoffice/comandas?destino=$destino", cabeceras = bearer(tokenDestino)).json().jsonArray
        assertEquals(1, comandas.size)
        assertEquals("T1", comandas.single().o["puesto"]!!.jsonPrimitive.content)

        val sinPermiso = sistema.pedir("GET", "/api/backoffice/metricas", cabeceras = bearer(login("cocina-prueba")))
        assertEquals(403, sinPermiso.statusCode())
    }

    private fun login(identificador: String): String =
        sistema.pedir("POST", "/api/backoffice/login", """{"identificador":"$identificador","clave":"${Escenario.CLAVE}"}""")
            .json().o["token"]!!.jsonPrimitive.content

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    @Test
    fun `errores de acceso`() {
        val sinSesion = sistema.pedir("GET", "/api/sesion/carta")
        assertEquals(401, sinSesion.statusCode())
        assertEquals("sesion_no_valida", sinSesion.json().o["error"]!!.jsonPrimitive.content)

        assertEquals(401, sistema.pedir("GET", "/api/backoffice/productos").statusCode())
        assertEquals(
            401,
            sistema.pedir("POST", "/api/backoffice/login", """{"identificador":"cocina-prueba","clave":"equivocada-123"}""").statusCode(),
        )

        e.app.puestos.cambiarActivoPuesto(e.admin, e.mesa.puesto.id, false)
        val inactivo = sistema.pedir("POST", "/api/sesiones", """{"token":"${e.tokenMesa}"}""")
        assertEquals(403, inactivo.statusCode())
        assertEquals("puesto_inactivo", inactivo.json().o["error"]!!.jsonPrimitive.content)

        assertEquals(400, sistema.pedir("POST", "/api/sesiones", "{").statusCode())
    }

    @Test
    fun `salud informa cada servicio y si hay proveedor de IA`() {
        val respuesta = sistema.pedir("GET", "/api/salud")
        assertEquals(200, respuesta.statusCode(), respuesta.body())
        val salud = respuesta.json().o
        assertEquals("ok", salud["estado"]!!.jsonPrimitive.content)
        assertEquals("false", salud["ia"]!!.jsonPrimitive.content)
        assertEquals(10, salud["servicios"]!!.jsonObject.size)
    }

    @Test
    fun `volver a escanear el mismo puesto retoma la sesion del celular`() {
        val sesion = e.sesion(restricciones = listOf("FICTICIO-alergia-mani"))
        val iniciar = { token: String, previa: String? ->
            val cuerpo = if (previa == null) """{"token":"$token"}""" else """{"token":"$token","sesionPrevia":"$previa"}"""
            sistema.pedir("POST", "/api/sesiones", cuerpo).json().o
        }

        // El mismo celular escanea el QR del mismo puesto: misma sesión, con sus restricciones.
        val tokenQr = e.mesa.puesto.accesos.first { it.canal == "qr" }.token
        val retomada = iniciar(tokenQr, sesion)
        assertEquals(sesion, retomada["sesionId"]!!.jsonPrimitive.content)
        assertEquals(1, retomada["estado"]!!.o["restricciones"]!!.jsonArray.size)

        // Otro celular en el mismo puesto: sesión propia y sin perfil.
        val otra = iniciar(e.tokenMesa, null)
        assertTrue(otra["sesionId"]!!.jsonPrimitive.content != sesion)
        assertEquals(0, otra["estado"]!!.o["restricciones"]!!.jsonArray.size)

        // Otro puesto, o una sesión ya cerrada: sesión nueva.
        val tokenOtroPuesto = e.app.puestos.crearPuesto(e.admin, "T2", "mesa").puesto.accesos.first { it.canal == "nfc" }.token
        assertTrue(iniciar(tokenOtroPuesto, sesion)["sesionId"]!!.jsonPrimitive.content != sesion)
        sistema.pedir("POST", "/api/sesion/cerrar", cabeceras = conSesion(sesion))
        assertTrue(iniciar(e.tokenMesa, sesion)["sesionId"]!!.jsonPrimitive.content != sesion)
        assertTrue(iniciar(e.tokenMesa, "no-existe")["sesionId"]!!.jsonPrimitive.content != "no-existe")
    }

    @Test
    fun `cerrar sesion por el gateway descarta el perfil`() {
        val sesion = e.sesion(restricciones = listOf("FICTICIO-alergia-mani"))

        assertEquals(200, sistema.pedir("POST", "/api/sesion/cerrar", cabeceras = conSesion(sesion)).statusCode())
        assertEquals(401, sistema.pedir("GET", "/api/sesion/carta", cabeceras = conSesion(sesion)).statusCode())
    }
}
