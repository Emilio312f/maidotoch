package ia

import comun.contratos.SolicitudOrdenamiento
import kotlinx.coroutines.withTimeout

class CircuitoAbierto : RuntimeException("Proveedor de IA en pausa tras fallos consecutivos")

/**
 * Timeout + Circuit Breaker alrededor del proveedor de IA (RNF04 · RPR05 · RPR08).
 *
 * Cada llamada se corta a los [timeoutMs]. Tras [umbralFallos] fallos seguidos el circuito se abre
 * durante [pausaMs]: no se llama al proveedor y el orquestador usa el fallback de inmediato.
 */
class OrdenadorProtegido(
    private val proveedor: OrdenadorExterno,
    val timeoutMs: Long,
    private val umbralFallos: Int = 3,
    private val pausaMs: Long = 30_000,
    private val reloj: () -> Long = System::currentTimeMillis,
) : OrdenadorExterno {
    private var fallosSeguidos = 0
    private var abiertoHasta = 0L

    override suspend fun ordenar(solicitud: SolicitudOrdenamiento): List<Long> {
        if (estaAbierto()) throw CircuitoAbierto()
        return try {
            withTimeout(timeoutMs) { proveedor.ordenar(solicitud) }.also { registrarExito() }
        } catch (e: Throwable) {
            registrarFallo()
            throw e
        }
    }

    @Synchronized
    fun estaAbierto(): Boolean = reloj() < abiertoHasta

    @Synchronized
    private fun registrarExito() {
        fallosSeguidos = 0
    }

    @Synchronized
    private fun registrarFallo() {
        fallosSeguidos++
        if (fallosSeguidos >= umbralFallos) {
            abiertoHasta = reloj() + pausaMs
            fallosSeguidos = 0
        }
    }
}
