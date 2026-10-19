package integracion

import comun.datos.BaseDatos
import comun.datos.consultar
import soporte.BaseDePrueba
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Comportamientos de la migración 001 de los que depende el código: se verifican, no se
 * reimplementan. Datos FICTICIOS.
 */
class TriggersEsquemaTest {
    private val db = BaseDePrueba()
    private val mani = db.alergeno("FICTICIO-ALG-mani")
    private val alergiaMani = db.restriccionAlergia("FICTICIO-RES-alergia-mani", mani)
    private val vegana = db.restriccionDieta("FICTICIO-RES-vegana")

    @AfterTest
    fun cerrar() = db.close()

    private fun recomendar(sesion: String, producto: Long, revisionProducto: Long, revisionPerfil: Long) = db.insertar(
        """
        INSERT INTO recomendaciones(sesion_id, producto_id, revision_producto, revision_perfil, posicion, explicacion, motor, version_reglas)
        VALUES (?, ?, ?, ?, 1, 'FICTICIO: explicación de prueba', 'fallback', 'prueba')
        """.trimIndent(),
        sesion, producto, revisionProducto, revisionPerfil,
    )

    @Test
    fun `cada conexion se abre con claves foraneas activas`() {
        BaseDatos.conectar("jdbc:sqlite::memory:").use { conn ->
            assertEquals(1, conn.consultar("PRAGMA foreign_keys") { it.getInt(1) }.single())
        }
        assertFailsWith<SQLException> {
            db.insertar("INSERT INTO accesos(punto_id, canal, token) VALUES (999999, 'qr', 'FICTICIO-huerfano')")
        }
    }

    @Test
    fun `recomendacion con revisiones vigentes se acepta`() {
        val producto = db.producto { compatibleCon(alergiaMani) }
        val sesion = db.sesion(alergiaMani)

        recomendar(sesion, producto, db.revisionProducto(producto), db.revisionPerfil(sesion))
    }

    @Test
    fun `recomendacion con revision de producto vieja es rechazada por la base`() {
        val producto = db.producto { compatibleCon(alergiaMani) }
        val sesion = db.sesion(alergiaMani)
        val revisionVieja = db.revisionProducto(producto)

        db.insertar(
            "INSERT INTO producto_ingredientes(producto_id, ingrediente_id) VALUES (?, ?)",
            producto, db.ingrediente("FICTICIO-ING-nuevo"),
        )
        db.validar(producto)

        val error = assertFailsWith<SQLException> {
            recomendar(sesion, producto, revisionVieja, db.revisionPerfil(sesion))
        }
        assertTrue("Recomendacion bloqueada" in error.message.orEmpty())
        recomendar(sesion, producto, db.revisionProducto(producto), db.revisionPerfil(sesion))
    }

    @Test
    fun `recomendacion con revision de perfil vieja es rechazada por la base`() {
        val producto = db.producto { compatibleCon(alergiaMani, vegana) }
        val sesion = db.sesion(alergiaMani)
        val perfilViejo = db.revisionPerfil(sesion)

        db.insertar("INSERT INTO sesion_restricciones(sesion_id, restriccion_id) VALUES (?, ?)", sesion, vegana)

        assertFailsWith<SQLException> { recomendar(sesion, producto, db.revisionProducto(producto), perfilViejo) }
    }

    private fun agregarItem(pedido: String, sesion: String, producto: Long, destino: String) {
        val precio = db.conn.consultar("SELECT precio_centimos FROM productos WHERE id = ?", producto) { it.getLong(1) }.single()
        db.insertar(
            """
            INSERT INTO pedido_items(pedido_id, sesion_id, producto_id, cantidad, precio_unitario_centimos, nombre_snapshot, destino)
            VALUES (?, ?, ?, 1, ?, ?, ?)
            """.trimIndent(),
            pedido, sesion, producto, precio, db.nombre(producto, "es"), destino,
        )
    }

    private fun confirmar(pedido: String) = db.ejecutar(
        "UPDATE pedidos SET estado = 'confirmado', confirmado_en = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?",
        pedido,
    )

    private fun destinosDeComandas(pedido: String) =
        db.conn.consultar("SELECT destino FROM comandas WHERE pedido_id = ? ORDER BY destino", pedido) { it.getString(1) }

    @Test
    fun `pedido en borrador no genera comandas y al confirmarlo se crea una por destino`() {
        val plato1 = db.producto { compatibleCon(alergiaMani) }
        val plato2 = db.producto { compatibleCon(alergiaMani) }
        val bebida = db.producto { destino = "barra"; compatibleCon(alergiaMani) }
        val sesion = db.sesion(alergiaMani)
        db.insertar("INSERT INTO pedidos(id, sesion_id) VALUES ('FICTICIO-pedido-1', ?)", sesion)
        agregarItem("FICTICIO-pedido-1", sesion, plato1, "cocina")
        agregarItem("FICTICIO-pedido-1", sesion, plato2, "cocina")
        agregarItem("FICTICIO-pedido-1", sesion, bebida, "barra")

        assertEquals(emptyList(), destinosDeComandas("FICTICIO-pedido-1"))

        confirmar("FICTICIO-pedido-1")

        assertEquals(listOf("barra", "cocina"), destinosDeComandas("FICTICIO-pedido-1"))
    }

    @Test
    fun `pedido con un producto que dejo de estar disponible no se confirma`() {
        val plato = db.producto { compatibleCon(alergiaMani) }
        val sesion = db.sesion(alergiaMani)
        db.insertar("INSERT INTO pedidos(id, sesion_id) VALUES ('FICTICIO-pedido-2', ?)", sesion)
        agregarItem("FICTICIO-pedido-2", sesion, plato, "cocina")

        db.cambiarDisponibilidad(plato, false)

        assertFailsWith<SQLException> { confirmar("FICTICIO-pedido-2") }
        assertEquals(emptyList(), destinosDeComandas("FICTICIO-pedido-2"))
    }
}
