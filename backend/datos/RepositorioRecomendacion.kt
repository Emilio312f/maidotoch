package datos

import dominio.recomendacion.CandidatoRecomendable
import dominio.recomendacion.ContextoPerfil
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

class RepositorioRecomendacion(private val conn: Connection) {

    fun contexto(sesionId: String): ContextoPerfil {
        val idioma = conn.consultar("SELECT idioma FROM sesiones WHERE id = ?", sesionId) { it.getString(1) }.single()
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        return ContextoPerfil(
            idioma = idioma,
            preferencias = conn.consultar(
                "SELECT p.id, p.$columna FROM sesion_preferencias sp JOIN preferencias p ON p.id = sp.preferencia_id WHERE sp.sesion_id = ?",
                sesionId,
            ) { it.getLong(1) to it.getString(2) }.toMap(),
            restricciones = conn.consultar(
                "SELECT r.$columna FROM sesion_restricciones sr JOIN restricciones r ON r.id = sr.restriccion_id WHERE sr.sesion_id = ? ORDER BY r.tipo, 1",
                sesionId,
            ) { it.getString(1) },
        )
    }

    /** Datos de catálogo de los candidatos seguros, en el idioma de la sesión. */
    fun candidatos(revisiones: Map<Long, Long>, idioma: String): List<CandidatoRecomendable> {
        if (revisiones.isEmpty()) return emptyList()
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        val preferencias = conn.consultar(
            "SELECT pp.producto_id, pr.id, pr.$columna FROM producto_preferencias pp JOIN preferencias pr ON pr.id = pp.preferencia_id",
        ) { Triple(it.getLong(1), it.getLong(2), it.getString(3)) }
            .groupBy { it.first }
            .mapValues { (_, filas) -> filas.associate { it.second to it.third } }
        return conn.consultar(
            """
            SELECT p.id, t.nombre, t.descripcion, c.orden
            FROM productos p JOIN producto_textos t ON t.producto_id = p.id AND t.idioma = ?
            JOIN categorias c ON c.id = p.categoria_id
            """.trimIndent(),
            idioma,
        ) { rs ->
            val id = rs.getLong(1)
            revisiones[id]?.let { revision ->
                CandidatoRecomendable(id, revision, rs.getString(2), rs.getString(3), rs.getInt(4), preferencias[id].orEmpty())
            }
        }.filterNotNull()
    }

    fun nombres(ids: Collection<Long>, idioma: String): Map<Long, String> = ids.associateWith { id ->
        conn.consultar("SELECT nombre FROM producto_textos WHERE producto_id = ? AND idioma = ?", id, idioma) { it.getString(1) }
            .singleOrNull() ?: ""
    }

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

    fun registrarEvento(recomendacionId: Long, accion: String) {
        conn.ejecutar("INSERT INTO recomendacion_eventos(recomendacion_id, accion) VALUES (?, ?)", recomendacionId, accion)
    }

    /** Producto de la recomendación si pertenece a la sesión; null si no existe o es de otra sesión. */
    fun productoDeRecomendacion(recomendacionId: Long, sesionId: String): Long? = conn.consultar(
        "SELECT producto_id FROM recomendaciones WHERE id = ? AND sesion_id = ?",
        recomendacionId, sesionId,
    ) { it.getLong(1) }.singleOrNull()

    fun tieneEvento(recomendacionId: Long, accion: String): Boolean = conn.consultar(
        "SELECT 1 FROM recomendacion_eventos WHERE recomendacion_id = ? AND accion = ?",
        recomendacionId, accion,
    ) { true }.isNotEmpty()
}
