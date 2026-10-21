package sesion.aplicacion

import comun.NoEncontrado
import comun.PersonalAutenticado
import comun.Prohibido
import comun.SesionNoDisponible
import comun.SolicitudInvalida
import comun.contratos.ApiSesion
import comun.contratos.EstadoSesion
import comun.contratos.PersonalResumen
import comun.datos.FuenteConexiones
import comun.validarIdioma
import kotlinx.serialization.Serializable
import sesion.datos.RepositorioSesiones
import java.sql.Connection
import java.sql.SQLException

@Serializable
data class SesionIniciada(val sesionId: String, val estado: EstadoSesion)

/** Sesión del comensal: solo puesto (token NFC/QR) e idioma, sin datos personales (RNF02). */
class ServicioSesion(private val fuente: FuenteConexiones) {

    /**
     * Abre la sesión del puesto. Si el celular trae una [sesionPrevia] abierta del mismo puesto (por NFC
     * o QR), la retoma con su perfil y su carrito; cualquier otro caso abre una sesión nueva.
     */
    fun iniciar(token: String, idioma: String, sesionPrevia: String? = null): SesionIniciada {
        validarIdioma(idioma)
        return fuente.usar { conn ->
            val repo = RepositorioSesiones(conn)
            val acceso = repo.buscarAcceso(token) ?: throw NoEncontrado("acceso_no_encontrado", "Token de puesto desconocido")
            if (!acceso.habilitado) throw Prohibido("puesto_inactivo", "Puesto ${acceso.puestoCodigo} deshabilitado")
            val previa = sesionPrevia?.let { repo.estado(it) }
            if (previa != null && previa.abierta && previa.puestoCodigo == acceso.puestoCodigo) {
                return@usar SesionIniciada(sesionPrevia, previa)
            }
            val id = repo.crear(acceso.accesoId, idioma)
            SesionIniciada(id, repo.estado(id)!!)
        }
    }

    fun estado(sesionId: String): EstadoSesion = fuente.usar { conn -> sesionAbierta(conn, sesionId) }

    /** Perfil breve (RF07): restricciones y preferencias elegidas de las listas del catálogo. */
    fun guardarPerfil(sesionId: String, restricciones: List<Long>, preferencias: List<Long>): EstadoSesion = fuente.usar { conn ->
        sesionAbierta(conn, sesionId)
        val repo = RepositorioSesiones(conn)
        try {
            repo.guardarPerfil(sesionId, restricciones, preferencias)
        } catch (e: SQLException) {
            // Las claves foráneas rechazan ids que no están en las listas de restricciones y preferencias.
            if (e.message?.contains("FOREIGN KEY", ignoreCase = true) == true) {
                throw SolicitudInvalida("opcion_desconocida", "Restricción o preferencia inexistente")
            }
            throw e
        }
        repo.estado(sesionId)!!
    }

    fun cambiarIdioma(sesionId: String, idioma: String): EstadoSesion {
        validarIdioma(idioma)
        return fuente.usar { conn ->
            sesionAbierta(conn, sesionId)
            RepositorioSesiones(conn).cambiarIdioma(sesionId, idioma)
            RepositorioSesiones(conn).estado(sesionId)!!
        }
    }

    fun cerrar(sesionId: String) = fuente.usar { conn ->
        sesionAbierta(conn, sesionId)
        RepositorioSesiones(conn).cerrar(sesionId)
    }

    fun cerrarVencidas(horas: Long): Int = fuente.usar { RepositorioSesiones(it).cerrarVencidas(horas) }

    fun puestos(sesiones: Collection<String>): Map<String, String> = fuente.usar { RepositorioSesiones(it).puestos(sesiones) }

    companion object {
        fun sesionAbierta(conn: Connection, sesionId: String): EstadoSesion {
            val estado = RepositorioSesiones(conn).estado(sesionId)
            if (estado == null || !estado.abierta) throw SesionNoDisponible()
            return estado
        }
    }
}

/** API interna del Servicio de Sesión, la misma para las rutas HTTP y para las pruebas en proceso. */
class InternoSesion(private val sesiones: ServicioSesion, private val personal: ServicioPersonal) : ApiSesion {
    override fun sesionAbierta(sesionId: String): EstadoSesion = sesiones.estado(sesionId)

    override fun puestosDeSesiones(sesiones: Collection<String>): Map<String, String> = this.sesiones.puestos(sesiones)

    override fun autenticarPersonal(token: String?): PersonalAutenticado = personal.autenticar(token)

    override fun personalPorIdentificador(identificador: String): PersonalResumen? = personal.resumen(identificador)

    override fun identificadoresPersonal(ids: Collection<Long>): Map<Long, String> = personal.identificadores(ids)
}
