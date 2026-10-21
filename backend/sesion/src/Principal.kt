package sesion

import comun.Entorno
import comun.NoEncontrado
import comun.Servicio
import comun.SolicitudInvalida
import comun.Topologia
import comun.contratos.ActivoPeticion
import comun.contratos.ApiSesion
import comun.contratos.ClavePeticion
import comun.contratos.CuentaPeticion
import comun.contratos.Identificadores
import comun.contratos.IdiomaPeticion
import comun.contratos.IdsPeticion
import comun.contratos.IniciarSesion
import comun.contratos.LoginPeticion
import comun.contratos.Ok
import comun.contratos.PerfilPeticion
import comun.contratos.PuestoPeticion
import comun.contratos.PuestosDeSesiones
import comun.contratos.SesionesPeticion
import comun.contratos.TokenPeticion
import comun.datos.ArchivoSqlite
import comun.datos.FuenteConexiones
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import sesion.aplicacion.InternoSesion
import sesion.aplicacion.ServicioPersonal
import sesion.aplicacion.ServicioPuestos
import sesion.aplicacion.ServicioSesion

private val log = LoggerFactory.getLogger("sesion")

@Serializable
data class CambioClavePeticion(val identificador: String, val clave: String)

/** Composición del Servicio de Sesión: sesiones del comensal, cuentas del personal y puestos NFC/QR. */
class AppSesion(fuente: FuenteConexiones, urlPublica: String, horasSesionPersonal: Long = 12) {
    val sesiones = ServicioSesion(fuente)
    val personal = ServicioPersonal(fuente, horasSesionPersonal)
    val puestos = ServicioPuestos(fuente, urlPublica)
    val interno: ApiSesion = InternoSesion(sesiones, personal)
}

fun Route.rutasSesion(app: AppSesion) {
    route("/api") {
        post("/sesiones") {
            val p = call.receive<IniciarSesion>()
            call.respond(HttpStatusCode.Created, io { app.sesiones.iniciar(p.token, p.idioma, p.sesionPrevia) })
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
        }
        route("/backoffice") {
            post("/login") {
                val p = call.receive<LoginPeticion>()
                call.respond(io { app.personal.iniciarSesion(p.identificador.trim(), p.clave) })
            }
            post("/logout") {
                call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { token -> io { app.personal.cerrarSesion(token) } }
                call.respond(Ok())
            }
            get("/yo") { call.respond(call.personal()) }

            route("/personal") {
                get { call.respond(io { app.personal.listar(call.personal()) }) }
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

            get("/puestos") { call.respond(io { app.puestos.puestos(call.personal()) }) }
            post("/puestos") {
                val admin = call.personal()
                val p = call.receive<PuestoPeticion>()
                call.respond(HttpStatusCode.Created, io { app.puestos.crearPuesto(admin, p.codigo, p.tipo) })
            }
            post("/puestos/{id}/activo") {
                val admin = call.personal()
                val p = call.receive<ActivoPeticion>()
                call.respond(io { app.puestos.cambiarActivoPuesto(admin, call.id(), p.activo) })
            }
        }
    }

    route("/interno") {
        get("/sesiones/{id}") {
            val id = call.parameters["id"] ?: throw SolicitudInvalida("id_invalido", "Sesión inválida")
            call.respond(io { app.interno.sesionAbierta(id) })
        }
        post("/sesiones/puestos") {
            val p = call.receive<SesionesPeticion>()
            call.respond(PuestosDeSesiones(io { app.interno.puestosDeSesiones(p.sesiones) }))
        }
        post("/personal/autenticar") {
            val p = call.receive<TokenPeticion>()
            call.respond(io { app.interno.autenticarPersonal(p.token) })
        }
        get("/personal/por-identificador/{identificador}") {
            val identificador = call.parameters["identificador"].orEmpty()
            call.respond(io { app.interno.personalPorIdentificador(identificador) } ?: throw NoEncontrado("personal_no_encontrado", "No existe $identificador"))
        }
        post("/personal/identificadores") {
            val p = call.receive<IdsPeticion>()
            call.respond(Identificadores(io { app.interno.identificadoresPersonal(p.ids) }))
        }
        // Comandos de operación (herramientas): primera cuenta de administración y cambio de clave.
        post("/personal") {
            val p = call.receive<CuentaPeticion>()
            io { app.personal.crear(p.identificador, p.rol, p.clave) }
            call.respond(HttpStatusCode.Created, Ok())
        }
        post("/personal/clave") {
            val p = call.receive<CambioClavePeticion>()
            io { app.personal.cambiarClave(p.identificador, p.clave) }
            call.respond(Ok())
        }
    }
}

fun Application.moduloSesion(app: AppSesion, token: String, version: String, horasSesionComensal: Long?) {
    moduloServicio(Servicio.SESION, token, version) { rutasSesion(app) }
    if (horasSesionComensal != null) {
        // Las sesiones abandonadas se cierran y su perfil se descarta (RNF02).
        launch {
            while (true) {
                runCatching { io { app.sesiones.cerrarVencidas(horasSesionComensal) } }
                    .onSuccess { if (it > 0) log.info("Sesiones vencidas cerradas: {}", it) }
                    .onFailure { log.warn("No se pudieron cerrar sesiones vencidas", it) }
                delay(10 * 60 * 1000L)
            }
        }
    }
}

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    val app = AppSesion(
        ArchivoSqlite(Entorno.rutaBaseDatos(entorno)),
        urlPublica = (Entorno.texto("MAIDOTOUCH_URL_PUBLICA", entorno) ?: "http://localhost:5173").trimEnd('/'),
        horasSesionPersonal = Entorno.texto("MAIDOTOUCH_PERSONAL_SESION_HORAS", entorno)?.toLong() ?: 12,
    )
    val horas = Entorno.texto("MAIDOTOUCH_SESION_HORAS", entorno)?.toLong() ?: 6
    val version = Entorno.version()
    return arrancar(Servicio.SESION, topologia) { moduloSesion(app, topologia.token(Servicio.SESION), version, horas) }
}

fun main() {
    servidor().start(wait = true)
}
