package comun.http

import comun.ErrorRemoto
import comun.Servicio
import comun.ServicioNoDisponible
import comun.Tls
import comun.Topologia
import comun.contratos.ErrorRespuesta
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Cliente HTTP de un servicio hacia otro. Agrega el token emitido para el servicio destino y vuelve a
 * lanzar los errores de negocio con su estado y código originales; una falla de red o de un servicio
 * caído se convierte en [ServicioNoDisponible].
 */
class ClienteInterno(
    val destino: Servicio,
    private val baseUrl: String,
    private val token: String,
    private val http: HttpClient,
    private val timeoutMs: Long = TIMEOUT_POR_DEFECTO_MS,
) {
    fun <T> enviar(
        metodo: String,
        ruta: String,
        cuerpo: String?,
        respuesta: KSerializer<T>,
        cabeceras: Map<String, String> = emptyMap(),
        timeoutMs: Long = this.timeoutMs,
    ): T {
        val solicitud = solicitud(metodo, ruta, cuerpo, cabeceras, timeoutMs)
        val resultado = try {
            http.send(solicitud, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw ServicioNoDisponible(destino.clave, e.javaClass.simpleName)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ServicioNoDisponible(destino.clave, "interrumpido")
        }
        return leer(resultado, respuesta)
    }

    /** Variante cancelable: el llamador puede cortar la espera con withTimeout (proveedor de IA). */
    suspend fun <T> enviarCancelable(
        metodo: String,
        ruta: String,
        cuerpo: String?,
        respuesta: KSerializer<T>,
        timeoutMs: Long = this.timeoutMs,
    ): T {
        val resultado = try {
            http.sendAsync(solicitud(metodo, ruta, cuerpo, emptyMap(), timeoutMs), HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: IOException) {
            throw ServicioNoDisponible(destino.clave, e.javaClass.simpleName)
        }
        return leer(resultado, respuesta)
    }

    inline fun <reified T> get(ruta: String, cabeceras: Map<String, String> = emptyMap()): T =
        enviar("GET", ruta, null, serializer<T>(), cabeceras)

    inline fun <reified C, reified T> post(ruta: String, cuerpo: C, cabeceras: Map<String, String> = emptyMap()): T =
        enviar("POST", ruta, JSON.encodeToString(serializer<C>(), cuerpo), serializer<T>(), cabeceras)

    private fun solicitud(metodo: String, ruta: String, cuerpo: String?, cabeceras: Map<String, String>, timeoutMs: Long): HttpRequest {
        val constructor = HttpRequest.newBuilder(URI.create(baseUrl + ruta))
            .timeout(Duration.ofMillis(timeoutMs))
            .header(CABECERA_TOKEN, token)
            .header("Accept", "application/json")
        cabeceras.forEach { (nombre, valor) -> constructor.header(nombre, valor) }
        if (cuerpo != null) constructor.header("Content-Type", "application/json")
        return constructor.method(
            metodo,
            if (cuerpo != null) HttpRequest.BodyPublishers.ofString(cuerpo) else HttpRequest.BodyPublishers.noBody(),
        ).build()
    }

    private fun <T> leer(resultado: HttpResponse<String>, respuesta: KSerializer<T>): T {
        val estado = resultado.statusCode()
        if (estado in 200..299) {
            return try {
                JSON.decodeFromString(respuesta, resultado.body())
            } catch (e: Exception) {
                throw ServicioNoDisponible(destino.clave, "respuesta ilegible")
            }
        }
        val error = runCatching { JSON.decodeFromString(ErrorRespuesta.serializer(), resultado.body()) }.getOrNull()
        if (error == null || estado >= 500) {
            throw ServicioNoDisponible(destino.clave, "HTTP $estado${error?.let { " ${it.error}" }.orEmpty()}")
        }
        throw ErrorRemoto(estado, error.error, error.mensaje, error.detalle)
    }

    companion object {
        const val CABECERA_TOKEN = "X-Servicio-Token"
        const val TIMEOUT_POR_DEFECTO_MS = 3_000L

        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        /** Un HttpClient por proceso. Con TLS interno, confía solo en el certificado del almacén de MaidoTouch. */
        fun http(tls: Tls?): HttpClient {
            val constructor = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
            if (tls != null) constructor.sslContext(contextoTls(tls))
            return constructor.build()
        }

        fun para(destino: Servicio, topologia: Topologia, http: HttpClient, timeoutMs: Long = TIMEOUT_POR_DEFECTO_MS) =
            ClienteInterno(destino, topologia.url(destino), topologia.token(destino), http, timeoutMs)

        private fun contextoTls(tls: Tls): SSLContext {
            val almacen = KeyStore.getInstance("PKCS12")
            File(tls.almacen).inputStream().use { almacen.load(it, tls.clave.toCharArray()) }
            val confianza = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(almacen) }
            return SSLContext.getInstance("TLS").apply { init(null, confianza.trustManagers, null) }
        }
    }
}
