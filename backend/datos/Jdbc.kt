package datos

import java.sql.Connection
import java.sql.ResultSet

fun <T> Connection.consultar(sql: String, vararg parametros: Any?, fila: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        parametros.forEachIndexed { i, valor -> st.setObject(i + 1, valor) }
        st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(fila(rs)) }
        }
    }

/** Ejecuta [bloque] dentro de una transacción de solo lectura para obtener una instantánea coherente. */
fun <T> Connection.leerEnTransaccion(bloque: () -> T): T {
    val autoCommitPrevio = autoCommit
    autoCommit = false
    try {
        return bloque()
    } finally {
        rollback()
        autoCommit = autoCommitPrevio
    }
}
