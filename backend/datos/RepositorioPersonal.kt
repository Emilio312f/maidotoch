package datos

import kotlinx.serialization.Serializable
import java.sql.Connection

data class Credencial(val hash: String, val sal: String, val iteraciones: Int)

data class PersonalRegistrado(val id: Long, val identificador: String, val rol: String, val activo: Boolean, val credencial: Credencial?)

@Serializable
data class PersonalAutenticado(val id: Long, val identificador: String, val rol: String)

@Serializable
data class AccesoPuestoInfo(val id: Long, val canal: String, val token: String, val activo: Boolean)

@Serializable
data class Puesto(val id: Long, val codigo: String, val tipo: String, val activo: Boolean, val accesos: List<AccesoPuestoInfo>)

class RepositorioPersonal(private val conn: Connection) {

    fun buscar(identificador: String): PersonalRegistrado? = conn.consultar(
        """
        SELECT p.id, p.identificador, p.rol, p.activo, c.hash, c.sal, c.iteraciones
        FROM personal p LEFT JOIN personal_credenciales c ON c.personal_id = p.id WHERE p.identificador = ?
        """.trimIndent(),
        identificador,
    ) {
        val credencial = it.getString(5)?.let { hash -> Credencial(hash, it.getString(6), it.getInt(7)) }
        PersonalRegistrado(it.getLong(1), it.getString(2), it.getString(3), it.getInt(4) == 1, credencial)
    }.singleOrNull()

    fun crear(identificador: String, rol: String): Long =
        conn.insertar("INSERT INTO personal(identificador, rol) VALUES (?, ?)", identificador, rol)

    fun guardarCredencial(personalId: Long, credencial: Credencial) {
        conn.ejecutar(
            """
            INSERT INTO personal_credenciales(personal_id, hash, sal, iteraciones) VALUES (?, ?, ?, ?)
            ON CONFLICT(personal_id) DO UPDATE SET hash = excluded.hash, sal = excluded.sal, iteraciones = excluded.iteraciones,
              actualizado_en = strftime('%Y-%m-%dT%H:%M:%fZ','now')
            """.trimIndent(),
            personalId, credencial.hash, credencial.sal, credencial.iteraciones,
        )
    }

    fun crearSesion(tokenHash: String, personalId: Long, horas: Long) {
        conn.ejecutar(
            "INSERT INTO personal_sesiones(token_hash, personal_id, expira_en) VALUES (?, ?, strftime('%Y-%m-%dT%H:%M:%fZ','now', ?))",
            tokenHash, personalId, "+$horas hours",
        )
    }

    /** Solo sesiones vigentes de personal activo. */
    fun sesion(tokenHash: String): PersonalAutenticado? = conn.consultar(
        """
        SELECT p.id, p.identificador, p.rol FROM personal_sesiones s JOIN personal p ON p.id = s.personal_id
        WHERE s.token_hash = ? AND p.activo = 1 AND s.expira_en > strftime('%Y-%m-%dT%H:%M:%fZ','now')
        """.trimIndent(),
        tokenHash,
    ) { PersonalAutenticado(it.getLong(1), it.getString(2), it.getString(3)) }.singleOrNull()

    fun cerrarSesion(tokenHash: String) {
        conn.ejecutar("DELETE FROM personal_sesiones WHERE token_hash = ?", tokenHash)
    }

    fun puestos(): List<Puesto> {
        val accesos = conn.consultar("SELECT id, punto_id, canal, token, activo FROM accesos ORDER BY canal") {
            it.getLong(2) to AccesoPuestoInfo(it.getLong(1), it.getString(3), it.getString(4), it.getInt(5) == 1)
        }.groupBy({ it.first }, { it.second })
        return conn.consultar("SELECT id, codigo, tipo, activo FROM puntos_servicio ORDER BY tipo DESC, codigo") {
            val id = it.getLong(1)
            Puesto(id, it.getString(2), it.getString(3), it.getInt(4) == 1, accesos[id].orEmpty())
        }
    }

    fun crearPuesto(codigo: String, tipo: String, tokenNfc: String, tokenQr: String): Long = conn.enTransaccion {
        val id = conn.insertar("INSERT INTO puntos_servicio(codigo, tipo) VALUES (?, ?)", codigo, tipo)
        conn.ejecutar("INSERT INTO accesos(punto_id, canal, token) VALUES (?, 'nfc', ?)", id, tokenNfc)
        conn.ejecutar("INSERT INTO accesos(punto_id, canal, token) VALUES (?, 'qr', ?)", id, tokenQr)
        id
    }

    fun existePuesto(codigo: String): Boolean =
        conn.consultar("SELECT 1 FROM puntos_servicio WHERE codigo = ?", codigo) { true }.isNotEmpty()

    fun cambiarActivoPuesto(puestoId: Long, activo: Boolean): Int =
        conn.ejecutar("UPDATE puntos_servicio SET activo = ? WHERE id = ?", if (activo) 1 else 0, puestoId)
}
