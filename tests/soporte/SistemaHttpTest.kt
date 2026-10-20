package soporte

import comun.Servicio
import kotlin.test.Test
import kotlin.test.assertEquals

class SistemaHttpTest {
    @Test
    fun `la asignacion de puertos evita duplicados`() {
        val entregados = ArrayDeque(listOf(41000, 41000, 41001, 41001, 41002, 41003, 41004, 41005, 41006, 41007, 41008, 41009, 41010))
        val puertos = SistemaHttp.puertosUnicos(Servicio.entries) { entregados.removeFirst() }

        assertEquals(Servicio.entries.size, puertos.values.toSet().size)
    }
}
