package integracion

import comun.SolicitudInvalida
import comun.datos.consultar
import soporte.Escenario
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Carga del catálogo FICTICIO: idempotente y con invalidación de lo que cambia. */
class ImportadorCatalogoTest {
    private val e = Escenario()
    private val archivo = File(System.getProperty("maidotouch.catalogoFicticio") ?: "db/catalogo/ficticio.json").readText()

    @AfterTest
    fun cerrar() = e.close()

    private fun revisiones() = e.db.conn.consultar("SELECT codigo, revision, validado_en IS NOT NULL FROM productos ORDER BY codigo") {
        Triple(it.getString(1), it.getLong(2), it.getInt(3) == 1)
    }

    @Test
    fun `importar de nuevo el mismo archivo no cambia nada ni invalida productos`() {
        val antes = revisiones()

        val resumen = e.app.importador.importar(archivo, "cocina-prueba")

        assertEquals(8, resumen.productosSinCambios)
        assertEquals(0, resumen.productosNuevos + resumen.productosModificados)
        assertTrue(resumen.productosPorValidar.isEmpty())
        assertEquals(antes, revisiones())
    }

    @Test
    fun `un cambio de receta invalida solo ese producto hasta que cocina lo revalide`() {
        val modificado = archivo.replace(
            "\"ingredientes\": [\"FICTICIO-quinua\", \"FICTICIO-palta\", \"FICTICIO-limon\"]",
            "\"ingredientes\": [\"FICTICIO-quinua\", \"FICTICIO-palta\"]",
        )
        check(modificado != archivo)

        val resumen = e.app.importador.importar(modificado, "cocina-prueba")

        assertEquals(1, resumen.productosModificados)
        assertEquals(listOf("FICTICIO-P04"), resumen.productosPorValidar)
        val sesion = e.sesion()
        val aptitud = e.app.catalogo.cartaParaSesion(sesion).productos.map { it.producto.id }
        assertFalse(e.producto("FICTICIO-P04") in aptitud, "Un producto sin validar no aparece en la carta")
    }

    @Test
    fun `un alergeno nuevo en un ingrediente invalida los productos que lo usan`() {
        val modificado = archivo.replace(
            "{ \"codigo\": \"FICTICIO-palta\", \"nombre_es\": \"Palta\", \"nombre_en\": \"Avocado\", \"alergenos\": [] }",
            "{ \"codigo\": \"FICTICIO-palta\", \"nombre_es\": \"Palta\", \"nombre_en\": \"Avocado\", \"alergenos\": [\"FICTICIO-mani\"] }",
        )
        check(modificado != archivo)

        val resumen = e.app.importador.importar(modificado, "cocina-prueba")

        assertEquals(listOf("FICTICIO-P04"), resumen.productosPorValidar)
    }

    @Test
    fun `solo cocina o administracion pueden figurar como evaluador`() {
        assertFailsWith<SolicitudInvalida> { e.app.importador.importar(archivo, "sala-prueba") }
        assertFailsWith<SolicitudInvalida> { e.app.importador.importar(archivo, "no-existe") }
    }

    @Test
    fun `un producto sin textos en ambos idiomas se rechaza sin cargar nada`() {
        val incompleto = """
            { "productos": [ { "codigo": "FICTICIO-X", "categoria": "FICTICIO-entradas", "tipo": "plato", "destino": "cocina",
              "precio_centimos": 100, "textos": { "es": { "nombre": "x", "descripcion": "x" } }, "ingredientes": ["FICTICIO-limon"] } ] }
        """.trimIndent()

        assertFailsWith<SolicitudInvalida> { e.app.importador.importar(incompleto, "cocina-prueba") }
        assertEquals(0, e.db.conn.consultar("SELECT count(*) FROM productos WHERE codigo = 'FICTICIO-X'") { it.getInt(1) }.single())
    }
}
