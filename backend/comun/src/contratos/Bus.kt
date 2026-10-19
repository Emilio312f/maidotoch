package comun.contratos

import comun.http.ClienteInterno
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Aviso para las pantallas de cocina, barra y sala (Observer / publicación-suscripción). Solo lleva
 * el tipo y una referencia: la pantalla vuelve a pedir el detalle a su servicio. Sin datos personales.
 */
@Serializable
data class Evento(val canal: String, val tipo: String, val referencia: String, val fecha: String = java.time.Instant.now().toString()) {
    companion object {
        val CANALES = setOf("cocina", "barra", "sala")
        const val COMANDA_NUEVA = "comanda_nueva"
        const val COMANDA_ACTUALIZADA = "comanda_actualizada"
        const val LLAMADO_NUEVO = "llamado_nuevo"
        const val LLAMADO_ATENDIDO = "llamado_atendido"
    }
}

/** API interna del bus de eventos. */
interface ApiBus {
    fun publicar(evento: Evento)
}

class HttpApiBus(private val cliente: ClienteInterno) : ApiBus {
    override fun publicar(evento: Evento) {
        cliente.post<Evento, Ok>("/interno/bus/publicar", evento)
    }
}

/**
 * Publicación asíncrona con reintento: quien publica no espera al bus. Si el bus no responde, la
 * comanda o el llamado siguen en la base y las pantallas los ven en su consulta periódica (RNF03).
 */
class PublicadorEventos(private val bus: ApiBus, private val intentos: Int = 3, private val esperaMs: Long = 200) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ejecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "publicador-eventos").apply { isDaemon = true } }

    fun publicar(evento: Evento) {
        ejecutor.execute {
            for (intento in 1..intentos) {
                try {
                    bus.publicar(evento)
                    return@execute
                } catch (e: Exception) {
                    if (intento == intentos) {
                        log.warn("Evento {} sin publicar tras {} intentos: {}", evento.tipo, intentos, e.message)
                    } else {
                        Thread.sleep(esperaMs * intento)
                    }
                }
            }
        }
    }

    /** Espera a que salgan los eventos pendientes (pruebas y cierre ordenado). */
    fun vaciar(timeoutMs: Long = 5_000) {
        ejecutor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        ejecutor.shutdown()
    }
}
