package dominio.restricciones

import dominio.restricciones.EstadoSeguridad.INSEGURO
import dominio.restricciones.EstadoSeguridad.SEGURO
import dominio.restricciones.EstadoSeguridad.SIN_DATO
import dominio.restricciones.OrigenAlergeno.CONTIENE
import dominio.restricciones.OrigenAlergeno.TRAZAS
import dominio.restricciones.ResultadoEvaluacion.COMPATIBLE
import dominio.restricciones.ResultadoEvaluacion.DESCONOCIDO
import dominio.restricciones.ResultadoEvaluacion.INCOMPATIBLE
import dominio.restricciones.TipoMotivo.ALERGENO_CONTIENE
import dominio.restricciones.TipoMotivo.ALERGENO_TRAZAS
import dominio.restricciones.TipoMotivo.ALERGENO_VIA_INGREDIENTE
import dominio.restricciones.TipoMotivo.EVALUACION_DESCONOCIDA
import dominio.restricciones.TipoMotivo.EVALUACION_INCOMPATIBLE
import dominio.restricciones.TipoMotivo.PRODUCTO_NO_VALIDADO
import dominio.restricciones.TipoMotivo.SIN_EVALUACION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Casos críticos de alergias sobre el motor puro, sin base de datos. Datos FICTICIOS. */
class MotorRestriccionesTest {
    private val motor = MotorRestricciones()

    private val mani = 1L
    private val alergiaMani = Restriccion(10, TipoRestriccion.ALERGIA, mani)
    private val vegana = Restriccion(20, TipoRestriccion.DIETA, null)

    private fun producto(
        validado: Boolean = true,
        declarados: Map<Long, Set<OrigenAlergeno>> = emptyMap(),
        viaIngredientes: Set<Long> = emptySet(),
        evaluaciones: Map<Long, ResultadoEvaluacion> = mapOf(alergiaMani.id to COMPATIBLE, vegana.id to COMPATIBLE),
    ) = DatosSeguridadProducto(1, validado, declarados, viaIngredientes, evaluaciones)

    @Test
    fun `producto validado y compatible con todas las restricciones es seguro`() {
        val resultado = motor.evaluar(producto(), listOf(alergiaMani, vegana))

        assertEquals(SEGURO, resultado.estado)
        assertTrue(resultado.motivos.isEmpty())
    }

    @Test
    fun `sin restricciones declaradas un producto validado es seguro`() {
        assertEquals(SEGURO, motor.evaluar(producto(evaluaciones = emptyMap()), emptyList()).estado)
    }

    @Test
    fun `alergeno declarado como contiene es inseguro aunque la evaluacion diga compatible`() {
        val resultado = motor.evaluar(producto(declarados = mapOf(mani to setOf(CONTIENE))), listOf(alergiaMani))

        assertEquals(INSEGURO, resultado.estado)
        assertEquals(listOf(Motivo(ALERGENO_CONTIENE, alergiaMani.id)), resultado.motivos)
    }

    @Test
    fun `alergeno solo en trazas es inseguro`() {
        val resultado = motor.evaluar(producto(declarados = mapOf(mani to setOf(TRAZAS))), listOf(alergiaMani))

        assertEquals(INSEGURO, resultado.estado)
        assertEquals(listOf(Motivo(ALERGENO_TRAZAS, alergiaMani.id)), resultado.motivos)
    }

    @Test
    fun `alergeno solo via ingrediente es inseguro`() {
        val resultado = motor.evaluar(producto(viaIngredientes = setOf(mani)), listOf(alergiaMani))

        assertEquals(INSEGURO, resultado.estado)
        assertEquals(listOf(Motivo(ALERGENO_VIA_INGREDIENTE, alergiaMani.id)), resultado.motivos)
    }

    @Test
    fun `alergeno de otra restriccion no afecta`() {
        val otroAlergeno = 2L
        val resultado = motor.evaluar(producto(declarados = mapOf(otroAlergeno to setOf(CONTIENE))), listOf(alergiaMani))

        assertEquals(SEGURO, resultado.estado)
    }

    @Test
    fun `sin fila de evaluacion para una restriccion queda sin dato`() {
        val resultado = motor.evaluar(producto(evaluaciones = mapOf(vegana.id to COMPATIBLE)), listOf(alergiaMani, vegana))

        assertEquals(SIN_DATO, resultado.estado)
        assertEquals(listOf(Motivo(SIN_EVALUACION, alergiaMani.id)), resultado.motivos)
    }

    @Test
    fun `evaluacion desconocida queda sin dato`() {
        val resultado = motor.evaluar(producto(evaluaciones = mapOf(alergiaMani.id to DESCONOCIDO)), listOf(alergiaMani))

        assertEquals(SIN_DATO, resultado.estado)
        assertEquals(listOf(Motivo(EVALUACION_DESCONOCIDA, alergiaMani.id)), resultado.motivos)
    }

    @Test
    fun `evaluacion incompatible es inseguro`() {
        val resultado = motor.evaluar(producto(evaluaciones = mapOf(alergiaMani.id to INCOMPATIBLE)), listOf(alergiaMani))

        assertEquals(INSEGURO, resultado.estado)
        assertEquals(listOf(Motivo(EVALUACION_INCOMPATIBLE, alergiaMani.id)), resultado.motivos)
    }

    @Test
    fun `dieta sin evaluacion compatible queda sin dato`() {
        val resultado = motor.evaluar(producto(evaluaciones = mapOf(alergiaMani.id to COMPATIBLE)), listOf(vegana))

        assertEquals(SIN_DATO, resultado.estado)
        assertEquals(listOf(Motivo(SIN_EVALUACION, vegana.id)), resultado.motivos)
    }

    @Test
    fun `dieta evaluada incompatible es inseguro`() {
        val resultado = motor.evaluar(producto(evaluaciones = mapOf(vegana.id to INCOMPATIBLE)), listOf(vegana))

        assertEquals(INSEGURO, resultado.estado)
    }

    @Test
    fun `producto no validado queda sin dato aunque no haya restricciones`() {
        val resultado = motor.evaluar(producto(validado = false), emptyList())

        assertEquals(SIN_DATO, resultado.estado)
        assertEquals(listOf(Motivo(PRODUCTO_NO_VALIDADO)), resultado.motivos)
    }

    @Test
    fun `inseguro prevalece sobre sin dato`() {
        val resultado = motor.evaluar(
            producto(validado = false, declarados = mapOf(mani to setOf(TRAZAS)), evaluaciones = emptyMap()),
            listOf(alergiaMani, vegana),
        )

        assertEquals(INSEGURO, resultado.estado)
    }

    @Test
    fun `una restriccion de alergia exige alergeno y una dieta no lo admite`() {
        assertFailsWith<IllegalArgumentException> { Restriccion(1, TipoRestriccion.ALERGIA, null) }
        assertFailsWith<IllegalArgumentException> { Restriccion(1, TipoRestriccion.DIETA, mani) }
    }
}
