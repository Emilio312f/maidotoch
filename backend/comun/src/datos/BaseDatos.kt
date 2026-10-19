package comun.datos

import java.sql.Connection
import java.sql.DriverManager

object BaseDatos {
    /** Toda conexión a SQLite pasa por aquí: activa las claves foráneas y comprueba que quedaron activas. */
    fun conectar(url: String): Connection {
        val conn = DriverManager.getConnection(url)
        try {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            verificarClavesForaneas(conn)
        } catch (e: Exception) {
            conn.close()
            throw e
        }
        return conn
    }

    fun verificarClavesForaneas(conn: Connection) {
        val activas = conn.consultar("PRAGMA foreign_keys") { it.getInt(1) }.single()
        check(activas == 1) { "SQLite debe operar con foreign_keys = ON" }
    }
}
