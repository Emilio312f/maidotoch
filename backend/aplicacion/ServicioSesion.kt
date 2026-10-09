package aplicacion

import datos.EstadoSesion
import datos.FuenteConexiones
import datos.OpcionesPerfil
import datos.RepositorioSesiones
import kotlinx.serialization.Serializable
import java.sql.Connection

@Serializable
data class SesionIniciada(val sesionId: String, val estado: EstadoSesion)

internal val IDIOMAS = setOf("es", "en")

internal fun validarIdioma(idioma: String) {
    if (idioma !in IDIOMAS) throw SolicitudInvalida("idioma_no_soportado", "Idioma no soportado: $idioma")
}

/** Sesión del comensal: solo puesto (token NFC/QR) e idioma, sin datos personales (RNF02). */
class ServicioSesion(private val fuente: FuenteConexiones) {

    fun iniciar(token: String, idioma: String): SesionIniciada {
        validarIdioma(idioma)
        return fuente.usar { conn ->
            val repo = RepositorioSesiones(conn)
            val acceso = repo.buscarAcceso(token) ?: throw NoEncontrado("acceso_no_encontrado", "Token de puesto desconocido")
            if (!acceso.habilitado) throw Prohibido("puesto_inactivo", "Puesto ${acceso.puestoCodigo} deshabilitado")
            val id = repo.crear(acceso.accesoId, idioma)
            SesionIniciada(id, repo.estado(id)!!)
        }
    }

    fun estado(sesionId: String): EstadoSesion = fuente.usar { conn -> sesionAbierta(conn, sesionId) }

    fun opciones(idioma: String): OpcionesPerfil {
        validarIdioma(idioma)
        return fuente.usar { RepositorioSesiones(it).opciones(idioma) }
    }

    /** Perfil breve (RF07): restricciones y preferencias elegidas de las listas del catálogo. */
    fun guardarPerfil(sesionId: String, restricciones: List<Long>, preferencias: List<Long>): EstadoSesion = fuente.usar { conn ->
        sesionAbierta(conn, sesionId)
        val repo = RepositorioSesiones(conn)
        if (!repo.existenRestricciones(restricciones) || !repo.existenPreferencias(preferencias)) {
            throw SolicitudInvalida("opcion_desconocida", "Restricción o preferencia inexistente")
        }
        repo.guardarPerfil(sesionId, restricciones, preferencias)
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

    fun encuesta(sesionId: String, satisfaccion: Int) {
        if (satisfaccion !in 1..5) throw SolicitudInvalida("satisfaccion_invalida", "La satisfacción va de 1 a 5")
        fuente.usar { conn ->
            sesionAbierta(conn, sesionId)
            RepositorioSesiones(conn).guardarEncuesta(sesionId, satisfaccion)
        }
    }

    fun cerrarVencidas(horas: Long): Int = fuente.usar { RepositorioSesiones(it).cerrarVencidas(horas) }

    companion object {
        fun sesionAbierta(conn: Connection, sesionId: String): EstadoSesion {
            val estado = RepositorioSesiones(conn).estado(sesionId)
            if (estado == null || !estado.abierta) throw SesionNoDisponible()
            return estado
        }
    }
}
