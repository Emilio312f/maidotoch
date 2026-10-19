package bus

import comun.SolicitudInvalida
import comun.contratos.ApiBus
import comun.contratos.Evento
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Bus de eventos en memoria (Observer / publicación-suscripción): comandas confirmadas, llamados y
 * derivaciones para las pantallas de cocina, barra y sala. No guarda datos de negocio: si una
 * pantalla no estaba conectada, igual ve la comanda en su consulta periódica al Servicio de Pedido.
 */
class BusEventos : ApiBus {
    private val flujos = Evento.CANALES.associateWith {
        MutableSharedFlow<Evento>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    override fun publicar(evento: Evento) {
        val flujo = flujos[evento.canal] ?: throw SolicitudInvalida("canal_invalido", "Canal no válido: ${evento.canal}")
        flujo.tryEmit(evento)
    }

    fun suscribir(canal: String): SharedFlow<Evento> =
        flujos[canal] ?: throw SolicitudInvalida("canal_invalido", "Canal no válido: $canal")

    fun suscriptores(canal: String): Int = flujos[canal]?.subscriptionCount?.value ?: 0
}
