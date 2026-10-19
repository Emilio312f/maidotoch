package comun.contratos

import comun.ErrorRemoto
import comun.http.ClienteInterno
import kotlinx.serialization.Serializable

@Serializable
data class ProductoRecomendado(val productoId: Long)

/** API interna del Servicio de Recomendación. */
interface ApiRecomendacion {
    /** Producto de la recomendación si pertenece a la sesión; null si no existe o es de otra sesión. */
    fun productoDeRecomendacion(recomendacionId: Long, sesionId: String): Long?
}

class HttpApiRecomendacion(private val cliente: ClienteInterno) : ApiRecomendacion {
    override fun productoDeRecomendacion(recomendacionId: Long, sesionId: String): Long? = try {
        cliente.get<ProductoRecomendado>("/interno/recomendaciones/$recomendacionId?sesion=${codificar(sesionId)}").productoId
    } catch (e: ErrorRemoto) {
        if (e.estadoHttp == 404) null else throw e
    }
}

@Serializable
data class EventoRecomendacion(val recomendacionId: Long, val accion: String)

/** API interna del Servicio de Trazabilidad (RF13 · RNF09). */
interface ApiTrazabilidad {
    /** Registra `mostrada`, `aceptada`, `modificada` o `rechazada`, una sola vez por recomendación y acción. */
    fun registrarEvento(recomendacionId: Long, accion: String)
}

class HttpApiTrazabilidad(private val cliente: ClienteInterno) : ApiTrazabilidad {
    override fun registrarEvento(recomendacionId: Long, accion: String) {
        cliente.post<EventoRecomendacion, Ok>("/interno/trazabilidad/eventos", EventoRecomendacion(recomendacionId, accion))
    }
}

/**
 * DTO mínimo que puede salir hacia el proveedor externo de IA (RNF01 · RNF02): la consulta y los
 * candidatos ya filtrados. Sin alérgenos, sin ingredientes, sin restricciones y sin id de sesión.
 */
@Serializable
data class SolicitudOrdenamiento(val consulta: String, val maximo: Int, val candidatos: List<CandidatoParaIA>)

@Serializable
data class CandidatoParaIA(val id: Long, val nombre: String, val descripcion: String, val etiquetas: List<String>)

@Serializable
data class RespuestaOrdenamiento(val ids: List<Long>)

@Serializable
data class EstadoIA(val configurada: Boolean, val modelo: String? = null, val timeoutMs: Long, val circuitoAbierto: Boolean)

/** API interna del Servicio Adaptador de IA: única frontera con el proveedor externo. */
interface ApiIA {
    /** Ids en orden de relevancia. La respuesta no es confiable: el Servicio de Recomendación la valida. */
    suspend fun ordenar(solicitud: SolicitudOrdenamiento): List<Long>

    fun estado(): EstadoIA
}

class HttpApiIA(private val cliente: ClienteInterno, private val timeoutMs: Long) : ApiIA {
    override suspend fun ordenar(solicitud: SolicitudOrdenamiento): List<Long> = cliente.enviarCancelable(
        "POST", "/interno/ia/ordenar",
        ClienteInterno.JSON.encodeToString(SolicitudOrdenamiento.serializer(), solicitud),
        RespuestaOrdenamiento.serializer(),
        timeoutMs,
    ).ids

    override fun estado(): EstadoIA = cliente.get("/interno/ia/estado")
}
