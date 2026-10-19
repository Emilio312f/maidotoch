package pedido

import comun.Entorno
import comun.Servicio
import comun.Topologia
import comun.contratos.AgregarItemPeticion
import comun.contratos.ApiBus
import comun.contratos.ApiRecomendacion
import comun.contratos.ApiSeguridad
import comun.contratos.ApiSesion
import comun.contratos.ApiTrazabilidad
import comun.contratos.CantidadPeticion
import comun.contratos.ConfirmarPeticion
import comun.contratos.EstadoPeticion
import comun.contratos.HttpApiBus
import comun.contratos.HttpApiRecomendacion
import comun.contratos.HttpApiSeguridad
import comun.contratos.HttpApiSesion
import comun.contratos.HttpApiTrazabilidad
import comun.contratos.Ok
import comun.contratos.PublicadorEventos
import comun.datos.ArchivoSqlite
import comun.datos.FuenteConexiones
import comun.http.ClienteInterno
import comun.http.arrancar
import comun.http.id
import comun.http.io
import comun.http.moduloServicio
import comun.http.personal
import comun.http.sesion
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import pedido.aplicacion.ServicioComandas
import pedido.aplicacion.ServicioPedido

/** Composición del Servicio de Pedido. */
class AppPedido(
    fuente: FuenteConexiones,
    sesion: ApiSesion,
    seguridad: ApiSeguridad,
    recomendaciones: ApiRecomendacion,
    trazabilidad: ApiTrazabilidad,
    bus: ApiBus,
) {
    val eventos = PublicadorEventos(bus)
    val pedidos = ServicioPedido(fuente, sesion, seguridad, recomendaciones, trazabilidad, eventos)
    val comandas = ServicioComandas(fuente, sesion, eventos)
}

fun Route.rutasPedido(app: AppPedido) {
    route("/api/sesion/pedido") {
        get { call.respond(io { app.pedidos.carrito(call.sesion()) }) }
        post("/items") {
            val p = call.receive<AgregarItemPeticion>()
            call.respond(io { app.pedidos.agregar(call.sesion(), p.productoId, p.cantidad, p.recomendacionId) })
        }
        patch("/items/{id}") {
            val p = call.receive<CantidadPeticion>()
            call.respond(io { app.pedidos.cambiarCantidad(call.sesion(), call.id(), p.cantidad) })
        }
        delete("/items/{id}") { call.respond(io { app.pedidos.eliminar(call.sesion(), call.id()) }) }
        post("/confirmar") {
            val p = call.receive<ConfirmarPeticion>()
            call.respond(io { app.pedidos.confirmar(call.sesion(), p.confirmo) })
        }
    }
    route("/api/backoffice") {
        get("/comandas") {
            val personal = call.personal()
            call.respond(io { app.comandas.activas(personal, call.request.queryParameters["destino"] ?: "cocina") })
        }
        post("/comandas/{id}/estado") {
            val personal = call.personal()
            val p = call.receive<EstadoPeticion>()
            io { app.comandas.avanzar(personal, call.id(), p.estado) }
            call.respond(Ok())
        }
        get("/comandas-atrasadas") { call.respond(io { app.comandas.atrasadas(call.personal()) }) }
    }
}

fun Application.moduloPedido(app: AppPedido, token: String, version: String) =
    moduloServicio(Servicio.PEDIDO, token, version) { rutasPedido(app) }

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    val http = ClienteInterno.http(topologia.tls)
    fun cliente(s: Servicio) = ClienteInterno.para(s, topologia, http)
    val app = AppPedido(
        ArchivoSqlite(Entorno.rutaBaseDatos(entorno)),
        HttpApiSesion(cliente(Servicio.SESION)),
        HttpApiSeguridad(cliente(Servicio.SEGURIDAD)),
        HttpApiRecomendacion(cliente(Servicio.RECOMENDACION)),
        HttpApiTrazabilidad(cliente(Servicio.TRAZABILIDAD)),
        HttpApiBus(cliente(Servicio.BUS)),
    )
    val version = Entorno.version()
    return arrancar(Servicio.PEDIDO, topologia) { moduloPedido(app, topologia.token(Servicio.PEDIDO), version) }
}

fun main() {
    servidor().start(wait = true)
}
