package atencion

import comun.datos.consultar
import comun.datos.ejecutar
import comun.datos.insertar
import kotlinx.serialization.Serializable
import java.sql.Connection

@Serializable
data class Llamado(
    val id: Long,
    val motivo: String,
    val estado: String,
    val puesto: String,
    val producto: String? = null,
    val creadoEn: String,
    val atendidoEn: String? = null,
)

/** Fila de llamados_mozo; el puesto y el nombre del producto los dan los servicios de Sesión y Catálogo. */
data class LlamadoRegistrado(val llamado: Llamado, val sesionId: String, val productoId: Long?)

/** Tabla del Servicio de Atención: llamados al mozo y derivaciones (RF06 · RF14). */
class RepositorioLlamados(private val conn: Connection) {

    private val seleccion = "SELECT id, motivo, estado, creado_en, atendido_en, sesion_id, producto_id FROM llamados_mozo"

    private fun leer(rs: java.sql.ResultSet) = LlamadoRegistrado(
        Llamado(rs.getLong(1), rs.getString(2), rs.getString(3), "", null, rs.getString(4), rs.getString(5)),
        rs.getString(6),
        rs.getObject(7)?.let { rs.getLong(7) },
    )

    fun llamadoPendiente(sesionId: String, motivo: String): Long? = conn.consultar(
        "SELECT id FROM llamados_mozo WHERE sesion_id = ? AND motivo = ? AND estado = 'pendiente'",
        sesionId, motivo,
    ) { it.getLong(1) }.firstOrNull()

    fun crearLlamado(sesionId: String, motivo: String, productoId: Long?): Long = conn.insertar(
        "INSERT INTO llamados_mozo(sesion_id, motivo, producto_id) VALUES (?, ?, ?)",
        sesionId, motivo, productoId,
    )

    fun llamado(id: Long): LlamadoRegistrado? = conn.consultar("$seleccion WHERE id = ?", id, fila = ::leer).singleOrNull()

    fun llamadosDeSesion(sesionId: String): List<LlamadoRegistrado> =
        conn.consultar("$seleccion WHERE sesion_id = ? ORDER BY id DESC", sesionId, fila = ::leer)

    fun llamadosPendientes(): List<LlamadoRegistrado> =
        conn.consultar("$seleccion WHERE estado = 'pendiente' ORDER BY creado_en, id", fila = ::leer)

    fun atenderLlamado(id: Long, personalId: Long): Int = conn.ejecutar(
        "UPDATE llamados_mozo SET estado = 'atendido', atendido_por = ?, atendido_en = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ? AND estado = 'pendiente'",
        personalId, id,
    )
}
