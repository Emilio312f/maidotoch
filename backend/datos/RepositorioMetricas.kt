package datos

import kotlinx.serialization.Serializable
import java.sql.Connection

@Serializable
data class Metricas(
    val recomendaciones: Long,
    val mostradas: Long,
    val aceptadas: Long,
    val rechazadas: Long,
    /** Aceptadas / mostradas, en porcentaje. Meta del piloto: ≥ 40 %. */
    val tasaAceptacion: Double?,
    val consultasPorMotor: Map<String, Long>,
    val latenciaP95Ms: Long?,
    val llamadosPorMotivo: Map<String, Long>,
    val pedidosConfirmados: Long,
    val ticketPromedioCentimos: Long?,
    val encuestas: Long,
    val satisfaccionPromedio: Double?,
)

class RepositorioMetricas(private val conn: Connection) {
    fun metricas(): Metricas {
        val (recomendaciones, mostradas, aceptadas, rechazadas) = conn.consultar(
            "SELECT recomendaciones, mostradas, aceptadas, rechazadas FROM v_aceptacion_recomendaciones",
        ) { listOf(it.getLong(1), it.getLong(2), it.getLong(3), it.getLong(4)) }.single()
        val latencias = conn.consultar("SELECT latencia_ms FROM consultas_asistente WHERE motor <> 'ninguno' ORDER BY 1") { it.getLong(1) }
        val p95 = if (latencias.isEmpty()) null else latencias[((latencias.size * 95 + 99) / 100 - 1).coerceIn(latencias.indices)]
        val (pedidos, ticket) = conn.consultar(
            "SELECT count(*), CAST(avg(total_centimos) AS INTEGER) FROM v_totales_pedidos WHERE estado <> 'borrador'",
        ) { it.getLong(1) to it.getObject(2)?.let { _ -> it.getLong(2) } }.single()
        val (encuestas, satisfaccion) = conn.consultar("SELECT count(*), avg(satisfaccion) FROM encuestas") {
            it.getLong(1) to it.getObject(2)?.let { _ -> it.getDouble(2) }
        }.single()
        return Metricas(
            recomendaciones = recomendaciones,
            mostradas = mostradas,
            aceptadas = aceptadas,
            rechazadas = rechazadas,
            tasaAceptacion = if (mostradas == 0L) null else 100.0 * aceptadas / mostradas,
            consultasPorMotor = conn.consultar("SELECT motor, count(*) FROM consultas_asistente GROUP BY motor") { it.getString(1) to it.getLong(2) }.toMap(),
            latenciaP95Ms = p95,
            llamadosPorMotivo = conn.consultar("SELECT motivo, count(*) FROM llamados_mozo GROUP BY motivo") { it.getString(1) to it.getLong(2) }.toMap(),
            pedidosConfirmados = pedidos,
            ticketPromedioCentimos = ticket,
            encuestas = encuestas,
            satisfaccionPromedio = satisfaccion,
        )
    }
}
