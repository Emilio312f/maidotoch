package trazabilidad

import comun.NoEncontrado
import comun.SolicitudInvalida
import comun.contratos.ApiRecomendacion
import comun.contratos.ApiSesion
import comun.contratos.ApiTrazabilidad
import comun.datos.FuenteConexiones
import comun.datos.consultar
import comun.datos.ejecutar
import java.sql.Connection

/** Tabla del Servicio de Trazabilidad: recomendacion_eventos (inmutable). */
class RepositorioEventos(private val conn: Connection) {
    fun registrar(recomendacionId: Long, accion: String) {
        conn.ejecutar("INSERT INTO recomendacion_eventos(recomendacion_id, accion) VALUES (?, ?)", recomendacionId, accion)
    }

    fun existe(recomendacionId: Long, accion: String): Boolean = conn.consultar(
        "SELECT 1 FROM recomendacion_eventos WHERE recomendacion_id = ? AND accion = ?",
        recomendacionId, accion,
    ) { true }.isNotEmpty()
}

/** Trazabilidad (RF13 · RNF09): eventos sobre recomendaciones mostradas, sin datos personales. */
class ServicioTrazabilidad(
    private val fuente: FuenteConexiones,
    private val sesion: ApiSesion,
    private val recomendaciones: ApiRecomendacion,
) : ApiTrazabilidad {
    private val accionesComensal = setOf("aceptada", "rechazada", "modificada")
    private val acciones = accionesComensal + "mostrada"

    /** Acción del comensal sobre una recomendación de su propia sesión. */
    fun registrar(sesionId: String, recomendacionId: Long, accion: String) {
        if (accion !in accionesComensal) throw SolicitudInvalida("accion_invalida", "Acción no válida: $accion")
        sesion.sesionAbierta(sesionId)
        recomendaciones.productoDeRecomendacion(recomendacionId, sesionId)
            ?: throw NoEncontrado("recomendacion_no_encontrada", "Recomendación inexistente")
        registrarEvento(recomendacionId, accion)
    }

    /** Una sola vez por recomendación y acción. */
    override fun registrarEvento(recomendacionId: Long, accion: String) {
        if (accion !in acciones) throw SolicitudInvalida("accion_invalida", "Acción no válida: $accion")
        fuente.usar { conn ->
            val repo = RepositorioEventos(conn)
            if (!repo.existe(recomendacionId, accion)) repo.registrar(recomendacionId, accion)
        }
    }
}
