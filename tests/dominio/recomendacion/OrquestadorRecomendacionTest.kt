package dominio.recomendacion

import aplicacion.OrquestadorRecomendacion
import configuracion.Configuracion
import datos.consultar
import dominio.restricciones.ResultadoEvaluacion.DESCONOCIDO
import integracion.OrdenadorProtegido
import integracion.PayloadIA
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import presentacion.Aplicacion
import soporte.BaseDePrueba
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Proveedor de IA falso: registra lo que recibe y responde lo que la prueba indique. */
class IaFalsa(var responder: suspend (SolicitudOrdenamiento) -> List<Long> = { s -> s.candidatos.map { it.id } }) : OrdenadorExterno {
    val solicitudes = mutableListOf<SolicitudOrdenamiento>()

    override suspend fun ordenar(solicitud: SolicitudOrdenamiento): List<Long> {
        solicitudes += solicitud
        return responder(solicitud)
    }
}

/**
 * Casos críticos del asistente (CLAUDE.md §10): la IA solo ve la lista segura, sus ids inventados se
 * descartan, el timeout activa el fallback y el DTO no lleva datos sensibles. Datos FICTICIOS.
 */
class OrquestadorRecomendacionTest {
    private val db = BaseDePrueba()
    private val mani = db.alergeno("FICTICIO-ALG-mani")
    private val alergiaMani = db.restriccionAlergia("FICTICIO-RES-alergia-mani", mani)
    private val picante = db.preferencia("FICTICIO-PREF-picante")
    private val salsaMani = db.ingrediente("FICTICIO-ING-salsa-mani", mani)

    private val seguro = db.producto { etiqueta(picante); compatibleCon(alergiaMani) }
    private val otroSeguro = db.producto { compatibleCon(alergiaMani) }
    private val inseguro = db.producto { contiene(mani); etiqueta(picante); compatibleCon(alergiaMani) }
    private val viaIngrediente = db.producto { ingrediente(salsaMani); compatibleCon(alergiaMani) }
    private val sinDato = db.producto { evaluacion(alergiaMani, DESCONOCIDO) }

    private val sesion = db.sesion(alergiaMani)

    @AfterTest
    fun cerrar() = db.close()

    private fun orquestador(ia: OrdenadorExterno?, limite: Int = 10) = OrquestadorRecomendacion(db.fuente, ia, 3, limite)

    private fun filasRecomendaciones() = db.conn.consultar(
        "SELECT producto_id, motor, revision_producto, revision_perfil, version_reglas FROM recomendaciones ORDER BY posicion",
    ) { listOf(it.getLong(1), it.getString(2), it.getLong(3), it.getLong(4), it.getString(5)) }

    @Test
    fun `la IA solo recibe candidatos ya declarados seguros`() = runBlocking<Unit> {
        val ia = IaFalsa()

        orquestador(ia).recomendar(sesion, "algo picante")

        assertEquals(setOf(seguro, otroSeguro), ia.solicitudes.single().candidatos.map { it.id }.toSet())
    }

    @Test
    fun `un id devuelto por la IA que no estaba en la lista enviada se descarta`() = runBlocking<Unit> {
        val ia = IaFalsa { listOf(inseguro, 999_999, seguro, viaIngrediente, seguro) }

        val respuesta = orquestador(ia).recomendar(sesion, "algo picante")

        assertEquals("ia", respuesta.motor)
        assertEquals(listOf(seguro), respuesta.recomendaciones.map { it.productoId })
        assertEquals(listOf(seguro), filasRecomendaciones().map { it[0] })
    }

    @Test
    fun `si la IA solo devuelve ids invalidos se usa el ranking por reglas`() = runBlocking<Unit> {
        val respuesta = orquestador(IaFalsa { listOf(inseguro, 123_456) }).recomendar(sesion, "algo picante")

        assertEquals("fallback", respuesta.motor)
        // Solo el plato seguro que coincide con «picante»; el otro seguro no tiene relación con la consulta.
        assertEquals(listOf(seguro), respuesta.recomendaciones.map { it.productoId })
    }

    @Test
    fun `si la IA no responde a tiempo se usa el fallback y el comensal recibe recomendacion`() = runBlocking<Unit> {
        val lenta = IaFalsa { delay(10_000); listOf(seguro) }
        val inicio = System.currentTimeMillis()

        val respuesta = orquestador(OrdenadorProtegido(lenta, timeoutMs = 200)).recomendar(sesion, "algo picante")

        assertTrue(System.currentTimeMillis() - inicio < 3_000, "No debe esperar a la IA más allá del timeout")
        assertEquals("fallback", respuesta.motor)
        assertTrue(respuesta.recomendaciones.isNotEmpty())
        assertEquals("fallback", filasRecomendaciones().first()[1])
    }

    @Test
    fun `si la IA falla se usa el fallback`() = runBlocking<Unit> {
        val respuesta = orquestador(IaFalsa { error("proveedor caído") }).recomendar(sesion, "algo picante")

        assertEquals("fallback", respuesta.motor)
        assertEquals(seguro, respuesta.recomendaciones.first().productoId)
    }

    @Test
    fun `el timeout por defecto del proveedor es de 5 segundos`() {
        assertEquals(5_000, Configuracion().iaTimeoutMs)
        val app = Aplicacion(Configuracion(directorioFrontend = null), db.fuente, IaFalsa())
        assertEquals(5_000, app.ordenador!!.timeoutMs)
    }

    @Test
    fun `sin candidatos seguros no se recomienda nada, no se llama a la IA y se ofrece el mozo`() = runBlocking<Unit> {
        val ia = IaFalsa()
        val sesionSinOpciones = db.sesion(alergiaMani)
        db.cambiarDisponibilidad(seguro, false)
        db.cambiarDisponibilidad(otroSeguro, false)

        val respuesta = orquestador(ia).recomendar(sesionSinOpciones, "algo picante")

        assertTrue(respuesta.sinCandidatos)
        assertTrue(respuesta.recomendaciones.isEmpty())
        assertEquals("restriccion_incierta", respuesta.derivacion?.motivo)
        assertTrue(ia.solicitudes.isEmpty())
        assertTrue(filasRecomendaciones().isEmpty())
    }

    @Test
    fun `el DTO enviado a la IA no contiene alergenos, ingredientes, restricciones ni id de sesion`() = runBlocking<Unit> {
        val ia = IaFalsa()
        orquestador(ia).recomendar(sesion, "algo picante")
        val payload = PayloadIA.json(ia.solicitudes.single())

        val json = Json.parseToJsonElement(payload).jsonObject
        assertEquals(setOf("consulta", "candidatos"), json.keys)
        json.getValue("candidatos").jsonArray.forEach {
            assertEquals(setOf("id", "nombre", "descripcion", "etiquetas"), it.jsonObject.keys)
        }
        listOf(sesion, "FICTICIO-ALG-mani", "FICTICIO-RES-alergia-mani", "FICTICIO-ING-salsa-mani", "alergia", "alerg").forEach {
            assertFalse(it in payload, "El DTO no debe contener «$it»")
        }
        val camposSolicitud = SolicitudOrdenamiento::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("consulta", "maximo", "candidatos"), camposSolicitud)
        assertEquals(setOf("id", "nombre", "descripcion", "etiquetas"), CandidatoParaIA::class.java.declaredFields.map { it.name }.toSet())
    }

    @Test
    fun `al agotar el limite de consultas con IA de la sesion se usa el fallback sin llamar al proveedor`() = runBlocking<Unit> {
        val ia = IaFalsa()
        val orquestador = orquestador(ia, limite = 1)

        assertEquals("ia", orquestador.recomendar(sesion, "algo picante").motor)
        assertEquals("fallback", orquestador.recomendar(sesion, "otra cosa picante").motor)
        assertEquals(1, ia.solicitudes.size)
    }

    @Test
    fun `una consulta vacia no llama a la IA`() = runBlocking<Unit> {
        val ia = IaFalsa()

        val respuesta = orquestador(ia).recomendar(sesion, "   ")

        assertEquals("fallback", respuesta.motor)
        assertTrue(ia.solicitudes.isEmpty())
    }

    @Test
    fun `cada recomendacion se guarda con revisiones vigentes, motor y version de reglas, y queda como mostrada`() = runBlocking<Unit> {
        val respuesta = orquestador(IaFalsa { listOf(seguro) }).recomendar(sesion, "algo picante")

        val fila = filasRecomendaciones().single()
        assertEquals(listOf(seguro, "ia", db.revisionProducto(seguro), db.revisionPerfil(sesion), "restricciones-v1"), fila)
        val eventos = db.conn.consultar("SELECT accion FROM recomendacion_eventos WHERE recomendacion_id = ?", respuesta.recomendaciones.single().recomendacionId) { it.getString(1) }
        assertEquals(listOf("mostrada"), eventos)
        val consultas = db.conn.consultar("SELECT motor, recomendaciones FROM consultas_asistente") { it.getString(1) to it.getInt(2) }
        assertEquals(listOf("ia" to 1), consultas)
    }

    @Test
    fun `si el producto cambia mientras se consulta a la IA se recalcula sin forzar el insert`() = runBlocking<Unit> {
        var primera = true
        val ia = IaFalsa { solicitud ->
            if (primera) {
                primera = false
                // Cocina modifica el plato mientras la IA responde: pierde la validación.
                db.insertar("INSERT INTO producto_ingredientes(producto_id, ingrediente_id) VALUES (?, ?)", seguro, db.ingrediente("FICTICIO-ING-nuevo"))
            }
            solicitud.candidatos.map { it.id }
        }

        val respuesta = orquestador(ia).recomendar(sesion, "algo picante")

        assertEquals(2, ia.solicitudes.size)
        assertFalse(seguro in respuesta.recomendaciones.map { it.productoId })
        assertEquals(listOf(otroSeguro), filasRecomendaciones().map { it[0] })
    }

    @Test
    fun `la explicacion usa preferencias declaradas y restricciones en el idioma de la sesion`() = runBlocking<Unit> {
        val enIngles = db.sesion(alergiaMani, idioma = "en")
        db.declararPreferencias(enIngles, picante)

        val respuesta = orquestador(null).recomendar(enIngles, "")

        val explicacion = respuesta.recomendaciones.first { it.productoId == seguro }.explicacion
        assertTrue("Matches your preferences: FICTICIO-PREF-picante-en" in explicacion, explicacion)
        assertTrue("Checked by the kitchen as compatible with: FICTICIO-RES-alergia-mani" in explicacion, explicacion)
    }

    @Test
    fun `se ofrece derivar al mozo si la consulta menciona un plato sin dato suficiente`() = runBlocking<Unit> {
        db.ejecutar("UPDATE producto_textos SET nombre = 'Tiradito ficticio' WHERE producto_id = ? AND idioma = 'es'", sinDato)

        val respuesta = orquestador(null).recomendar(sesion, "quiero el tiradito")

        assertEquals("restriccion_incierta", respuesta.derivacion?.motivo)
        assertEquals(listOf("Tiradito ficticio"), respuesta.derivacion?.productos)
        assertNull(respuesta.recomendaciones.find { it.productoId == sinDato })
    }
}
