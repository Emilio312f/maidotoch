package datos

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Aplica en orden los archivos `NNN_descripcion.sql` cuyo número supera `PRAGMA user_version`.
 * Cada migración debe terminar fijando `PRAGMA user_version = NNN`. Las migraciones nunca se editan
 * después de aplicadas; antes de migrar en producción se respalda el archivo SQLite.
 */
object Migraciones {
    private val patron = Regex("""^(\d{3})_.+\.sql$""")

    fun aplicar(conn: Connection, directorio: Path) {
        val actual = versionActual(conn)
        val pendientes = Files.list(directorio).use { it.toList() }
            .mapNotNull { archivo -> patron.matchEntire(archivo.fileName.toString())?.let { it.groupValues[1].toInt() to archivo } }
            .sortedBy { it.first }
            .filter { it.first > actual }

        for ((numero, archivo) in pendientes) {
            conn.createStatement().use { it.executeUpdate(Files.readString(archivo)) }
            check(versionActual(conn) == numero) {
                "La migración ${archivo.fileName} debe fijar PRAGMA user_version = $numero"
            }
        }
        BaseDatos.verificarClavesForaneas(conn)
    }

    fun versionActual(conn: Connection): Int = conn.consultar("PRAGMA user_version") { it.getInt(1) }.single()
}
