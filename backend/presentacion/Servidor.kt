package presentacion

import aplicacion.Conflicto
import aplicacion.ErrorNegocio
import aplicacion.exigirRol
import datos.PersonalAutenticado
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("presentacion.Servidor")

/** Los servicios usan JDBC (bloqueante): se ejecutan fuera del hilo de eventos del servidor. */
private suspend fun <T> io(bloque: () -> T): T = withContext(Dispatchers.IO) { bloque() }

/** La sesión del comensal viaja en una cabecera; nunca en la URL, para que no quede en logs ni historial. */
private fun ApplicationCall.sesion(): String =
    request.headers["X-Sesion"]?.takeIf { it.isNotBlank() } ?: throw aplicacion.SesionNoDisponible()

private fun ApplicationCall.tokenPersonal(): String? =
    request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

private fun ApplicationCall.id(nombre: String = "id"): Long =
    parameters[nombre]?.toLongOrNull() ?: throw aplicacion.SolicitudInvalida("id_invalido", "Identificador inválido")

fun Application.moduloMaidoTouch(app: Aplicacion, programarTareas: Boolean = true) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
    }
    install(StatusPages) {
        exception<ErrorNegocio> { call, e ->
            val detalle = (e as? Conflicto)?.detalle.orEmpty()
            call.respond(HttpStatusCode.fromValue(e.estadoHttp), ErrorRespuesta(e.codigo, e.message ?: e.codigo, detalle))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorRespuesta("solicitud_invalida", "Cuerpo de la solicitud inválido"))
        }
        exception<Throwable> { call, e ->
            log.error("Error no controlado en {}", call.request.local.uri, e)
            call.respond(HttpStatusCode.InternalServerError, ErrorRespuesta("error_interno", "Error interno"))
        }
    }

    if (programarTareas) {
        // Las sesiones abandonadas se cierran y su perfil se descarta (RNF02).
        launch {
            while (true) {
                runCatching { io { app.sesiones.cerrarVencidas(app.configuracion.horasSesionComensal) } }
                    .onSuccess { if (it > 0) log.info("Sesiones vencidas cerradas: {}", it) }
                    .onFailure { log.warn("No se pudieron cerrar sesiones vencidas", it) }
                delay(10 * 60 * 1000L)
            }
        }
    }

    routing {
        route("/api") {
            get("/salud") {
                call.respond(Salud("ok", app.ordenador != null, app.ordenador?.estaAbierto() ?: false))
            }
            rutasComensal(app)
            route("/backoffice") { rutasBackoffice(app) }
        }

        app.configuracion.directorioFrontend?.let(::File)?.takeIf { it.resolve("index.html").exists() }?.let { dir ->
            singlePageApplication {
                useResources = false
                filesPath = dir.absolutePath
                defaultPage = "index.html"
            }
        }
    }
}

private fun Route.rutasComensal(app: Aplicacion) {
    post("/sesiones") {
        val peticion = call.receive<IniciarSesion>()
        call.respond(HttpStatusCode.Created, io { app.sesiones.iniciar(peticion.token, peticion.idioma) })
    }
    get("/opciones-perfil") {
        val idioma = call.request.queryParameters["idioma"] ?: "es"
        call.respond(io { app.sesiones.opciones(idioma) })
    }

    route("/sesion") {
        get { call.respond(io { app.sesiones.estado(call.sesion()) }) }
        put("/perfil") {
            val p = call.receive<PerfilPeticion>()
            call.respond(io { app.sesiones.guardarPerfil(call.sesion(), p.restricciones, p.preferencias) })
        }
        put("/idioma") {
            val p = call.receive<IdiomaPeticion>()
            call.respond(io { app.sesiones.cambiarIdioma(call.sesion(), p.idioma) })
        }
        post("/cerrar") {
            io { app.sesiones.cerrar(call.sesion()) }
            call.respond(Ok())
        }
        get("/carta") { call.respond(io { app.catalogo.cartaParaSesion(call.sesion()) }) }

        post("/asistente") {
            val p = call.receive<ConsultaPeticion>()
            call.respond(app.orquestador.recomendar(call.sesion(), p.consulta))
        }
        post("/recomendaciones/{id}/eventos") {
            val p = call.receive<EventoPeticion>()
            io { app.trazabilidad.registrar(call.sesion(), call.id(), p.accion) }
            call.respond(Ok())
        }

        route("/pedido") {
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

        get("/llamados") { call.respond(io { app.atencion.llamadosDeSesion(call.sesion()) }) }
        post("/llamados") {
            val p = call.receive<LlamadoPeticion>()
            call.respond(HttpStatusCode.Created, io { app.atencion.llamar(call.sesion(), p.motivo, p.productoId) })
        }
        post("/encuesta") {
            val p = call.receive<EncuestaPeticion>()
            io { app.sesiones.encuesta(call.sesion(), p.satisfaccion) }
            call.respond(Ok())
        }
    }
}

private fun Route.rutasBackoffice(app: Aplicacion) {
    suspend fun ApplicationCall.personal(): PersonalAutenticado = io { app.personal.autenticar(tokenPersonal()) }

    post("/login") {
        val p = call.receive<LoginPeticion>()
        call.respond(io { app.personal.iniciarSesion(p.identificador.trim(), p.clave) })
    }
    post("/logout") {
        call.tokenPersonal()?.let { token -> io { app.personal.cerrarSesion(token) } }
        call.respond(Ok())
    }
    get("/yo") { call.respond(call.personal()) }

    get("/productos") {
        val personal = call.personal()
        call.respond(io { app.backoffice.productos(personal) })
    }
    get("/productos/{id}") {
        val personal = call.personal()
        call.respond(io { app.backoffice.detalle(personal, call.id()) })
    }
    post("/productos/{id}/disponibilidad") {
        val personal = call.personal()
        val p = call.receive<DisponibilidadPeticion>()
        call.respond(io { app.backoffice.cambiarDisponibilidad(personal, call.id(), p.disponible, p.motivo) })
    }
    post("/productos/{id}/validar") {
        val personal = call.personal()
        call.respond(io { app.backoffice.validar(personal, call.id()) })
    }
    get("/disponibilidad/historial") {
        val personal = call.personal()
        call.respond(io { app.backoffice.historial(personal) })
    }

    get("/comandas") {
        val personal = call.personal()
        val destino = call.request.queryParameters["destino"] ?: "cocina"
        call.respond(io { app.comandas.activas(personal, destino) })
    }
    post("/comandas/{id}/estado") {
        val personal = call.personal()
        val p = call.receive<EstadoPeticion>()
        io { app.comandas.avanzar(personal, call.id(), p.estado) }
        call.respond(Ok())
    }

    get("/llamados") {
        val personal = call.personal()
        call.respond(io { app.atencion.pendientes(personal) })
    }
    post("/llamados/{id}/atender") {
        val personal = call.personal()
        call.respond(io { app.atencion.atender(personal, call.id()) })
    }

    get("/puestos") {
        val personal = call.personal()
        call.respond(io { app.backoffice.puestos(personal) })
    }
    post("/puestos") {
        val personal = call.personal()
        val p = call.receive<PuestoPeticion>()
        call.respond(HttpStatusCode.Created, io { app.backoffice.crearPuesto(personal, p.codigo, p.tipo) })
    }
    post("/puestos/{id}/activo") {
        val personal = call.personal()
        val p = call.receive<ActivoPeticion>()
        call.respond(io { app.backoffice.cambiarActivoPuesto(personal, call.id(), p.activo) })
    }

    get("/metricas") {
        val personal = call.personal()
        call.respond(io { app.backoffice.metricas(personal) })
    }

    get("/comandas-atrasadas") {
        val personal = call.personal()
        call.respond(io { app.comandas.atrasadas(personal) })
    }

    route("/personal") {
        get {
            val admin = call.personal()
            call.respond(io { app.personal.listar(admin) })
        }
        post {
            val admin = call.personal()
            val p = call.receive<CuentaPeticion>()
            call.respond(HttpStatusCode.Created, io { app.personal.crearDesdeBackoffice(admin, p.identificador, p.rol, p.clave) })
        }
        post("/{id}/activo") {
            val admin = call.personal()
            val p = call.receive<ActivoPeticion>()
            call.respond(io { app.personal.cambiarActivo(admin, call.id(), p.activo) })
        }
        post("/{id}/clave") {
            val admin = call.personal()
            val p = call.receive<ClavePeticion>()
            io { app.personal.restablecerClave(admin, call.id(), p.clave) }
            call.respond(Ok())
        }
    }

    route("/mediciones") {
        get {
            val admin = call.personal()
            call.respond(io { app.piloto.mediciones(admin) })
        }
        post {
            val admin = call.personal()
            val p = call.receive<MedicionPeticion>()
            call.respond(
                HttpStatusCode.Created,
                io { app.piloto.registrarMedicion(admin, p.fase, p.fecha, p.comensales, p.importeCentimos, p.tiempoPedidoSegundos, p.consultasMozo, p.fuente) },
            )
        }
    }

    get("/reporte-diario") {
        val admin = call.personal()
        val fecha = call.request.queryParameters["fecha"] ?: java.time.LocalDate.now(java.time.ZoneId.of("America/Lima")).toString()
        call.respond(io { app.piloto.reporteDiario(admin, fecha) })
    }

    get("/verificacion") {
        val admin = call.personal()
        admin.exigirRol()
        val chequeos = io { app.piloto.verificar() }
        call.respond(Verificacion(chequeos, aplicacion.ServicioPiloto.VERIFICACIONES_MANUALES, chequeos.none { it.resultado == aplicacion.ResultadoChequeo.FALLA }))
    }
}
