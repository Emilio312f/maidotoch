package sesion.datos

import comun.contratos.EstadoSesion
import comun.datos.consultar
import comun.datos.ejecutar
import comun.datos.enTransaccion
import java.sql.Connection
import java.util.UUID

data class AccesoPuesto(val accesoId: Long, val habilitado: Boolean, val puestoCodigo: String, val puestoTipo: String)

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

    /** Código de puesto de cada sesión, para que cocina, barra y sala sepan a qué mesa ir. */
    fun puestos(sesiones: Collection<String>): Map<String, String> = sesiones.distinct().mapNotNull { id ->
        conn.consultar(
            """
            SELECT p.codigo FROM sesiones s JOIN accesos a ON a.id = s.acceso_id JOIN puntos_servicio p ON p.id = a.punto_id
            WHERE s.id = ?
            """.trimIndent(),
            id,
        ) { it.getString(1) }.singleOrNull()?.let { id to it }
    }.toMap()

    /**
     * Reemplaza el perfil completo. Los triggers suben revision_perfil en cada cambio; las claves
     * foráneas rechazan restricciones o preferencias que no existen.
     */
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
}
