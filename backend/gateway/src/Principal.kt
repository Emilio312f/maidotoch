package gateway

import comun.Entorno
import comun.ErrorNegocio
import comun.NoEncontrado
import comun.Servicio
import comun.ServicioNoDisponible
import comun.Topologia
import comun.contratos.ApiSesion
import comun.contratos.ErrorRespuesta
import comun.contratos.HttpApiIA
import comun.contratos.HttpApiSesion
import comun.contratos.SaludServicio
import comun.http.CABECERA_PERSONAL
import comun.http.CABECERA_SESION
import comun.http.ClienteInterno
import comun.http.arrancar
import comun.http.codificarPersonal
import comun.http.io
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val log = LoggerFactory.getLogger("gateway")

@Serializable
data class Salud(
    val estado: String,
    val version: String,
    val ia: Boolean,
    val circuitoIaAbierto: Boolean,
    /** Versión de cada servicio, o «sin respuesta». */
    val servicios: Map<String, String>,
)

data class ConfiguracionGateway(
    val topologia: Topologia,
    val version: String = "desarrollo",
    val webComensal: String? = null,
    val webBackoffice: String? = null,
    val limitePorIpMinuto: Int = 600,
    val limitePorSesionMinuto: Int = 120,
)

private class DemasiadasSolicitudes : ErrorNegocio(429, "demasiadas_solicitudes", "Demasiadas solicitudes; intenta en un momento")

private class CuerpoDemasiadoGrande : ErrorNegocio(413, "cuerpo_demasiado_grande", "La solicitud es demasiado grande")

private const val CUERPO_MAXIMO = 9_000_000L

/**
 * API Gateway (Facade): único punto de entrada HTTPS (detrás de Caddy). Enruta cada solicitud al
 * servicio responsable con su token de servicio, autentica al personal del back-office, aplica el
 * límite de solicitudes por IP y por sesión y entrega las dos web-apps (comensal y back-office).
 */
fun Application.moduloGateway(configuracion: ConfiguracionGateway, http: HttpClient, sesion: ApiSesion) {
    val topologia = configuracion.topologia
    val porIp = LimiteSolicitudes(configuracion.limitePorIpMinuto)
    val porSesion = LimiteSolicitudes(configuracion.limitePorSesionMinuto)

    install(ContentNegotiation) { json(ClienteInterno.JSON) }
    install(StatusPages) {
        exception<ErrorNegocio> { call, e ->
            call.respond(HttpStatusCode.fromValue(e.estadoHttp), ErrorRespuesta(e.codigo, e.message ?: e.codigo, e.detalle))
        }
        exception<Throwable> { call, e ->
            log.error("Error no controlado en {}", call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorRespuesta("error_interno", "Error interno"))
        }
    }

    launch {
        while (true) {
            delay(60_000)
            porIp.limpiar()
            porSesion.limpiar()
        }
    }

    routing {
        get("/api/salud") {
            val salud = salud(configuracion, http)
            call.respond(if (salud.estado == "ok") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, salud)
        }
        route("/api/{...}") { handle { reenviar(call, topologia, http, sesion, porIp, porSesion) } }
        route("/fotos/{...}") { handle { reenviar(call, topologia, http, sesion, porIp, porSesion) } }
        // Las rutas internas entre servicios nunca se atienden en el borde.
        route("/interno/{...}") { handle { throw NoEncontrado("ruta_no_encontrada", "Ruta inexistente") } }

        // Las dos web-apps se despliegan por separado; el back-office se descarga solo en /admin.
        configuracion.webBackoffice?.let(::File)?.takeIf { it.resolve("index.html").exists() }?.let { dir ->
            singlePageApplication {
                applicationRoute = "admin"
                useResources = false
                filesPath = dir.absolutePath
                defaultPage = "index.html"
            }
        }
        configuracion.webComensal?.let(::File)?.takeIf { it.resolve("index.html").exists() }?.let { dir ->
            singlePageApplication {
                useResources = false
                filesPath = dir.absolutePath
                defaultPage = "index.html"
            }
        }
    }
}

/** IP del cliente: detrás de Caddy (conexión desde 127.0.0.1) se toma la primera de X-Forwarded-For. */
private fun ipCliente(call: ApplicationCall): String {
    val remota = call.request.origin.remoteHost
    val reenviada = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
    return if (reenviada != null && (remota == "127.0.0.1" || remota == "::1" || remota == "localhost")) reenviada else remota
}

private suspend fun reenviar(
    call: ApplicationCall,
    topologia: Topologia,
    http: HttpClient,
    sesion: ApiSesion,
    porIp: LimiteSolicitudes,
    porSesion: LimiteSolicitudes,
) {
    val ruta = call.request.path()
    val servicio = Enrutamiento.destino(ruta) ?: throw NoEncontrado("ruta_no_encontrada", "Ruta inexistente")
    if (!porIp.permitir(ipCliente(call))) throw DemasiadasSolicitudes()
    val sesionComensal = call.request.headers[CABECERA_SESION]
    if (sesionComensal != null && !porSesion.permitir(sesionComensal)) throw DemasiadasSolicitudes()

    // Solo se reenvían cabeceras conocidas: nadie desde fuera puede fijar X-Personal ni el token de servicio.
    val cabeceras = mutableMapOf(ClienteInterno.CABECERA_TOKEN to topologia.token(servicio))
    call.request.headers["Content-Type"]?.let { cabeceras["Content-Type"] = it }
    call.request.headers["Accept"]?.let { cabeceras["Accept"] = it }
    sesionComensal?.let { cabeceras[CABECERA_SESION] = it }
    if (Enrutamiento.esBackoffice(ruta)) {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
        if (Enrutamiento.sinAutenticacion(ruta)) {
            if (ruta.endsWith("/logout") && token != null) cabeceras["Authorization"] = "Bearer $token"
        } else {
            val personal = io { sesion.autenticarPersonal(token) }
            cabeceras[CABECERA_PERSONAL] = codificarPersonal(personal)
        }
    }

    val metodo = call.request.httpMethod
    val cuerpo = if (metodo in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
        val largo = call.request.headers["Content-Length"]?.toLongOrNull()
        if (largo != null && largo > CUERPO_MAXIMO) throw CuerpoDemasiadoGrande()
        call.receiveChannel().readRemaining(CUERPO_MAXIMO + 1).readByteArray().also {
            if (it.size > CUERPO_MAXIMO) throw CuerpoDemasiadoGrande()
        }
    } else {
        null
    }

    val flujo = ruta == "/api/backoffice/eventos"
    val constructor = HttpRequest.newBuilder(URI.create(topologia.url(servicio) + call.request.uri))
        .method(metodo.value, cuerpo?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
    cabeceras.forEach { (nombre, valor) -> constructor.header(nombre, valor) }
    if (!flujo) constructor.timeout(Duration.ofMillis(timeout(ruta)))

    try {
        if (flujo) {
            val respuesta = http.sendAsync(constructor.build(), HttpResponse.BodyHandlers.ofInputStream()).await()
            call.response.header("Cache-Control", "no-cache")
            call.response.header("X-Accel-Buffering", "no")
            call.respondBytesWriter(ContentType.Text.EventStream, HttpStatusCode.fromValue(respuesta.statusCode())) {
                respuesta.body().use { entrada ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val leidos = withContext(Dispatchers.IO) { entrada.read(buffer) }
                        if (leidos < 0) break
                        writeFully(buffer, 0, leidos)
                        flush()
                    }
                }
            }
        } else {
            val respuesta = http.sendAsync(constructor.build(), HttpResponse.BodyHandlers.ofByteArray()).await()
            respuesta.headers().firstValue("Cache-Control").ifPresent { call.response.header("Cache-Control", it) }
            val tipo = respuesta.headers().firstValue("Content-Type").map { ContentType.parse(it) }.orElse(ContentType.Application.Json)
            call.respondBytes(respuesta.body(), tipo, HttpStatusCode.fromValue(respuesta.statusCode()))
        }
    } catch (e: IOException) {
        throw ServicioNoDisponible(servicio.clave, e.javaClass.simpleName)
    }
}

/** El asistente incluye hasta 5 s del proveedor de IA; las fotos se procesan en el servidor. */
private fun timeout(ruta: String): Long = when {
    ruta == "/api/sesion/asistente" -> 12_000
    ruta.endsWith("/foto") -> 30_000
    else -> 10_000
}

private suspend fun salud(configuracion: ConfiguracionGateway, http: HttpClient): Salud = coroutineScope {
    val topologia = configuracion.topologia
    val servicios = Servicio.entries.filter { it != Servicio.GATEWAY }.map { s ->
        async(Dispatchers.IO) {
            s.clave to (runCatching { ClienteInterno.para(s, topologia, http, 2_000).get<SaludServicio>("/salud").version }.getOrNull() ?: "sin respuesta")
        }
    }.awaitAll().toMap()
    val ia = withContext(Dispatchers.IO) {
        runCatching { HttpApiIA(ClienteInterno.para(Servicio.ADAPTADOR_IA, topologia, http, 2_000), 2_000).estado() }.getOrNull()
    }
    Salud(
        estado = if (servicios.values.none { it == "sin respuesta" }) "ok" else "degradado",
        version = configuracion.version,
        ia = ia?.configurada ?: false,
        circuitoIaAbierto = ia?.circuitoAbierto ?: false,
        servicios = servicios,
    )
}

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    val http = ClienteInterno.http(topologia.tls)
    val configuracion = ConfiguracionGateway(
        topologia = topologia,
        version = Entorno.version(),
        webComensal = Entorno.texto("MAIDOTOUCH_WEB_COMENSAL_DIR", entorno) ?: "frontend/dist/comensal",
        webBackoffice = Entorno.texto("MAIDOTOUCH_WEB_BACKOFFICE_DIR", entorno) ?: "frontend/dist/backoffice",
        limitePorIpMinuto = Entorno.texto("MAIDOTOUCH_LIMITE_IP_MINUTO", entorno)?.toInt() ?: 600,
        limitePorSesionMinuto = Entorno.texto("MAIDOTOUCH_LIMITE_SESION_MINUTO", entorno)?.toInt() ?: 120,
    )
    val sesion = HttpApiSesion(ClienteInterno.para(Servicio.SESION, topologia, http))
    println("MaidoTouch API Gateway ${configuracion.version}")
    return arrancar(Servicio.GATEWAY, topologia) { moduloGateway(configuracion, http, sesion) }
}

fun main() {
    servidor().start(wait = true)
}
