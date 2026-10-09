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

@Serializable
data class Medicion(
    val id: Long,
    val fase: String,
    val fecha: String,
    val comensales: Int,
    val importeCentimos: Long,
    val tiempoPedidoSegundos: Int,
    val consultasMozo: Int,
    val fuente: String,
    val registradoPor: String,
)

/** Promedios por comensal de una fase (línea base, piloto, post-lanzamiento) para comparar el impacto. */
@Serializable
data class ResumenFase(
    val fase: String,
    val registros: Int,
    val comensales: Long,
    val ticketPorComensalCentimos: Long,
    val tiempoPedidoPromedioSegundos: Long,
    val consultasMozoPorComensal: Double,
)

class RepositorioMetricas(private val conn: Connection) {

    fun registrarMedicion(fase: String, fecha: String, comensales: Int, importeCentimos: Long, tiempoSegundos: Int, consultasMozo: Int, fuente: String, personalId: Long): Long =
        conn.insertar(
            """
            INSERT INTO mediciones_piloto(fase, fecha, comensales, importe_centimos, tiempo_pedido_segundos, consultas_mozo, fuente, registrado_por)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            fase, fecha, comensales, importeCentimos, tiempoSegundos, consultasMozo, fuente, personalId,
        )

    fun mediciones(): List<Medicion> = conn.consultar(
        """
        SELECT m.id, m.fase, m.fecha, m.comensales, m.importe_centimos, m.tiempo_pedido_segundos, m.consultas_mozo, m.fuente, p.identificador
        FROM mediciones_piloto m JOIN personal p ON p.id = m.registrado_por ORDER BY m.fecha DESC, m.id DESC
        """.trimIndent(),
    ) { Medicion(it.getLong(1), it.getString(2), it.getString(3), it.getInt(4), it.getLong(5), it.getInt(6), it.getInt(7), it.getString(8), it.getString(9)) }

    /** El tiempo de pedido se pondera por comensales: cada registro resume a varios comensales. */
    fun resumenPorFase(): List<ResumenFase> = conn.consultar(
        """
        SELECT fase, count(*), sum(comensales),
               CAST(sum(importe_centimos) / sum(comensales) AS INTEGER),
               CAST(sum(tiempo_pedido_segundos * comensales) / sum(comensales) AS INTEGER),
               CAST(sum(consultas_mozo) AS REAL) / sum(comensales)
        FROM mediciones_piloto GROUP BY fase
        ORDER BY CASE fase WHEN 'linea_base' THEN 1 WHEN 'piloto' THEN 2 ELSE 3 END
        """.trimIndent(),
    ) { ResumenFase(it.getString(1), it.getInt(2), it.getLong(3), it.getLong(4), it.getLong(5), it.getDouble(6)) }

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
