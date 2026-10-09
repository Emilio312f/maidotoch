package dominio.recomendacion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Estrategia de respaldo y explicaciones, sin base de datos. Datos FICTICIOS. */
class RankingPorReglasTest {
    private val picante = 1L
    private val dulce = 2L

    private fun candidato(id: Long, nombre: String = "Plato $id", descripcion: String = "Descripción ficticia", preferencias: Map<Long, String> = emptyMap(), orden: Int = 1) =
        CandidatoRecomendable(id, 1, nombre, descripcion, orden, preferencias)

    private val perfil = ContextoPerfil("es", mapOf(picante to "Picante"), listOf("Alergia al maní"))

    @Test
    fun `las preferencias declaradas ordenan primero`() {
        val sinPreferencia = candidato(1)
        val conPreferencia = candidato(2, preferencias = mapOf(picante to "Picante"))

        assertEquals(listOf(conPreferencia, sinPreferencia), RankingPorReglas.ordenar(listOf(sinPreferencia, conPreferencia), perfil, ""))
    }

    @Test
    fun `las palabras de la consulta suben los platos que las mencionan`() {
        val otro = candidato(1)
        val ceviche = candidato(2, nombre = "Ceviche ficticio")

        assertEquals(listOf(ceviche, otro), RankingPorReglas.ordenar(listOf(otro, ceviche), perfil.copy(preferencias = emptyMap()), "quiero un ceviche"))
    }

    @Test
    fun `los empates se resuelven por categoria e id, sin usar el precio`() {
        // El modelo de candidato no tiene precio: el ranking no puede favorecer precios altos (RPR11).
        assertFalse(CandidatoRecomendable::class.java.declaredFields.any { "precio" in it.name.lowercase() })
        val a = candidato(3, orden = 2)
        val b = candidato(2, orden = 1)
        val c = candidato(1, orden = 2)

        assertEquals(listOf(b, c, a), RankingPorReglas.ordenar(listOf(a, b, c), perfil.copy(preferencias = emptyMap()), ""))
    }

    @Test
    fun `si algun plato coincide no se rellena con platos sin relacion`() {
        val ceviche = candidato(1, nombre = "Ceviche ficticio")
        val postre = candidato(2, nombre = "Postre ficticio")
        val sinPreferencias = perfil.copy(preferencias = emptyMap())

        assertEquals(listOf(ceviche), RankingPorReglas.seleccionar(listOf(postre, ceviche), sinPreferencias, "un ceviche"))
        // Sin ninguna coincidencia se ofrecen todos los candidatos seguros, en orden estable.
        assertEquals(listOf(ceviche, postre), RankingPorReglas.seleccionar(listOf(postre, ceviche), sinPreferencias, "algo rico"))
    }

    @Test
    fun `el validador conserva solo ids enviados y sin duplicados`() {
        assertEquals(listOf(3L, 1L), ValidadorRespuestaIA.filtrar(listOf(3, 99, 1, 3, -5), setOf(1, 2, 3)))
    }

    @Test
    fun `la explicacion cita preferencias, consulta y restricciones reales`() {
        val c = candidato(1, nombre = "Ceviche ficticio", preferencias = mapOf(picante to "Picante", dulce to "Dulce"))

        val texto = GeneradorExplicaciones.explicar(c, perfil, "un ceviche", 4)

        assertTrue("Coincide con tus preferencias: Picante." in texto, texto)
        assertFalse("Dulce" in texto, "Solo se citan preferencias declaradas por el comensal")
        assertTrue("«ceviche»" in texto, texto)
        assertTrue("Validado por cocina como compatible con: Alergia al maní." in texto, texto)
        assertFalse("mejor plato" in texto.lowercase())
    }

    @Test
    fun `sin coincidencias la explicacion lo dice en vez de usar una frase generica`() {
        val texto = GeneradorExplicaciones.explicar(candidato(1), ContextoPerfil("en", emptyMap(), emptyList()), "", 5)

        assertEquals(
            "It does not match a specific preference; it is one of the 5 dishes available today that fit your profile. Available on today's menu.",
            texto,
        )
    }
}
