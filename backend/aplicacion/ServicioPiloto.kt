package aplicacion

import configuracion.Configuracion
import datos.FuenteConexiones
import datos.Medicion
import datos.PersonalAutenticado
import datos.RepositorioMetricas
import datos.ResumenFase
import datos.consultar
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Serializable
data class MedicionesPiloto(val mediciones: List<Medicion>, val resumen: List<ResumenFase>)

@Serializable
data class ReporteDiario(
    val fecha: String,
    val sesiones: Int,
    val consultasPorMotor: Map<String, Int>,
    val latenciaP95Ms: Long?,
    val latenciaMaximaMs: Long?,
    val consultasSobrePresupuesto: Int,
    val mostradas: Int,
    val aceptadas: Int,
    val rechazadas: Int,
    val pedidosConfirmados: Int,
    val ticketPromedioCentimos: Long?,
    val comandas: Int,
    val comandasSinRecibir: Int,
    val recepcionP95Segundos: Long?,
    val llamadosPorMotivo: Map<String, Int>,
    val atencionPromedioSegundos: Long?,
    val cambiosDisponibilidad: Int,
    val encuestas: Int,
    val satisfaccionPromedio: Double?,
) {
    /** Resumen en texto para enviar al Director de Proyecto (Plan §7.2, monitoreo diario). */
    fun aTexto(): String = buildString {
        fun linea(texto: String) = appendLine(texto)
        val totalConsultas = consultasPorMotor.values.sum()
        linea("Reporte diario MaidoTouch · $fecha (hora de Lima)")
        linea("")
        linea("Sesiones iniciadas: $sesiones")
        linea("Consultas al asistente: $totalConsultas (${consultasPorMotor.entries.joinToString { "${it.key}: ${it.value}" }.ifEmpty { "ninguna" }})")
        linea("Latencia del asistente: P95 ${latenciaP95Ms?.let { "$it ms" } ?: "—"}, máxima ${latenciaMaximaMs?.let { "$it ms" } ?: "—"}, sobre 3 s: $consultasSobrePresupuesto")
        linea("Recomendaciones: $mostradas mostradas, $aceptadas aceptadas, $rechazadas rechazadas" +
            (if (mostradas > 0) " (aceptación ${"%.1f".format(100.0 * aceptadas / mostradas)} %, meta ≥ 40 %)" else ""))
        linea("Pedidos enviados: $pedidosConfirmados, ticket promedio ${ticketPromedioCentimos?.let { "S/ ${"%.2f".format(it / 100.0)}" } ?: "—"}")
        linea("Comandas: $comandas, sin recibir: $comandasSinRecibir, recepción P95 ${recepcionP95Segundos?.let { "$it s" } ?: "—"}")
        linea("Llamados al mozo: ${llamadosPorMotivo.entries.joinToString { "${it.key}: ${it.value}" }.ifEmpty { "ninguno" }}, atención promedio ${atencionPromedioSegundos?.let { "$it s" } ?: "—"}")
        linea("Derivaciones por restricción no validable: ${llamadosPorMotivo["restriccion_incierta"] ?: 0}")
        linea("Cambios de disponibilidad: $cambiosDisponibilidad")
        linea("Encuestas: $encuestas, satisfacción promedio ${satisfaccionPromedio?.let { "%.2f".format(it) } ?: "—"}")
    }
}

enum class ResultadoChequeo { OK, AVISO, FALLA }

@Serializable
data class Chequeo(val criterio: String, val resultado: ResultadoChequeo, val detalle: String)

/**
 * Herramientas del piloto: mediciones de línea base y piloto, reporte diario de monitoreo y
 * verificación de los criterios de Go/No-Go que se pueden comprobar sobre la base (Plan §5, §7).
 */
class ServicioPiloto(private val fuente: FuenteConexiones, private val configuracion: Configuracion) {

    private val fases = setOf("linea_base", "piloto", "post_lanzamiento")
    private val zona = ZoneId.of("America/Lima")
    private val formatoUtc = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun registrarMedicion(
        admin: PersonalAutenticado,
        fase: String,
        fecha: String,
        comensales: Int,
        importeCentimos: Long,
        tiempoPedidoSegundos: Int,
        consultasMozo: Int,
        fuenteDato: String,
    ): MedicionesPiloto {
        admin.exigirRol()
        if (fase !in fases) throw SolicitudInvalida("fase_invalida", "Fase no válida: $fase")
        fechaValida(fecha)
        if (comensales <= 0 || importeCentimos < 0 || tiempoPedidoSegundos < 0 || consultasMozo < 0) {
            throw SolicitudInvalida("medicion_invalida", "Comensales > 0; importe, tiempo y consultas ≥ 0")
        }
        if (fuenteDato.isBlank()) throw SolicitudInvalida("fuente_requerida", "Indica la fuente del dato (p. ej. observación en sala, cierre de caja)")
        fuente.usar { RepositorioMetricas(it).registrarMedicion(fase, fecha, comensales, importeCentimos, tiempoPedidoSegundos, consultasMozo, fuenteDato.trim(), admin.id) }
        return mediciones(admin)
    }

    fun mediciones(admin: PersonalAutenticado): MedicionesPiloto {
        admin.exigirRol()
        return fuente.usar { conn -> RepositorioMetricas(conn).let { MedicionesPiloto(it.mediciones(), it.resumenPorFase()) } }
    }

    fun reporteDiario(admin: PersonalAutenticado, fecha: String): ReporteDiario {
        admin.exigirRol()
        return reporteDiario(fechaValida(fecha))
    }

    /** Día calendario de Lima, convertido al rango UTC en que se guardan las fechas. */
    fun reporteDiario(dia: LocalDate): ReporteDiario = fuente.usar { conn ->
        val desde = formatoUtc.format(dia.atStartOfDay(zona).toInstant())
        val hasta = formatoUtc.format(dia.plusDays(1).atStartOfDay(zona).toInstant())
        fun entero(sql: String) = conn.consultar(sql, desde, hasta) { it.getInt(1) }.single()
        fun conteo(sql: String) = conn.consultar(sql, desde, hasta) { it.getString(1) to it.getInt(2) }.toMap()

        val latencias = conn.consultar(
            "SELECT latencia_ms FROM consultas_asistente WHERE fecha >= ? AND fecha < ? AND motor <> 'ninguno' ORDER BY 1",
            desde, hasta,
        ) { it.getLong(1) }
        val recepciones = conn.consultar(
            "SELECT CAST((julianday(recibida_en) - julianday(creada_en)) * 86400 AS INTEGER) FROM comandas WHERE creada_en >= ? AND creada_en < ? AND recibida_en IS NOT NULL ORDER BY 1",
            desde, hasta,
        ) { it.getLong(1) }
        val (ticket, encuestas) = conn.consultar(
            "SELECT CAST(avg(t.total_centimos) AS INTEGER) FROM v_totales_pedidos t WHERE t.confirmado_en >= ? AND t.confirmado_en < ?",
            desde, hasta,
        ) { it.getObject(1)?.let { _ -> it.getLong(1) } }.single() to
            conn.consultar("SELECT count(*), avg(satisfaccion) FROM encuestas WHERE fecha >= ? AND fecha < ?", desde, hasta) {
                it.getInt(1) to it.getObject(2)?.let { _ -> it.getDouble(2) }
            }.single()

        ReporteDiario(
            fecha = dia.toString(),
            sesiones = entero("SELECT count(*) FROM sesiones WHERE inicio >= ? AND inicio < ?"),
            consultasPorMotor = conteo("SELECT motor, count(*) FROM consultas_asistente WHERE fecha >= ? AND fecha < ? GROUP BY motor"),
            latenciaP95Ms = percentil95(latencias),
            latenciaMaximaMs = latencias.maxOrNull(),
            consultasSobrePresupuesto = latencias.count { it > 3_000 },
            mostradas = entero("SELECT count(*) FROM recomendacion_eventos WHERE accion = 'mostrada' AND fecha >= ? AND fecha < ?"),
            aceptadas = entero("SELECT count(*) FROM recomendacion_eventos WHERE accion = 'aceptada' AND fecha >= ? AND fecha < ?"),
            rechazadas = entero("SELECT count(*) FROM recomendacion_eventos WHERE accion = 'rechazada' AND fecha >= ? AND fecha < ?"),
            pedidosConfirmados = entero("SELECT count(*) FROM pedidos WHERE confirmado_en >= ? AND confirmado_en < ?"),
            ticketPromedioCentimos = ticket,
            comandas = entero("SELECT count(*) FROM comandas WHERE creada_en >= ? AND creada_en < ?"),
            comandasSinRecibir = entero("SELECT count(*) FROM comandas WHERE creada_en >= ? AND creada_en < ? AND estado = 'pendiente'"),
            recepcionP95Segundos = percentil95(recepciones),
            llamadosPorMotivo = conteo("SELECT motivo, count(*) FROM llamados_mozo WHERE creado_en >= ? AND creado_en < ? GROUP BY motivo"),
            atencionPromedioSegundos = conn.consultar(
                "SELECT CAST(avg((julianday(atendido_en) - julianday(creado_en)) * 86400) AS INTEGER) FROM llamados_mozo WHERE creado_en >= ? AND creado_en < ? AND atendido_en IS NOT NULL",
                desde, hasta,
            ) { it.getObject(1)?.let { _ -> it.getLong(1) } }.single(),
            cambiosDisponibilidad = entero("SELECT count(*) FROM disponibilidad_historial WHERE fecha >= ? AND fecha < ?"),
            encuestas = encuestas.first,
            satisfaccionPromedio = encuestas.second,
        )
    }

    /** Criterios de Go/No-Go verificables sobre la base y la configuración. FALLA bloquea el paso a producción. */
    fun verificar(): List<Chequeo> = fuente.usar { conn ->
        buildList {
            add(catalogoValidado(conn))
            add(evaluacionesCompletas(conn))
            add(fotos(conn))
            add(puestos(conn))
            add(cuentas(conn))
            add(sinDatosFicticios(conn))
            add(
                if (configuracion.iaApiKey != null) Chequeo("Proveedor de IA configurado", ResultadoChequeo.OK, "Modelo ${configuracion.iaModelo}")
                else Chequeo("Proveedor de IA configurado", ResultadoChequeo.AVISO, "Sin ANTHROPIC_API_KEY: el asistente operará solo con el ranking por reglas"),
            )
            add(
                if (configuracion.iaTimeoutMs <= 5_000) Chequeo("Timeout del proveedor ≤ 5 s", ResultadoChequeo.OK, "${configuracion.iaTimeoutMs} ms")
                else Chequeo("Timeout del proveedor ≤ 5 s", ResultadoChequeo.FALLA, "${configuracion.iaTimeoutMs} ms excede el presupuesto (RNF04)"),
            )
            add(
                if (configuracion.urlPublica.startsWith("https://")) Chequeo("URL pública con HTTPS", ResultadoChequeo.OK, configuracion.urlPublica)
                else Chequeo("URL pública con HTTPS", ResultadoChequeo.FALLA, "${configuracion.urlPublica}: los tags y QR deben apuntar a HTTPS"),
            )
            val lineaBase = conn.consultar("SELECT count(*) FROM mediciones_piloto WHERE fase = 'linea_base'") { it.getInt(1) }.single()
            add(
                if (lineaBase > 0) Chequeo("Línea base medida", ResultadoChequeo.OK, "$lineaBase registros")
                else Chequeo("Línea base medida", ResultadoChequeo.AVISO, "Sin registros de línea base: no se podrá comparar el piloto"),
            )
        }
    }

    private fun catalogoValidado(conn: Connection): Chequeo {
        val activos = conn.consultar("SELECT count(*) FROM productos WHERE activo = 1") { it.getInt(1) }.single()
        val sinValidar = conn.consultar("SELECT codigo FROM productos WHERE activo = 1 AND validado_en IS NULL ORDER BY codigo") { it.getString(1) }
        return when {
            activos == 0 -> Chequeo("Catálogo piloto 100 % validado", ResultadoChequeo.FALLA, "No hay productos activos")
            sinValidar.isNotEmpty() -> Chequeo("Catálogo piloto 100 % validado", ResultadoChequeo.FALLA, "Sin validar: ${sinValidar.joinToString()}")
            else -> Chequeo("Catálogo piloto 100 % validado", ResultadoChequeo.OK, "$activos productos activos validados")
        }
    }

    private fun evaluacionesCompletas(conn: Connection): Chequeo {
        val faltantes = conn.consultar(
            """
            SELECT p.codigo || ' / ' || r.codigo FROM productos p CROSS JOIN restricciones r
            WHERE p.activo = 1 AND NOT EXISTS (
              SELECT 1 FROM producto_restricciones pr WHERE pr.producto_id = p.id AND pr.restriccion_id = r.id AND pr.resultado <> 'desconocido')
            ORDER BY 1
            """.trimIndent(),
        ) { it.getString(1) }
        return if (faltantes.isEmpty()) {
            Chequeo("Restricciones evaluadas en todos los productos", ResultadoChequeo.OK, "Sin evaluaciones faltantes ni desconocidas")
        } else {
            Chequeo(
                "Restricciones evaluadas en todos los productos",
                ResultadoChequeo.AVISO,
                "${faltantes.size} pares sin evaluar o desconocidos (se derivan al mozo): ${faltantes.take(10).joinToString()}${if (faltantes.size > 10) "…" else ""}",
            )
        }
    }

    private fun fotos(conn: Connection): Chequeo {
        val sinFoto = conn.consultar("SELECT codigo FROM productos WHERE activo = 1 AND (foto_url IS NULL OR trim(foto_url) = '') ORDER BY codigo") { it.getString(1) }
        return if (sinFoto.isEmpty()) Chequeo("Fotos de la carta (RF02)", ResultadoChequeo.OK, "Todos los productos activos tienen foto")
        else Chequeo("Fotos de la carta (RF02)", ResultadoChequeo.AVISO, "${sinFoto.size} productos sin foto: ${sinFoto.take(10).joinToString()}")
    }

    private fun puestos(conn: Connection): Chequeo {
        val activos = conn.consultar("SELECT codigo FROM puntos_servicio WHERE activo = 1 ORDER BY codigo") { it.getString(1) }
        val incompletos = conn.consultar(
            """
            SELECT p.codigo FROM puntos_servicio p WHERE p.activo = 1 AND (
              NOT EXISTS (SELECT 1 FROM accesos a WHERE a.punto_id = p.id AND a.canal = 'nfc' AND a.activo = 1) OR
              NOT EXISTS (SELECT 1 FROM accesos a WHERE a.punto_id = p.id AND a.canal = 'qr' AND a.activo = 1))
            """.trimIndent(),
        ) { it.getString(1) }
        return when {
            activos.isEmpty() -> Chequeo("Puestos con NFC y QR", ResultadoChequeo.FALLA, "No hay puestos activos")
            incompletos.isNotEmpty() -> Chequeo("Puestos con NFC y QR", ResultadoChequeo.FALLA, "Sin NFC o QR activo: ${incompletos.joinToString()}")
            else -> Chequeo("Puestos con NFC y QR", ResultadoChequeo.OK, "${activos.size} puestos activos con NFC y QR")
        }
    }

    private fun cuentas(conn: Connection): Chequeo {
        val porRol = conn.consultar(
            "SELECT p.rol, count(*) FROM personal p JOIN personal_credenciales c ON c.personal_id = p.id WHERE p.activo = 1 GROUP BY p.rol",
        ) { it.getString(1) to it.getInt(2) }.toMap()
        val faltan = listOf("administrador", "cocina", "barra", "sala").filter { (porRol[it] ?: 0) == 0 }
        return if (faltan.isEmpty()) Chequeo("Cuentas del personal por rol", ResultadoChequeo.OK, porRol.entries.joinToString { "${it.key}: ${it.value}" })
        else Chequeo("Cuentas del personal por rol", ResultadoChequeo.FALLA, "Sin cuenta activa con clave para: ${faltan.joinToString()}")
    }

    private fun sinDatosFicticios(conn: Connection): Chequeo {
        val ficticios = conn.consultar(
            """
            SELECT 'producto ' || codigo FROM productos WHERE codigo LIKE 'FICTICIO-%' AND activo = 1
            UNION ALL SELECT 'puesto ' || codigo FROM puntos_servicio WHERE codigo LIKE 'FICTICIO-%' AND activo = 1
            UNION ALL SELECT 'cuenta ' || identificador FROM personal WHERE identificador LIKE '%-demo' AND activo = 1
            """.trimIndent(),
        ) { it.getString(1) }
        return if (ficticios.isEmpty()) Chequeo("Sin datos FICTICIOS ni cuentas demo activas", ResultadoChequeo.OK, "—")
        else Chequeo("Sin datos FICTICIOS ni cuentas demo activas", ResultadoChequeo.FALLA, ficticios.take(10).joinToString())
    }

    private fun fechaValida(fecha: String): LocalDate = try {
        LocalDate.parse(fecha)
    } catch (e: DateTimeParseException) {
        throw SolicitudInvalida("fecha_invalida", "Fecha con formato AAAA-MM-DD")
    }

    companion object {
        fun percentil95(valores: List<Long>): Long? {
            if (valores.isEmpty()) return null
            val ordenados = valores.sorted()
            return ordenados[((ordenados.size * 95 + 99) / 100 - 1).coerceIn(ordenados.indices)]
        }

        /** Criterios del Go/No-Go que no se pueden comprobar sobre la base (Plan §5). */
        val VERIFICACIONES_MANUALES = listOf(
            "100 % de los casos críticos de alergias aprobados (./gradlew test en CI, sin pruebas omitidas)",
            "0 defectos críticos abiertos (tablero de GitHub)",
            "Latencia del asistente ≤ 3 s en P95 medida en 4G (scripts/medir_latencia.py)",
            "Fallback sin IA probado en staging (desactivar ANTHROPIC_API_KEY y repetir el recorrido)",
            "NFC/QR operativo en al menos el 99 % de los puestos (prueba física de cada tag)",
            "100 % del personal clave capacitado (registro de capacitación)",
        )
    }
}
