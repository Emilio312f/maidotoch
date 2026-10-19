package bus

import comun.Entorno
import comun.Servicio
import comun.SolicitudInvalida
import comun.Topologia
import comun.contratos.Evento
import comun.contratos.Ok
import comun.exigirRol
import comun.http.ClienteInterno
import comun.http.arrancar
import comun.http.moduloServicio
import comun.http.personal
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val LATIDO_MS = 15_000L

fun Route.rutasBus(bus: BusEventos) {
    post("/interno/bus/publicar") {
        bus.publicar(call.receive<Evento>())
        call.respond(Ok())
    }

    // Server-Sent Events para las pantallas del back-office; el API Gateway ya autenticó al personal.
    get("/api/backoffice/eventos") {
        val personal = call.personal()
        val canal = call.request.queryParameters["canal"] ?: throw SolicitudInvalida("canal_invalido", "Indica el canal")
        if (canal !in Evento.CANALES) throw SolicitudInvalida("canal_invalido", "Canal no válido: $canal")
        personal.exigirRol(canal)
        val flujo = bus.suscribir(canal)
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val escritura = Mutex()
            suspend fun ByteWriteChannel.enviar(texto: String) = escritura.withLock {
                writeStringUtf8(texto)
                flush()
            }
            enviar(": conectado\n\n")
            coroutineScope {
                // Comentario periódico: mantiene viva la conexión a través de los proxies.
                val latido = launch {
                    while (true) {
                        delay(LATIDO_MS)
                        enviar(": latido\n\n")
                    }
                }
                try {
                    flujo.collect { evento ->
                        enviar("event: ${evento.tipo}\ndata: ${ClienteInterno.JSON.encodeToString(Evento.serializer(), evento)}\n\n")
                    }
                } finally {
                    latido.cancel()
                }
            }
        }
    }
}

fun Application.moduloBus(bus: BusEventos, token: String, version: String) =
    moduloServicio(Servicio.BUS, token, version) { rutasBus(bus) }

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    val version = Entorno.version()
    return arrancar(Servicio.BUS, topologia) { moduloBus(BusEventos(), topologia.token(Servicio.BUS), version) }
}

fun main() {
    servidor().start(wait = true)
}
