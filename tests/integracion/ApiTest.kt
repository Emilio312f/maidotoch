package integracion

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import presentacion.moduloMaidoTouch
import soporte.Escenario
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** La API REST de punta a punta: comensal y back-office. Datos FICTICIOS. */
class ApiTest {
    private val e = Escenario()

    @AfterTest
    fun cerrar() = e.close()

    private fun prueba(bloque: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { moduloMaidoTouch(e.app, programarTareas = false) }
        bloque()
    }

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

    private val JsonElement.o get() = jsonObject

    @Test
    fun `flujo completo del comensal hasta la comanda en cocina`() = prueba {
        val sesion = client.post("/api/sesiones") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"${e.tokenMesa}","idioma":"es"}""")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.json().o["sesionId"]!!.jsonPrimitive.content

        val opciones = client.get("/api/opciones-perfil?idioma=en").json().o
        val mani = opciones["restricciones"]!!.jsonArray.first { it.o["codigo"]!!.jsonPrimitive.content == "FICTICIO-alergia-mani" }.o["id"]!!.jsonPrimitive.long
        assertEquals("Peanut allergy", opciones["restricciones"]!!.jsonArray.first { it.o["id"]!!.jsonPrimitive.long == mani }.o["nombre"]!!.jsonPrimitive.content)

        val sinPerfil = client.post("/api/sesion/asistente") {
            header("X-Sesion", sesion); contentType(ContentType.Application.Json); setBody("""{"consulta":"algo ligero"}""")
        }
        assertEquals(HttpStatusCode.Conflict, sinPerfil.status)
        assertEquals("perfil_incompleto", sinPerfil.json().o["error"]!!.jsonPrimitive.content)

        client.put("/api/sesion/perfil") {
            header("X-Sesion", sesion); contentType(ContentType.Application.Json); setBody("""{"restricciones":[$mani],"preferencias":[]}""")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        val carta = client.get("/api/sesion/carta") { header("X-Sesion", sesion) }.json().o
        assertEquals(8, carta["productos"]!!.jsonArray.size)

        val asistente = client.post("/api/sesion/asistente") {
            header("X-Sesion", sesion); contentType(ContentType.Application.Json); setBody("""{"consulta":"algo ligero"}""")
        }.json().o
        assertEquals("fallback", asistente["motor"]!!.jsonPrimitive.content)
        val primera = asistente["recomendaciones"]!!.jsonArray.first().o
        val productoId = primera["productoId"]!!.jsonPrimitive.long
        val recomendacionId = primera["recomendacionId"]!!.jsonPrimitive.long
        assertTrue(primera["explicacion"]!!.jsonPrimitive.content.isNotBlank())

        val carrito = client.post("/api/sesion/pedido/items") {
            header("X-Sesion", sesion); contentType(ContentType.Application.Json)
            setBody("""{"productoId":$productoId,"cantidad":1,"recomendacionId":$recomendacionId}""")
        }.json().o
        val item = carrito["borrador"]!!.o["items"]!!.jsonArray.single().o["id"]!!.jsonPrimitive.long
        client.patch("/api/sesion/pedido/items/$item") {
            header("X-Sesion", sesion); contentType(ContentType.Application.Json); setBody("""{"cantidad":2}""")
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        val sinConfirmar = client.post("/api/sesion/pedido/confirmar") {
            header("X-Sesion", sesion); contentType(ContentType.Application.Json); setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, sinConfirmar.status)

        val pedido = client.post("/api/sesion/pedido/confirmar") {
            header("X-Sesion", sesion); contentType(ContentType.Application.Json); setBody("""{"confirmo":true}""")
        }.json().o
        assertEquals("confirmado", pedido["estado"]!!.jsonPrimitive.content)

        val token = client.post("/api/backoffice/login") {
            contentType(ContentType.Application.Json); setBody("""{"identificador":"cocina-prueba","clave":"${Escenario.CLAVE}"}""")
        }.json().o["token"]!!.jsonPrimitive.content
        val destino = pedido["comandas"]!!.jsonArray.single().o["destino"]!!.jsonPrimitive.content
        val comandas = client.get("/api/backoffice/comandas?destino=$destino") {
            header("Authorization", "Bearer ${e.app.personal.iniciarSesion(if (destino == "barra") "barra-prueba" else "cocina-prueba", Escenario.CLAVE).token}")
        }.json().jsonArray
        assertEquals(1, comandas.size)
        assertEquals("T1", comandas.single().o["puesto"]!!.jsonPrimitive.content)

        val sinPermiso = client.get("/api/backoffice/metricas") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, sinPermiso.status)
    }

    @Test
    fun `errores de acceso`() = prueba {
        val sinSesion = client.get("/api/sesion/carta")
        assertEquals(HttpStatusCode.Unauthorized, sinSesion.status)
        assertEquals("sesion_no_valida", sinSesion.json().o["error"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/backoffice/productos").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/backoffice/login") {
                contentType(ContentType.Application.Json); setBody("""{"identificador":"cocina-prueba","clave":"equivocada-123"}""")
            }.status,
        )

        e.app.backoffice.cambiarActivoPuesto(e.admin, e.mesa.puesto.id, false)
        val inactivo = client.post("/api/sesiones") {
            contentType(ContentType.Application.Json); setBody("""{"token":"${e.tokenMesa}"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, inactivo.status)
        assertEquals("puesto_inactivo", inactivo.json().o["error"]!!.jsonPrimitive.content)

        val malformado = client.post("/api/sesiones") { contentType(ContentType.Application.Json); setBody("{") }
        assertEquals(HttpStatusCode.BadRequest, malformado.status)
    }

    @Test
    fun `salud indica si hay proveedor de IA`() = prueba {
        val salud = client.get("/api/salud").json().o
        assertEquals("ok", salud["estado"]!!.jsonPrimitive.content)
        assertEquals("false", salud["ia"]!!.jsonPrimitive.content)
    }
}
