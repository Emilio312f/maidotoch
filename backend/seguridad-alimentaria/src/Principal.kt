package seguridad

import comun.Conflicto
import comun.Entorno
import comun.Servicio
import comun.Topologia
import comun.contratos.AlergenosCarta
import comun.contratos.ApiSeguridad
import comun.contratos.CandidatosPeticion
import comun.contratos.HttpApiSesion
import comun.contratos.IdsPeticion
import comun.contratos.ImportarSeguridadPeticion
import comun.contratos.ItemsPeticion
import comun.contratos.Ok
import comun.contratos.OpcionesRestriccion
import comun.contratos.PediblePeticion
import comun.datos.ArchivoSqlite
import comun.http.ClienteInterno
import comun.http.arrancar
import comun.http.id
import comun.http.io
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
import seguridad.aplicacion.ServicioSeguridad

/** Rutas internas del Servicio de Seguridad Alimentaria. No tiene rutas públicas: nadie fuera de MaidoTouch lo llama. */
fun Route.rutasSeguridad(seguridad: ApiSeguridad) {
    route("/interno/seguridad") {
        post("/candidatos") {
            val p = call.receive<CandidatosPeticion>()
            call.respond(io { seguridad.candidatos(p.sesionId, p.productos) })
        }
        post("/pedible") {
            val p = call.receive<PediblePeticion>()
            val producto = io { seguridad.pedible(p.sesionId, p.productoId) }
                ?: throw Conflicto("producto_no_pedible", "El producto no está disponible o no es compatible con el perfil")
            call.respond(producto)
        }
        post("/items-no-confirmables") {
            val p = call.receive<ItemsPeticion>()
            call.respond(IdsPeticion(io { seguridad.itemsNoConfirmables(p.sesionId, p.items) }))
        }
        get("/restricciones") {
            call.respond(OpcionesRestriccion(io { seguridad.restricciones(call.request.queryParameters["idioma"] ?: "es") }))
        }
        get("/alergenos") {
            call.respond(AlergenosCarta(io { seguridad.alergenos(call.request.queryParameters["idioma"] ?: "es") }))
        }
        get("/productos/{id}") { call.respond(io { seguridad.fichaSeguridad(call.id()) }) }
        post("/importar") {
            val p = call.receive<ImportarSeguridadPeticion>()
            io { seguridad.importar(p.archivo, p.evaluador, p.productos) }
            call.respond(Ok())
        }
    }
}

fun Application.moduloSeguridad(seguridad: ApiSeguridad, token: String, version: String) =
    moduloServicio(Servicio.SEGURIDAD, token, version) { rutasSeguridad(seguridad) }

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    val http = ClienteInterno.http(topologia.tls)
    val seguridad = ServicioSeguridad(
        ArchivoSqlite(Entorno.rutaBaseDatos(entorno)),
        HttpApiSesion(ClienteInterno.para(Servicio.SESION, topologia, http)),
    )
    val version = Entorno.version()
    return arrancar(Servicio.SEGURIDAD, topologia) { moduloSeguridad(seguridad, topologia.token(Servicio.SEGURIDAD), version) }
}

fun main() {
    servidor().start(wait = true)
}
