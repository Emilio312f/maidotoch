package ia

import comun.Entorno
import comun.Servicio
import comun.Topologia
import comun.contratos.ApiIA
import comun.contratos.RespuestaOrdenamiento
import comun.contratos.SolicitudOrdenamiento
import comun.http.arrancar
import comun.http.moduloServicio
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.rutasIa(ia: ApiIA) {
    route("/interno/ia") {
        post("/ordenar") {
            val solicitud = call.receive<SolicitudOrdenamiento>()
            call.respond(RespuestaOrdenamiento(ia.ordenar(solicitud)))
        }
        get("/estado") { call.respond(ia.estado()) }
    }
}

fun Application.moduloIa(ia: ApiIA, token: String, version: String) =
    moduloServicio(Servicio.ADAPTADOR_IA, token, version) { rutasIa(ia) }

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    // Las claves de los proveedores solo están en el entorno de este servicio (/etc/maidotouch/adaptador-ia.env).
    val timeoutMs = Entorno.timeoutIaMs(entorno)
    val proveedor = Entorno.texto("MAIDOTOUCH_IA_PROVEEDOR", entorno) ?: "claude"
    val (ordenador, modelo) = when (proveedor) {
        "claude" -> {
            val clave = Entorno.texto("ANTHROPIC_API_KEY", entorno)
            val modelo = Entorno.texto("MAIDOTOUCH_IA_MODELO", entorno) ?: "claude-opus-5-5"
            if (clave == null) println("ANTHROPIC_API_KEY no definida: el asistente usará solo el ranking por reglas.")
            clave?.let { AdaptadorClaude(it, modelo, timeoutMs) } to modelo
        }
        "servidor-casa" -> {
            val url = Entorno.texto("MAIDOTOUCH_IA_SERVIDOR_URL", entorno)
            val modelo = Entorno.texto("MAIDOTOUCH_IA_SERVIDOR_MODELO", entorno) ?: "qwen3-4b"
            if (url == null) println("MAIDOTOUCH_IA_SERVIDOR_URL no definida: el asistente usará solo el ranking por reglas.")
            url?.let {
                AdaptadorServidorCasa(it, Entorno.texto("MAIDOTOUCH_IA_SERVIDOR_CLAVE", entorno), modelo, timeoutMs)
            } to modelo
        }
        else -> error("MAIDOTOUCH_IA_PROVEEDOR desconocido: '$proveedor' (use 'claude' o 'servidor-casa')")
    }
    val ia = ServicioAdaptadorIA(ordenador, timeoutMs, modelo)
    val version = Entorno.version()
    return arrancar(Servicio.ADAPTADOR_IA, topologia) { moduloIa(ia, topologia.token(Servicio.ADAPTADOR_IA), version) }
}

fun main() {
    servidor().start(wait = true)
}
