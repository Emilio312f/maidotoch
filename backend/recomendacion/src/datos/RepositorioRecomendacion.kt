package recomendacion.datos

import comun.datos.consultar
import comun.datos.ejecutar
import comun.datos.insertar
import java.sql.Connection

data class NuevaRecomendacion(
    val sesionId: String,
    val productoId: Long,
    val revisionProducto: Long,
    val revisionPerfil: Long,
    val posicion: Int,
    val explicacion: String,
    val motor: String,
    val versionReglas: String,
)

/** Tablas del Servicio de Recomendación: recomendaciones (inmutables) y consultas al asistente. */
class RepositorioRecomendacion(private val conn: Connection) {

    fun consultasConIa(sesionId: String): Int =
        conn.consultar("SELECT count(*) FROM consultas_asistente WHERE sesion_id = ? AND motor = 'ia'", sesionId) { it.getInt(1) }.single()

    fun registrarConsulta(sesionId: String, motor: String, latenciaMs: Long, recomendaciones: Int) {
        conn.ejecutar(
            "INSERT INTO consultas_asistente(sesion_id, motor, latencia_ms, recomendaciones) VALUES (?, ?, ?, ?)",
            sesionId, motor, latenciaMs, recomendaciones,
        )
    }

    /** El trigger recomendacion_segura rechaza la fila si el producto o el perfil cambiaron de revisión. */
    fun insertar(nueva: NuevaRecomendacion): Long = conn.insertar(
        """
        INSERT INTO recomendaciones(sesion_id, producto_id, revision_producto, revision_perfil, posicion, explicacion, motor, version_reglas)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        nueva.sesionId, nueva.productoId, nueva.revisionProducto, nueva.revisionPerfil, nueva.posicion,
        nueva.explicacion, nueva.motor, nueva.versionReglas,
    )

    /** Producto de la recomendación si pertenece a la sesión; null si no existe o es de otra sesión. */
    fun productoDeRecomendacion(recomendacionId: Long, sesionId: String): Long? = conn.consultar(
        "SELECT producto_id FROM recomendaciones WHERE id = ? AND sesion_id = ?",
        recomendacionId, sesionId,
    ) { it.getLong(1) }.singleOrNull()
}
