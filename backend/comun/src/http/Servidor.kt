package comun.http

import comun.ErrorNegocio
import comun.NoAutenticado
import comun.PersonalAutenticado
import comun.Servicio
import comun.SesionNoDisponible
import comun.SolicitudInvalida
import comun.Tls
import comun.Topologia
import comun.contratos.ErrorRespuesta
import comun.contratos.SaludServicio
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64

private val log = LoggerFactory.getLogger("comun.http.Servidor")

const val CABECERA_SESION = "X-Sesion"

/** Identidad del personal que el API Gateway ya autenticó; solo se acepta junto con el token del servicio. */
const val CABECERA_PERSONAL = "X-Personal"

/** Los servicios usan JDBC (bloqueante): se ejecuta fuera del hilo de eventos del servidor. */
suspend fun <T> io(bloque: () -> T): T = withContext(Dispatchers.IO) { bloque() }

/** La sesión del comensal viaja en una cabecera; nunca en la URL, para que no quede en logs ni historial. */
fun ApplicationCall.sesion(): String =
    request.headers[CABECERA_SESION]?.takeIf { it.isNotBlank() } ?: throw SesionNoDisponible()

fun ApplicationCall.personal(): PersonalAutenticado {
    val valor = request.headers[CABECERA_PERSONAL] ?: throw NoAutenticado()
    return runCatching { decodificarPersonal(valor) }.getOrElse { throw NoAutenticado() }
}

fun ApplicationCall.id(nombre: String = "id"): Long =
    parameters[nombre]?.toLongOrNull() ?: throw SolicitudInvalida("id_invalido", "Identificador inválido")

fun codificarPersonal(personal: PersonalAutenticado): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        ClienteInterno.JSON.encodeToString(PersonalAutenticado.serializer(), personal).toByteArray(),
    )

fun decodificarPersonal(valor: String): PersonalAutenticado =
    ClienteInterno.JSON.decodeFromString(PersonalAutenticado.serializer(), String(Base64.getUrlDecoder().decode(valor)))

/** Ninguna llamada se atiende sin el token emitido para este servicio (Diseño v1.1 §9). */
private fun tokenServicio(esperado: String) = createApplicationPlugin("TokenServicio") {
    val esperadoBytes = esperado.toByteArray()
    onCall { call ->
        val recibido = call.request.headers[ClienteInterno.CABECERA_TOKEN]?.toByteArray() ?: ByteArray(0)
        if (!MessageDigest.isEqual(recibido, esperadoBytes)) throw TokenServicioInvalido()
    }
}

private class TokenServicioInvalido : ErrorNegocio(401, "servicio_no_autorizado", "Falta el token de servicio o no corresponde")

/**
 * Configuración común de cada servicio: JSON, errores con código para la web-app, token de servicio
 * y /salud. [rutas] agrega las rutas propias del servicio.
 */
fun Application.moduloServicio(servicio: Servicio, token: String, version: String, rutas: Route.() -> Unit) {
    install(ContentNegotiation) {
        json(ClienteInterno.JSON)
    }
    install(StatusPages) {
        exception<ErrorNegocio> { call, e ->
            if (e.estadoHttp >= 500) log.warn("{}: {}", call.request.local.uri, e.message)
            call.respond(HttpStatusCode.fromValue(e.estadoHttp), ErrorRespuesta(e.codigo, e.message ?: e.codigo, e.detalle))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorRespuesta("solicitud_invalida", "Cuerpo de la solicitud inválido"))
        }
        exception<Throwable> { call, e ->
            log.error("Error no controlado en {}", call.request.local.uri, e)
            call.respond(HttpStatusCode.InternalServerError, ErrorRespuesta("error_interno", "Error interno"))
        }
    }
    install(tokenServicio(token))
    routing {
        get("/salud") { call.respond(SaludServicio(servicio.clave, "ok", version)) }
        rutas()
    }
}

/**
 * Levanta el servicio en la dirección de la [topologia]. Con una URL https:// sirve con el
 * certificado interno (TLS entre servicios); en desarrollo y pruebas, HTTP en 127.0.0.1.
 */
fun arrancar(
    servicio: Servicio,
    topologia: Topologia,
    modulo: Application.() -> Unit,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val uri = URI(topologia.url(servicio))
    val servidor = embeddedServer(Netty, configure = {
        if (uri.scheme == "https") {
            val tls = topologia.tls ?: error("${servicio.clave} usa https:// pero falta MAIDOTOUCH_TLS_ALMACEN")
            val almacen = cargarAlmacen(tls)
            sslConnector(almacen, Tls.ALIAS, { tls.clave.toCharArray() }, { tls.clave.toCharArray() }) {
                host = uri.host
                port = uri.port
            }
        } else {
            connector {
                host = uri.host
                port = uri.port
            }
        }
    }, module = modulo)
    log.info("{} en {}", servicio.clave, uri)
    return servidor
}

private fun cargarAlmacen(tls: Tls): KeyStore = KeyStore.getInstance("PKCS12").apply {
    File(tls.almacen).inputStream().use { load(it, tls.clave.toCharArray()) }
}
