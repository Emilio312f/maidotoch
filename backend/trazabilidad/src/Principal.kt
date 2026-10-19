package trazabilidad

import comun.Entorno
import comun.Servicio
import comun.Topologia
import comun.contratos.EventoPeticion
import comun.contratos.EventoRecomendacion
import comun.contratos.HttpApiRecomendacion
import comun.contratos.HttpApiSesion
import comun.contratos.Ok
import comun.datos.ArchivoSqlite
import comun.http.ClienteInterno
import comun.http.arrancar
import comun.http.id
import comun.http.io
import comun.http.moduloServicio
import comun.http.sesion
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.rutasTrazabilidad(trazabilidad: ServicioTrazabilidad) {
    post("/api/sesion/recomendaciones/{id}/eventos") {
        val p = call.receive<EventoPeticion>()
        io { trazabilidad.registrar(call.sesion(), call.id(), p.accion) }
        call.respond(Ok())
    }
    post("/interno/trazabilidad/eventos") {
        val p = call.receive<EventoRecomendacion>()
        io { trazabilidad.registrarEvento(p.recomendacionId, p.accion) }
        call.respond(Ok())
    }
}

fun Application.moduloTrazabilidad(trazabilidad: ServicioTrazabilidad, token: String, version: String) =
    moduloServicio(Servicio.TRAZABILIDAD, token, version) { rutasTrazabilidad(trazabilidad) }

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    val http = ClienteInterno.http(topologia.tls)
    val trazabilidad = ServicioTrazabilidad(
        ArchivoSqlite(Entorno.rutaBaseDatos(entorno)),
        HttpApiSesion(ClienteInterno.para(Servicio.SESION, topologia, http)),
        HttpApiRecomendacion(ClienteInterno.para(Servicio.RECOMENDACION, topologia, http)),
    )
    val version = Entorno.version()
    return arrancar(Servicio.TRAZABILIDAD, topologia) { moduloTrazabilidad(trazabilidad, topologia.token(Servicio.TRAZABILIDAD), version) }
}

fun main() {
    servidor().start(wait = true)
}
