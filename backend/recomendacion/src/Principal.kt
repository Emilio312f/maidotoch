package recomendacion

import comun.Entorno
import comun.NoEncontrado
import comun.Servicio
import comun.Topologia
import comun.contratos.ConsultaPeticion
import comun.contratos.HttpApiCatalogo
import comun.contratos.HttpApiIA
import comun.contratos.HttpApiSeguridad
import comun.contratos.HttpApiSesion
import comun.contratos.HttpApiTrazabilidad
import comun.contratos.ProductoRecomendado
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import recomendacion.aplicacion.OrquestadorRecomendacion

fun Route.rutasRecomendacion(orquestador: OrquestadorRecomendacion) {
    post("/api/sesion/asistente") {
        val p = call.receive<ConsultaPeticion>()
        call.respond(orquestador.recomendar(call.sesion(), p.consulta))
    }
    get("/interno/recomendaciones/{id}") {
        val sesionId = call.request.queryParameters["sesion"].orEmpty()
        val producto = io { orquestador.productoDeRecomendacion(call.id(), sesionId) }
            ?: throw NoEncontrado("recomendacion_no_encontrada", "Recomendación inexistente")
        call.respond(ProductoRecomendado(producto))
    }
}

fun Application.moduloRecomendacion(orquestador: OrquestadorRecomendacion, token: String, version: String) =
    moduloServicio(Servicio.RECOMENDACION, token, version) { rutasRecomendacion(orquestador) }

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    val http = ClienteInterno.http(topologia.tls)
    fun cliente(s: Servicio) = ClienteInterno.para(s, topologia, http)
    val timeoutIa = Entorno.timeoutIaMs(entorno)
    val orquestador = OrquestadorRecomendacion(
        fuente = ArchivoSqlite(Entorno.rutaBaseDatos(entorno)),
        sesion = HttpApiSesion(cliente(Servicio.SESION)),
        catalogo = HttpApiCatalogo(cliente(Servicio.CATALOGO)),
        seguridad = HttpApiSeguridad(cliente(Servicio.SEGURIDAD)),
        ia = HttpApiIA(cliente(Servicio.ADAPTADOR_IA), timeoutIa),
        trazabilidad = HttpApiTrazabilidad(cliente(Servicio.TRAZABILIDAD)),
        maximoRecomendaciones = Entorno.texto("MAIDOTOUCH_RECOMENDACIONES_MAX", entorno)?.toInt() ?: 3,
        limiteConsultasIa = Entorno.texto("MAIDOTOUCH_IA_LIMITE_CONSULTAS", entorno)?.toInt() ?: 10,
        timeoutIaMs = timeoutIa,
    )
    val version = Entorno.version()
    return arrancar(Servicio.RECOMENDACION, topologia) { moduloRecomendacion(orquestador, topologia.token(Servicio.RECOMENDACION), version) }
}

fun main() {
    servidor().start(wait = true)
}
