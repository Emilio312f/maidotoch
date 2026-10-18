package dominio.restricciones

import aplicacion.CandidatosSeguros
import aplicacion.ServicioCandidatosSeguros
import aplicacion.SesionNoDisponible
import datos.RepositorioSeguridadAlimentaria
import dominio.restricciones.EstadoSeguridad.INSEGURO
import dominio.restricciones.EstadoSeguridad.SEGURO
import dominio.restricciones.EstadoSeguridad.SIN_DATO
import dominio.restricciones.ResultadoEvaluacion.DESCONOCIDO
import dominio.restricciones.ResultadoEvaluacion.INCOMPATIBLE
import soporte.BaseDePrueba
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Casos críticos de alergias de punta a punta: base creada desde la migración 001, repositorio,
 * filtro de disponibilidad, Motor de Restricciones y vista v_productos_compatibles. Datos FICTICIOS.
 *
 * Cada prueba de exclusión incluye un producto de control que sí debe salir: sin él, una lista vacía
 * haría pasar la prueba sin demostrar nada.
 */
class CandidatosSegurosTest {
    private val db = BaseDePrueba()
    private val servicio = ServicioCandidatosSeguros(RepositorioSeguridadAlimentaria(db.conn))

    private val mani = db.alergeno("FICTICIO-ALG-mani")
    private val alergiaMani = db.restriccionAlergia("FICTICIO-RES-alergia-mani", mani)
    private val vegana = db.restriccionDieta("FICTICIO-RES-vegana")

    @AfterTest
    fun cerrar() = db.close()

    private fun control() = db.producto { compatibleCon(alergiaMani, vegana) }

    private fun CandidatosSeguros.ids() = seguros.map { it.productoId }.toSet()

    private fun CandidatosSeguros.estadoDe(productoId: Long) = evaluaciones.find { it.productoId == productoId }?.estado

    private fun assertExcluido(resultado: CandidatosSeguros, productoId: Long, estado: EstadoSeguridad, control: Long) {
        assertFalse(productoId in resultado.ids(), "El producto $productoId no debe ser candidato")
        assertEquals(estado, resultado.estadoDe(productoId))
        assertTrue(control in resultado.ids(), "El producto de control debe seguir siendo candidato")
        assertTrue(resultado.discrepancias.isEmpty(), "Motor y vista deben coincidir: ${resultado.discrepancias}")
    }

    @Test
    fun `producto compatible disponible y validado es candidato con su revision vigente`() {
        val producto = control()
        val sesion = db.sesion(alergiaMani, vegana)

        val resultado = servicio.calcular(sesion)

        assertEquals(setOf(producto), resultado.ids())
        assertEquals(db.revisionProducto(producto), resultado.seguros.single().revisionProducto)
        assertEquals(db.revisionPerfil(sesion), resultado.revisionPerfil)
        assertEquals(SEGURO, resultado.estadoDe(producto))
        assertTrue(resultado.discrepancias.isEmpty())
    }

    @Test
    fun `alergeno declarado como contiene se excluye aunque la evaluacion diga compatible`() {
        val control = control()
        val producto = db.producto { contiene(mani); compatibleCon(alergiaMani) }

        assertExcluido(servicio.calcular(db.sesion(alergiaMani)), producto, INSEGURO, control)
    }

    @Test
    fun `alergeno solo en trazas se excluye`() {
        val control = control()
        val producto = db.producto { trazas(mani); compatibleCon(alergiaMani) }

        assertExcluido(servicio.calcular(db.sesion(alergiaMani)), producto, INSEGURO, control)
    }

    @Test
    fun `alergeno solo via ingrediente se excluye`() {
        val control = control()
        val salsa = db.ingrediente("FICTICIO-ING-salsa", mani)
        val producto = db.producto { ingrediente(salsa); compatibleCon(alergiaMani) }

        assertExcluido(servicio.calcular(db.sesion(alergiaMani)), producto, INSEGURO, control)
    }

    @Test
    fun `sin fila en producto_restricciones para una restriccion de la sesion se excluye`() {
        val control = control()
        val producto = db.producto { compatibleCon(vegana) }

        assertExcluido(servicio.calcular(db.sesion(alergiaMani, vegana)), producto, SIN_DATO, control)
    }

    @Test
    fun `evaluacion desconocida se excluye como sin dato`() {
        val control = control()
        val producto = db.producto { evaluacion(alergiaMani, DESCONOCIDO) }

        assertExcluido(servicio.calcular(db.sesion(alergiaMani)), producto, SIN_DATO, control)
    }

    @Test
    fun `evaluacion incompatible se excluye como inseguro`() {
        val control = control()
        val producto = db.producto { evaluacion(alergiaMani, INCOMPATIBLE) }

        assertExcluido(servicio.calcular(db.sesion(alergiaMani)), producto, INSEGURO, control)
    }

    @Test
    fun `dieta sin evaluacion compatible se excluye`() {
        val control = control()
        val producto = db.producto { compatibleCon(alergiaMani) }

        assertExcluido(servicio.calcular(db.sesion(vegana)), producto, SIN_DATO, control)
    }

    @Test
    fun `producto no disponible o inactivo no llega al motor`() {
        val control = control()
        val noDisponible = db.producto { disponible = false; compatibleCon(alergiaMani) }
        val inactivo = db.producto { activo = false; compatibleCon(alergiaMani) }

        val resultado = servicio.calcular(db.sesion(alergiaMani))

        assertEquals(setOf(control), resultado.ids())
        assertNull(resultado.estadoDe(noDisponible))
        assertNull(resultado.estadoDe(inactivo))
    }

    @Test
    fun `producto desactivado desde el back-office deja de ser candidato`() {
        val producto = control()
        val sesion = db.sesion(alergiaMani)
        assertTrue(producto in servicio.calcular(sesion).ids())

        db.cambiarDisponibilidad(producto, false)

        assertFalse(producto in servicio.calcular(sesion).ids())
    }

    @Test
    fun `producto no validado se excluye como sin dato`() {
        val control = control()
        val producto = db.producto { validado = false; compatibleCon(alergiaMani) }

        assertExcluido(servicio.calcular(db.sesion(alergiaMani)), producto, SIN_DATO, control)
    }

    @Test
    fun `producto modificado despues de validar deja de recomendarse`() {
        val control = control()
        val producto = control()
        val sesion = db.sesion(alergiaMani)
        assertTrue(producto in servicio.calcular(sesion).ids())

        val nuevo = db.ingrediente("FICTICIO-ING-nuevo")
        db.insertar("INSERT INTO producto_ingredientes(producto_id, ingrediente_id) VALUES (?, ?)", producto, nuevo)

        assertExcluido(servicio.calcular(sesion), producto, SIN_DATO, control)
    }

    @Test
    fun `alergeno agregado a un ingrediente ya usado excluye el producto`() {
        val control = control()
        val base = db.ingrediente("FICTICIO-ING-base")
        val producto = db.producto { ingrediente(base); compatibleCon(alergiaMani) }
        val sesion = db.sesion(alergiaMani)
        assertTrue(producto in servicio.calcular(sesion).ids())

        db.insertar("INSERT INTO ingrediente_alergenos(ingrediente_id, alergeno_id) VALUES (?, ?)", base, mani)

        assertExcluido(servicio.calcular(sesion), producto, INSEGURO, control)
    }

    @Test
    fun `sin candidatos seguros no se recomienda nada`() {
        db.producto { contiene(mani); compatibleCon(alergiaMani) }
        db.producto { evaluacion(alergiaMani, DESCONOCIDO) }

        val resultado = servicio.calcular(db.sesion(alergiaMani))

        assertTrue(resultado.sinCandidatos)
        assertTrue(resultado.seguros.isEmpty())
        assertEquals(1, resultado.sinDato.size)
    }

    @Test
    fun `sesion sin perfil completo o cerrada no recibe candidatos`() {
        control()
        val incompleta = db.sesion(alergiaMani, perfilCompleto = false)
        val cerrada = db.sesion(alergiaMani)
        db.ejecutar("UPDATE sesiones SET cierre = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?", cerrada)

        assertFailsWith<SesionNoDisponible> { servicio.calcular(incompleta) }
        assertFailsWith<SesionNoDisponible> { servicio.calcular(cerrada) }
        assertFailsWith<SesionNoDisponible> { servicio.calcular("FICTICIO-no-existe") }
    }
}
