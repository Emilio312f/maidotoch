package gateway

import java.util.concurrent.ConcurrentHashMap

/**
 * Límite de solicitudes por ventana de un minuto (por IP y por sesión de comensal). Protege el
 * servicio en hora punta y contiene el costo del asistente; no reemplaza el límite de consultas con
 * IA por sesión, que aplica el Servicio de Recomendación.
 */
class LimiteSolicitudes(private val maximoPorMinuto: Int, private val reloj: () -> Long = System::currentTimeMillis) {
    private class Ventana(var inicio: Long, var cuenta: Int)

    private val ventanas = ConcurrentHashMap<String, Ventana>()

    fun permitir(clave: String): Boolean {
        val ahora = reloj()
        val ventana = ventanas.computeIfAbsent(clave) { Ventana(ahora, 0) }
        synchronized(ventana) {
            if (ahora - ventana.inicio >= VENTANA_MS) {
                ventana.inicio = ahora
                ventana.cuenta = 0
            }
            ventana.cuenta++
            return ventana.cuenta <= maximoPorMinuto
        }
    }

    /** Descarta ventanas viejas para que el mapa no crezca sin límite. */
    fun limpiar() {
        val ahora = reloj()
        ventanas.entries.removeIf { ahora - it.value.inicio >= 2 * VENTANA_MS }
    }

    private companion object {
        const val VENTANA_MS = 60_000L
    }
}
