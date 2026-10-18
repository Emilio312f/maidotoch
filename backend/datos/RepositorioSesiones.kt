package datos

import kotlinx.serialization.Serializable
import java.sql.Connection
import java.util.UUID

data class AccesoPuesto(val accesoId: Long, val habilitado: Boolean, val puestoCodigo: String, val puestoTipo: String)

@Serializable
data class EstadoSesion(
    val idioma: String,
    val abierta: Boolean,
    val perfilCompleto: Boolean,
    val puestoCodigo: String,
    val puestoTipo: String,
    val restricciones: List<Long>,
    val preferencias: List<Long>,
)

@Serializable
data class OpcionPerfil(val id: Long, val codigo: String, val tipo: String? = null, val nombre: String)

@Serializable
data class OpcionesPerfil(val restricciones: List<OpcionPerfil>, val preferencias: List<OpcionPerfil>)

class RepositorioSesiones(private val conn: Connection) {

    fun buscarAcceso(token: String): AccesoPuesto? = conn.consultar(
        """
        SELECT a.id, a.activo = 1 AND p.activo = 1, p.codigo, p.tipo
        FROM accesos a JOIN puntos_servicio p ON p.id = a.punto_id
        WHERE a.token = ?
        """.trimIndent(),
        token,
    ) { AccesoPuesto(it.getLong(1), it.getInt(2) == 1, it.getString(3), it.getString(4)) }.singleOrNull()

    /** El trigger sesion_acceso_activo vuelve a comprobar que acceso y puesto estén activos. */
    fun crear(accesoId: Long, idioma: String): String {
        val id = UUID.randomUUID().toString()
        conn.ejecutar("INSERT INTO sesiones(id, acceso_id, idioma) VALUES (?, ?, ?)", id, accesoId, idioma)
        return id
    }

    fun estado(sesionId: String): EstadoSesion? {
        val restricciones = conn.consultar("SELECT restriccion_id FROM sesion_restricciones WHERE sesion_id = ? ORDER BY 1", sesionId) { it.getLong(1) }
        val preferencias = conn.consultar("SELECT preferencia_id FROM sesion_preferencias WHERE sesion_id = ? ORDER BY 1", sesionId) { it.getLong(1) }
        return conn.consultar(
            """
            SELECT s.idioma, s.cierre IS NULL, s.perfil_completo, p.codigo, p.tipo
            FROM sesiones s JOIN accesos a ON a.id = s.acceso_id JOIN puntos_servicio p ON p.id = a.punto_id
            WHERE s.id = ?
            """.trimIndent(),
            sesionId,
        ) {
            EstadoSesion(it.getString(1), it.getInt(2) == 1, it.getInt(3) == 1, it.getString(4), it.getString(5), restricciones, preferencias)
        }.singleOrNull()
    }

    fun opciones(idioma: String): OpcionesPerfil {
        val columna = if (idioma == "en") "nombre_en" else "nombre_es"
        return OpcionesPerfil(
            restricciones = conn.consultar("SELECT id, codigo, tipo, $columna FROM restricciones ORDER BY tipo, $columna") {
                OpcionPerfil(it.getLong(1), it.getString(2), it.getString(3), it.getString(4))
            },
            preferencias = conn.consultar("SELECT id, codigo, $columna FROM preferencias ORDER BY $columna") {
                OpcionPerfil(it.getLong(1), it.getString(2), null, it.getString(3))
            },
        )
    }

    fun existenRestricciones(ids: Collection<Long>): Boolean = ids.all { id ->
        conn.consultar("SELECT 1 FROM restricciones WHERE id = ?", id) { true }.isNotEmpty()
    }

    fun existenPreferencias(ids: Collection<Long>): Boolean = ids.all { id ->
        conn.consultar("SELECT 1 FROM preferencias WHERE id = ?", id) { true }.isNotEmpty()
    }

    /** Reemplaza el perfil completo. Los triggers suben revision_perfil en cada cambio. */
    fun guardarPerfil(sesionId: String, restricciones: Collection<Long>, preferencias: Collection<Long>) = conn.enTransaccion {
        conn.ejecutar("DELETE FROM sesion_restricciones WHERE sesion_id = ?", sesionId)
        conn.ejecutar("DELETE FROM sesion_preferencias WHERE sesion_id = ?", sesionId)
        restricciones.distinct().forEach {
            conn.ejecutar("INSERT INTO sesion_restricciones(sesion_id, restriccion_id) VALUES (?, ?)", sesionId, it)
        }
        preferencias.distinct().forEach {
            conn.ejecutar("INSERT INTO sesion_preferencias(sesion_id, preferencia_id) VALUES (?, ?)", sesionId, it)
        }
        conn.ejecutar("UPDATE sesiones SET perfil_completo = 1 WHERE id = ?", sesionId)
    }

    fun cambiarIdioma(sesionId: String, idioma: String) {
        conn.ejecutar("UPDATE sesiones SET idioma = ? WHERE id = ? AND cierre IS NULL", idioma, sesionId)
    }

    /** Cierra la sesión y descarta el perfil declarado (Diseño §9.1 · RNF02). */
    fun cerrar(sesionId: String) = conn.enTransaccion {
        conn.ejecutar("DELETE FROM sesion_restricciones WHERE sesion_id = ?", sesionId)
        conn.ejecutar("DELETE FROM sesion_preferencias WHERE sesion_id = ?", sesionId)
        conn.ejecutar(
            "UPDATE sesiones SET cierre = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ? AND cierre IS NULL",
            sesionId,
        )
    }

    fun cerrarVencidas(horas: Long): Int {
        val vencidas = conn.consultar(
            "SELECT id FROM sesiones WHERE cierre IS NULL AND inicio < strftime('%Y-%m-%dT%H:%M:%fZ','now', ?)",
            "-$horas hours",
        ) { it.getString(1) }
        vencidas.forEach { cerrar(it) }
        return vencidas.size
    }

    fun guardarEncuesta(sesionId: String, satisfaccion: Int) {
        conn.ejecutar(
            """
            INSERT INTO encuestas(sesion_id, satisfaccion) VALUES (?, ?)
            ON CONFLICT(sesion_id) DO UPDATE SET satisfaccion = excluded.satisfaccion, fecha = strftime('%Y-%m-%dT%H:%M:%fZ','now')
            """.trimIndent(),
            sesionId, satisfaccion,
        )
    }
}
