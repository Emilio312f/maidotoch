package dominio.recomendacion

import com.sun.net.httpserver.HttpServer
import comun.contratos.CandidatoParaIA
import comun.contratos.SolicitudOrdenamiento
import ia.AdaptadorServidorCasa
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Adaptador compatible con OpenAI (servidor de casa y Gemini) contra un servidor local ficticio. */
class AdaptadorServidorCasaTest {

    private val cuerpos = mutableListOf<String>()
    private val servidor = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/chat/completions") { intercambio ->
            cuerpos += intercambio.requestBody.readBytes().decodeToString()
            val respuesta = """{"choices":[{"message":{"content":"{\"ids\":[2,1]}"}}]}""".toByteArray()
            intercambio.sendResponseHeaders(200, respuesta.size.toLong())
            intercambio.responseBody.use { it.write(respuesta) }
        }
        start()
    }
    private val url = "http://127.0.0.1:${servidor.address.port}/v1"

    private val solicitud = SolicitudOrdenamiento(
        consulta = "algo ligero",
        maximo = 3,
        candidatos = listOf(
            CandidatoParaIA(1, "Plato ficticio uno", "Descripción ficticia", listOf("Ligero")),
            CandidatoParaIA(2, "Plato ficticio dos", "Descripción ficticia", listOf("Picante")),
        ),
    )

    @AfterTest
    fun detener() = servidor.stop(0)

    @Test
    fun `envia el nivel de razonamiento configurado para reducir la latencia`() = runBlocking {
        val ids = AdaptadorServidorCasa(url, "clave-ficticia", "modelo-ficticio", 5_000, razonamiento = "none").ordenar(solicitud)

        assertEquals(listOf(2L, 1L), ids)
        val cuerpo = Json.parseToJsonElement(cuerpos.single()).jsonObject
        assertEquals("none", cuerpo.getValue("reasoning_effort").jsonPrimitive.content)
    }

    @Test
    fun `sin configuracion no envia el parametro de razonamiento`() = runBlocking {
        AdaptadorServidorCasa(url, null, "modelo-ficticio", 5_000).ordenar(solicitud)

        assertFalse("reasoning_effort" in Json.parseToJsonElement(cuerpos.single()).jsonObject)
    }
}
