package ia

import comun.ErrorNegocio
import comun.contratos.ApiIA
import comun.contratos.EstadoIA
import comun.contratos.SolicitudOrdenamiento
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.slf4j.LoggerFactory

/** Puerto hacia el proveedor externo; lo implementa [AdaptadorClaude]. */
interface OrdenadorExterno {
    /** Devuelve ids en orden de relevancia. La respuesta no es confiable: se valida después. */
    suspend fun ordenar(solicitud: SolicitudOrdenamiento): List<Long>
}

/** El proveedor no respondió a tiempo, falló, el circuito está abierto o no hay clave: el llamador usa el fallback. */
class IaNoDisponible(codigo: String, mensaje: String) : ErrorNegocio(503, codigo, mensaje)

/**
 * Servicio Adaptador de IA: única frontera con el proveedor (RNF01 · RPR01 · RPR05). Recibe solo el
 * DTO mínimo, corta a los [timeoutMs] con circuit breaker y descarta cualquier id que no estuviera en
 * la lista enviada. Ningún otro servicio conoce la dirección ni la clave del proveedor.
 */
class ServicioAdaptadorIA(
    proveedor: OrdenadorExterno?,
    private val timeoutMs: Long,
    private val modelo: String?,
) : ApiIA {
    private val log = LoggerFactory.getLogger(javaClass)
    val protegido: OrdenadorProtegido? = proveedor?.let { OrdenadorProtegido(it, timeoutMs) }

    override suspend fun ordenar(solicitud: SolicitudOrdenamiento): List<Long> {
        val ordenador = protegido ?: throw IaNoDisponible("ia_no_configurada", "Sin ANTHROPIC_API_KEY")
        val enviados = solicitud.candidatos.map { it.id }.toSet()
        val ids = try {
            ordenador.ordenar(solicitud)
        } catch (e: TimeoutCancellationException) {
            throw IaNoDisponible("ia_tiempo_agotado", "El proveedor no respondió en $timeoutMs ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: CircuitoAbierto) {
            throw IaNoDisponible("ia_circuito_abierto", e.message ?: "Circuito abierto")
        } catch (e: Exception) {
            log.warn("Falla del proveedor de IA ({})", e.javaClass.simpleName)
            throw IaNoDisponible("ia_falla", e.javaClass.simpleName)
        }
        // Un id que no estaba en la lista enviada se descarta (la IA no inventa platos).
        return ids.filter { it in enviados }.distinct()
    }

    override fun estado(): EstadoIA = EstadoIA(protegido != null, modelo, timeoutMs, protegido?.estaAbierto() ?: false)
}
