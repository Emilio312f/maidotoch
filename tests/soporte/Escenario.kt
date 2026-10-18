package soporte

import configuracion.Configuracion
import datos.PersonalAutenticado
import datos.RepositorioCatalogo
import datos.consultar
import dominio.recomendacion.OrdenadorExterno
import presentacion.Aplicacion
import java.io.File

/**
 * Escenario completo con el catálogo FICTICIO de db/catalogo/ficticio.json cargado por el importador,
 * validado por cocina y disponible; cuentas del back-office y un puesto de mesa y uno de barra.
 */
class Escenario(
    proveedorIa: OrdenadorExterno? = null,
    configuracion: Configuracion = Configuracion(
        directorioFrontend = null,
        directorioFotos = java.nio.file.Files.createTempDirectory("maidotouch-fotos").toString(),
    ),
) : AutoCloseable {
    val db = BaseDePrueba()
    val app = Aplicacion(configuracion, db.fuente, proveedorIa)

    val admin: PersonalAutenticado = cuenta("admin-prueba", "administrador")
    val cocina: PersonalAutenticado = cuenta("cocina-prueba", "cocina")
    val barra: PersonalAutenticado = cuenta("barra-prueba", "barra")
    val sala: PersonalAutenticado = cuenta("sala-prueba", "sala")

    init {
        val archivo = File(System.getProperty("maidotouch.catalogoFicticio") ?: "db/catalogo/ficticio.json")
        app.importador.importar(archivo.readText(), "cocina-prueba")
        db.fuente.usar { conn ->
            val repo = RepositorioCatalogo(conn)
            repo.productosBackoffice().forEach {
                if (!it.validado) repo.validar(it.id, cocina.id)
                if (!it.disponible) repo.cambiarDisponibilidad(it.id, true, cocina.id, "FICTICIO: prueba")
            }
        }
    }

    val mesa = app.backoffice.crearPuesto(admin, "T1", "mesa")
    val tokenMesa: String = mesa.puesto.accesos.first { it.canal == "nfc" }.token

    fun cuenta(identificador: String, rol: String): PersonalAutenticado {
        app.personal.crear(identificador, rol, CLAVE)
        return app.personal.autenticar(app.personal.iniciarSesion(identificador, CLAVE).token)
    }

    fun producto(codigo: String): Long =
        db.conn.consultar("SELECT id FROM productos WHERE codigo = ?", codigo) { it.getLong(1) }.single()

    fun restriccion(codigo: String): Long =
        db.conn.consultar("SELECT id FROM restricciones WHERE codigo = ?", codigo) { it.getLong(1) }.single()

    fun preferencia(codigo: String): Long =
        db.conn.consultar("SELECT id FROM preferencias WHERE codigo = ?", codigo) { it.getLong(1) }.single()

    /** Sesión con perfil completo en la mesa T1. */
    fun sesion(restricciones: List<String> = emptyList(), preferencias: List<String> = emptyList(), idioma: String = "es"): String {
        val id = app.sesiones.iniciar(tokenMesa, idioma).sesionId
        app.sesiones.guardarPerfil(id, restricciones.map(::restriccion), preferencias.map(::preferencia))
        return id
    }

    override fun close() = db.close()

    companion object {
        const val CLAVE = "clave-de-prueba-ficticia"
    }
}
