package soporte

import comun.Servicio
import comun.Tls
import comun.http.ClienteInterno
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * Los once procesos del Diseño v1.1 levantados de verdad (cada uno en su puerto, con su token y
 * hablando por HTTP), sobre la base de [db]. Las pruebas entran por el API Gateway, como la web-app.
 */
class SistemaHttp(db: BaseDePrueba, extra: Map<String, String> = emptyMap(), private val tls: Tls? = null) : AutoCloseable {
    private val puertos = Servicio.entries.associateWith { libre() }
    val tokens: Map<Servicio, String> = Servicio.entries.associateWith { aleatorio() }

    val entorno: Map<String, String> = buildMap {
        put("MAIDOTOUCH_DB_PATH", db.ruta)
        put("MAIDOTOUCH_FOTOS_DIR", Files.createTempDirectory("maidotouch-fotos").toString())
        put("MAIDOTOUCH_URL_PUBLICA", "http://localhost:5173")
        Servicio.entries.forEach {
            put("MAIDOTOUCH_URL_${it.sufijo}", url(it))
            put("MAIDOTOUCH_TOKEN_${it.sufijo}", tokens.getValue(it))
        }
        if (tls != null) {
            put("MAIDOTOUCH_TLS_ALMACEN", tls.almacen)
            put("MAIDOTOUCH_TLS_CLAVE", tls.clave)
        }
        putAll(extra)
    }

    private val servidores: List<EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>> = listOf(
        sesion.servidor(entorno),
        catalogo.servidor(entorno),
        seguridad.servidor(entorno),
        recomendacion.servidor(entorno),
        ia.servidor(entorno),
        pedido.servidor(entorno),
        atencion.servidor(entorno),
        trazabilidad.servidor(entorno),
        piloto.servidor(entorno),
        bus.servidor(entorno),
        gateway.servidor(entorno),
    ).onEach { it.start(wait = false) }

    val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()

    /** Con [tls], los servicios internos hablan por HTTPS; el gateway sigue en HTTP detrás de Caddy. */
    fun url(servicio: Servicio) =
        "${if (tls != null && servicio != Servicio.GATEWAY) "https" else "http"}://127.0.0.1:${puertos.getValue(servicio)}"

    /** Solicitud a la API pública, a través del API Gateway. */
    fun pedir(metodo: String, ruta: String, cuerpo: String? = null, cabeceras: Map<String, String> = emptyMap()): HttpResponse<String> =
        enviar(url(Servicio.GATEWAY) + ruta, metodo, cuerpo, cabeceras)

    /** Solicitud directa a un servicio, sin pasar por el gateway. */
    fun directo(servicio: Servicio, metodo: String, ruta: String, cuerpo: String? = null, cabeceras: Map<String, String> = emptyMap()): HttpResponse<String> =
        enviar(url(servicio) + ruta, metodo, cuerpo, cabeceras)

    /** Solicitud binaria a la API pública (fotos). */
    fun pedirBytes(metodo: String, ruta: String, cuerpo: ByteArray? = null, cabeceras: Map<String, String> = emptyMap()): HttpResponse<ByteArray> {
        val constructor = HttpRequest.newBuilder(URI.create(url(Servicio.GATEWAY) + ruta)).timeout(Duration.ofSeconds(30))
        cabeceras.forEach { (k, v) -> constructor.header(k, v) }
        constructor.method(metodo, cuerpo?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
        return http.send(constructor.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    fun conToken(servicio: Servicio) = mapOf(ClienteInterno.CABECERA_TOKEN to tokens.getValue(servicio))

    private fun enviar(url: String, metodo: String, cuerpo: String?, cabeceras: Map<String, String>): HttpResponse<String> {
        val constructor = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
        if (cuerpo != null) constructor.header("Content-Type", "application/json")
        cabeceras.forEach { (k, v) -> constructor.header(k, v) }
        constructor.method(metodo, cuerpo?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        return http.send(constructor.build(), HttpResponse.BodyHandlers.ofString())
    }

    override fun close() {
        servidores.forEach { it.stop(50, 500) }
    }

    private companion object {
        fun libre(): Int = ServerSocket(0).use { it.localPort }

        fun aleatorio(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
