package comun.datos

import java.sql.Connection

/** Entrega conexiones a SQLite; todas pasan por [BaseDatos.conectar], con claves foráneas activas. */
interface FuenteConexiones {
    fun <T> usar(bloque: (Connection) -> T): T
}

/** Una conexión por operación sobre el archivo SQLite del servidor. */
class ArchivoSqlite(ruta: String) : FuenteConexiones {
    private val url = "jdbc:sqlite:$ruta"

    override fun <T> usar(bloque: (Connection) -> T): T =
        BaseDatos.conectar(url).use { conn ->
            conn.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            bloque(conn)
        }

    /** WAL permite lecturas concurrentes mientras se escribe; se fija una vez por archivo. */
    fun activarWal() = usar { conn -> conn.createStatement().use { it.execute("PRAGMA journal_mode = WAL") } }
}

/** Comparte una única conexión (pruebas con base en memoria). */
class ConexionUnica(private val conn: Connection) : FuenteConexiones {
    override fun <T> usar(bloque: (Connection) -> T): T = synchronized(this) { bloque(conn) }
}

/** Ejecuta [bloque] en una transacción: confirma si termina bien y revierte si lanza. */
fun <T> Connection.enTransaccion(bloque: () -> T): T {
    val autoCommitPrevio = autoCommit
    autoCommit = false
    try {
        val resultado = bloque()
        commit()
        return resultado
    } catch (e: Throwable) {
        rollback()
        throw e
    } finally {
        autoCommit = autoCommitPrevio
    }
}

fun Connection.ejecutar(sql: String, vararg parametros: Any?): Int =
    prepareStatement(sql).use { st ->
        parametros.forEachIndexed { i, valor -> st.setObject(i + 1, valor) }
        st.executeUpdate()
    }

fun Connection.insertar(sql: String, vararg parametros: Any?): Long {
    ejecutar(sql, *parametros)
    return consultar("SELECT last_insert_rowid()") { it.getLong(1) }.single()
}

fun ahoraUtc(): String = java.time.Instant.now().toString()
