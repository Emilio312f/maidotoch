package soporte

import comun.Servicio
import comun.Tls
import comun.http.ClienteInterno
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import java.net.BindException
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
class SistemaHttp(
    private val db: BaseDePrueba,
    private val extra: Map<String, String> = emptyMap(),
    private val tls: Tls? = null,
) : AutoCloseable {
    private var puertos: Map<Servicio, Int> = emptyMap()
    val tokens: Map<Servicio, String> = Servicio.entries.associateWith { aleatorio() }
    private val fotos = Files.createTempDirectory("maidotouch-fotos").toString()

    var entorno: Map<String, String> = emptyMap()
        private set

    private val servidores: List<EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>> = arrancar()

    private fun construirEntorno(): Map<String, String> = buildMap {
        put("MAIDOTOUCH_DB_PATH", db.ruta)
        put("MAIDOTOUCH_FOTOS_DIR", fotos)
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

    /**
     * Los puertos se eligen antes de levantar los servidores (cada servicio necesita la dirección de
     * los demás), así que otro proceso puede ocupar uno en el intervalo. En ese caso se detiene lo
     * levantado y se vuelve a intentar con puertos nuevos.
     */
    private fun arrancar(): List<EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>> {
        for (intento in 1..INTENTOS) {
            puertos = reservarPuertos()
            entorno = construirEntorno()
            val iniciados = mutableListOf<EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>>()
            try {
                fabricas.forEach { fabrica -> iniciados += fabrica(entorno).also { it.start(wait = false) } }
                return iniciados
            } catch (e: Exception) {
                iniciados.forEach { it.stop(0, 200) }
                if (!puertoOcupado(e) || intento == INTENTOS) throw e
            }
        }
        error("inalcanzable")
    }

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
        const val INTENTOS = 5

        val fabricas: List<(Map<String, String>) -> EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>> = listOf(
            { e -> sesion.servidor(e) }, { e -> catalogo.servidor(e) }, { e -> seguridad.servidor(e) },
            { e -> recomendacion.servidor(e) }, { e -> ia.servidor(e) }, { e -> pedido.servidor(e) },
            { e -> atencion.servidor(e) }, { e -> trazabilidad.servidor(e) }, { e -> piloto.servidor(e) },
            { e -> bus.servidor(e) }, { e -> gateway.servidor(e) },
        )

        /** Once puertos distintos: se abren todos a la vez y se liberan juntos justo antes de arrancar. */
        fun reservarPuertos(): Map<Servicio, Int> {
            val sockets = Servicio.entries.associateWith { ServerSocket(0) }
            return sockets.mapValues { it.value.localPort }.also { sockets.values.forEach(ServerSocket::close) }
        }

        fun puertoOcupado(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any { it is BindException || "Address already in use" in (it.message ?: "") }

        fun aleatorio(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
