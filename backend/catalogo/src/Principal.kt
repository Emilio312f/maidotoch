package catalogo

import catalogo.aplicacion.ImportadorCatalogo
import catalogo.aplicacion.ServicioBackofficeCatalogo
import catalogo.aplicacion.ServicioCatalogo
import catalogo.aplicacion.ServicioFotos
import comun.Entorno
import comun.Servicio
import comun.SolicitudInvalida
import comun.Topologia
import comun.contratos.ApiSeguridad
import comun.contratos.ApiSesion
import comun.contratos.DisponibilidadPeticion
import comun.contratos.Existe
import comun.contratos.Fichas
import comun.contratos.HttpApiSeguridad
import comun.contratos.HttpApiSesion
import comun.contratos.IdsIdiomaPeticion
import comun.contratos.ImportarCatalogoPeticion
import comun.contratos.Nombres
import comun.datos.ArchivoSqlite
import comun.datos.FuenteConexiones
import comun.http.ClienteInterno
import comun.http.arrancar
import comun.http.id
import comun.http.io
import comun.http.moduloServicio
import comun.http.personal
import comun.http.sesion
import io.ktor.http.CacheControl
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.io.File

/** Composición del Servicio de Catálogo. */
class AppCatalogo(fuente: FuenteConexiones, val directorioFotos: File, sesion: ApiSesion, seguridad: ApiSeguridad) {
    val catalogo = ServicioCatalogo(fuente, sesion, seguridad)
    val backoffice = ServicioBackofficeCatalogo(fuente, catalogo, sesion, seguridad)
    val fotos = ServicioFotos(fuente, directorioFotos, catalogo, backoffice)
    val importador = ImportadorCatalogo(fuente, sesion, seguridad, catalogo)
}

fun Route.rutasCatalogo(app: AppCatalogo) {
    route("/api") {
        get("/opciones-perfil") {
            call.respond(io { app.catalogo.opciones(call.request.queryParameters["idioma"] ?: "es") })
        }
        get("/sesion/carta") { call.respond(io { app.catalogo.cartaParaSesion(call.sesion()) }) }

        route("/backoffice") {
            get("/productos") { call.respond(io { app.backoffice.productos(call.personal()) }) }
            get("/productos/{id}") { call.respond(io { app.backoffice.detalle(call.personal(), call.id()) }) }
            post("/productos/{id}/disponibilidad") {
                val personal = call.personal()
                val p = call.receive<DisponibilidadPeticion>()
                call.respond(io { app.backoffice.cambiarDisponibilidad(personal, call.id(), p.disponible, p.motivo) })
            }
            post("/productos/{id}/foto") {
                val personal = call.personal()
                val largo = call.request.headers["Content-Length"]?.toLongOrNull()
                if (largo != null && largo > ServicioFotos.TAMANO_MAXIMO) {
                    throw SolicitudInvalida("foto_invalida", "La foto supera el tamaño máximo")
                }
                val contenido = call.receive<ByteArray>()
                call.respond(io { app.fotos.subir(personal, call.id(), contenido) })
            }
            post("/productos/{id}/validar") { call.respond(io { app.backoffice.validar(call.personal(), call.id()) }) }
            get("/disponibilidad/historial") { call.respond(io { app.backoffice.historial(call.personal()) }) }
        }
    }

    // Fotos de la carta: el nombre incluye un hash del contenido, por eso se pueden cachear sin vencimiento.
    staticFiles(ServicioFotos.RUTA_PUBLICA, app.directorioFotos.also { it.mkdirs() }) {
        cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public)) }
    }

    route("/interno/catalogo") {
        get("/disponibles") {
            call.respond(Fichas(io { app.catalogo.disponibles(call.request.queryParameters["idioma"] ?: "es") }))
        }
        post("/nombres") {
            val p = call.receive<IdsIdiomaPeticion>()
            call.respond(Nombres(io { app.catalogo.nombres(p.ids, p.idioma) }))
        }
        get("/productos/{id}/existe") { call.respond(Existe(io { app.catalogo.existe(call.id()) })) }
        // Comando de operación: carga del catálogo validado por cocina.
        post("/importar") {
            val p = call.receive<ImportarCatalogoPeticion>()
            call.respond(io { app.importador.importar(p.contenido, p.evaluador) })
        }
    }
}

fun Application.moduloCatalogo(app: AppCatalogo, token: String, version: String) =
    moduloServicio(Servicio.CATALOGO, token, version) { rutasCatalogo(app) }

fun servidor(entorno: Map<String, String> = System.getenv()): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val topologia = Topologia.desdeEntorno(entorno)
    val http = ClienteInterno.http(topologia.tls)
    val app = AppCatalogo(
        ArchivoSqlite(Entorno.rutaBaseDatos(entorno)),
        File(Entorno.texto("MAIDOTOUCH_FOTOS_DIR", entorno) ?: "fotos"),
        HttpApiSesion(ClienteInterno.para(Servicio.SESION, topologia, http)),
        HttpApiSeguridad(ClienteInterno.para(Servicio.SEGURIDAD, topologia, http)),
    )
    val version = Entorno.version()
    return arrancar(Servicio.CATALOGO, topologia) { moduloCatalogo(app, topologia.token(Servicio.CATALOGO), version) }
}

fun main() {
    servidor().start(wait = true)
}
