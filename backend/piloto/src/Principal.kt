package piloto

import comun.Entorno
import comun.Servicio
import comun.Topologia
import comun.contratos.EncuestaPeticion
import comun.contratos.HttpApiIA
import comun.contratos.HttpApiSesion
import comun.contratos.MedicionPeticion
import comun.contratos.Ok
import comun.contratos.TextoPlano
import comun.datos.ArchivoSqlite
import comun.exigirRol
import comun.http.ClienteInterno
import comun.http.arrancar
import comun.http.io
import comun.http.moduloServicio
import comun.http.personal
import comun.http.sesion
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class Verificacion(val chequeos: List<Chequeo>, val manuales: List<String>, val apto: Boolean)

private fun hoyEnLima() = LocalDate.now(ZoneId.of("America/Lima")).toString()

fun Route.rutasPiloto(piloto: ServicioPiloto) {
    post("/api/sesion/encuesta") {
        val p = call.receive<EncuestaPeticion>()
        io { piloto.encuesta(call.sesion(), p.satisfaccion) }
        call.respond(Ok())
    }
    route("/api/backoffice") {
        get("/metricas") { call.respond(io { piloto.metricas(call.personal()) }) }
        route("/mediciones") {
            get { call.respond(io { piloto.mediciones(call.personal()) }) }
            post {
                val admin = call.personal()
                val p = call.receive<MedicionPeticion>()
                call.respond(
                    HttpStatusCode.Created,
                    io { piloto.registrarMedicion(admin, p.fase, p.fecha, p.comensales, p.importeCentimos, p.tiempoPedidoSegundos, p.consultasMozo, p.fuente) },
                )
            }
        }
        get("/reporte-diario") {
            val admin = call.personal()
            call.respond(io { piloto.reporteDiario(admin, call.request.queryParameters["fecha"] ?: hoyEnLima()) })
        }
        get("/verificacion") {
            call.personal().exigirRol()
            call.respond(verificacion(piloto))
        }
    }
    // Comandos de operación (herramientas): verificar y reporte-diario.
    route("/interno/piloto") {
        get("/verificacion") { call.respond(verificacion(piloto)) }
        get("/reporte-diario") {
            val fecha = call.request.queryParameters["fecha"] ?: hoyEnLima()
            call.respond(TextoPlano(io { piloto.reporteDiario(LocalDate.parse(fecha)) }.aTexto()))
        }
    }
}

private suspend fun verificacion(piloto: ServicioPiloto): Verificacion {
    val chequeos = io { piloto.verificar() }
    return Verificacion(chequeos, ServicioPiloto.VERIFICACIONES_MANUALES, chequeos.none { it.resultado == ResultadoChequeo.FALLA })
}

fun Application.moduloPiloto(piloto: ServicioPiloto, token: String, version: String) =
    moduloServicio(Servicio.PILOTO, token, version) { rutasPiloto(piloto) }

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    val http = ClienteInterno.http(topologia.tls)
    val piloto = ServicioPiloto(
        ArchivoSqlite(Entorno.rutaBaseDatos(entorno)),
        HttpApiSesion(ClienteInterno.para(Servicio.SESION, topologia, http)),
        HttpApiIA(ClienteInterno.para(Servicio.ADAPTADOR_IA, topologia, http), Entorno.timeoutIaMs(entorno)),
        (Entorno.texto("MAIDOTOUCH_URL_PUBLICA", entorno) ?: "http://localhost:5173").trimEnd('/'),
    )
    val version = Entorno.version()
    return arrancar(Servicio.PILOTO, topologia) { moduloPiloto(piloto, topologia.token(Servicio.PILOTO), version) }
}

fun main() {
    servidor().start(wait = true)
}
