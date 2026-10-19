package atencion

import comun.Entorno
import comun.Servicio
import comun.Topologia
import comun.contratos.HttpApiBus
import comun.contratos.HttpApiCatalogo
import comun.contratos.HttpApiSesion
import comun.contratos.LlamadoPeticion
import comun.contratos.PublicadorEventos
import comun.datos.ArchivoSqlite
import comun.http.ClienteInterno
import comun.http.arrancar
import comun.http.id
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

fun Route.rutasAtencion(atencion: ServicioAtencion) {
    get("/api/sesion/llamados") { call.respond(io { atencion.llamadosDeSesion(call.sesion()) }) }
    post("/api/sesion/llamados") {
        val p = call.receive<LlamadoPeticion>()
        call.respond(HttpStatusCode.Created, io { atencion.llamar(call.sesion(), p.motivo, p.productoId) })
    }
    get("/api/backoffice/llamados") { call.respond(io { atencion.pendientes(call.personal()) }) }
    post("/api/backoffice/llamados/{id}/atender") { call.respond(io { atencion.atender(call.personal(), call.id()) }) }
}

fun Application.moduloAtencion(atencion: ServicioAtencion, token: String, version: String) =
    moduloServicio(Servicio.ATENCION, token, version) { rutasAtencion(atencion) }

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    val http = ClienteInterno.http(topologia.tls)
    fun cliente(s: Servicio) = ClienteInterno.para(s, topologia, http)
    val atencion = ServicioAtencion(
        ArchivoSqlite(Entorno.rutaBaseDatos(entorno)),
        HttpApiSesion(cliente(Servicio.SESION)),
        HttpApiCatalogo(cliente(Servicio.CATALOGO)),
        PublicadorEventos(HttpApiBus(cliente(Servicio.BUS))),
    )
    val version = Entorno.version()
    return arrancar(Servicio.ATENCION, topologia) { moduloAtencion(atencion, topologia.token(Servicio.ATENCION), version) }
}

fun main() {
    servidor().start(wait = true)
}
