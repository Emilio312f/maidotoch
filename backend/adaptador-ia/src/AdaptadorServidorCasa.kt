package ia

import comun.contratos.SolicitudOrdenamiento
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Adaptador del servidor de IA de casa (Qwen3-4B tras un túnel HTTPS): API compatible con OpenAI.
 *
 * Igual que [AdaptadorClaude], recibe solo el DTO mínimo y devuelve ids ordenados. El modelo no
 * decide sobre seguridad alimentaria ni redacta texto para el comensal. El tiempo máximo y el
 * circuit breaker los aplica [OrdenadorProtegido].
 */
class AdaptadorServidorCasa(
    urlBase: String,
    private val claveApi: String?,
    private val modelo: String,
    timeoutMs: Long,
) : OrdenadorExterno {

    private val destino: URI = URI.create(urlBase.trimEnd('/') + "/chat/completions")
    private val cliente: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build()
    private val tiempoMaximo: Duration = Duration.ofMillis(timeoutMs)

    override suspend fun ordenar(solicitud: SolicitudOrdenamiento): List<Long> {
        val cuerpo = cuerpoPeticion(solicitud)
        val peticion = HttpRequest.newBuilder(destino)
            .timeout(tiempoMaximo)
            .header("Content-Type", "application/json")
            .apply { claveApi?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
            .build()

        val respuesta = cliente.sendAsync(peticion, HttpResponse.BodyHandlers.ofString()).await()
        check(respuesta.statusCode() == 200) { "Servidor de IA respondió ${respuesta.statusCode()}" }

        val raiz = Json.parseToJsonElement(respuesta.body()).jsonObject
        val texto = raiz.getValue("choices").jsonArray[0].jsonObject.getValue("message").jsonObject
            .getValue("content").jsonPrimitive.content
        return PayloadIA.leerIds(texto)
    }

    private fun cuerpoPeticion(solicitud: SolicitudOrdenamiento): String = buildJsonObject {
        put("model", modelo)
        put("temperature", 0.2)
        put("max_tokens", 200)
        putJsonArray("messages") {
            add(mensaje("system", PayloadIA.instrucciones(solicitud.maximo)))
            add(mensaje("user", PayloadIA.json(solicitud)))
        }
        putJsonObject("response_format") {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", "ids")
                put("strict", true)
                putJsonObject("schema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("ids") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "integer") }
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("ids")) }
                    put("additionalProperties", false)
                }
            }
        }
    }.toString()
}

private fun mensaje(rol: String, contenido: String): JsonObject =
    buildJsonObject { put("role", rol); put("content", contenido) }
