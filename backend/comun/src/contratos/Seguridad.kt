package comun.contratos

import comun.ErrorRemoto
import comun.http.ClienteInterno
import kotlinx.serialization.Serializable

@Serializable
data class CandidatoSeguro(val productoId: Long, val revisionProducto: Long)

/** Resultado del Motor de Restricciones para un producto: `seguro`, `inseguro` o `sin_dato`. */
@Serializable
data class EvaluacionResumen(val productoId: Long, val estado: String) {
    companion object {
        const val SEGURO = "seguro"
        const val INSEGURO = "inseguro"
        const val SIN_DATO = "sin_dato"
    }
}

/**
 * Respuesta del Servicio de Seguridad Alimentaria: la única lista que puede seguir hacia el ranking
 * y la IA. No incluye alérgenos ni restricciones; solo ids, revisiones y el estado de cada producto.
 */
@Serializable
data class ListaSegura(
    val sesionId: String,
    val revisionPerfil: Long,
    val seguros: List<CandidatoSeguro>,
    /** Evaluación del motor sobre los productos que pasaron el filtro de disponibilidad. */
    val evaluaciones: List<EvaluacionResumen>,
    /** Productos en que el motor y v_productos_compatibles no coinciden; se excluyen y deben revisarse. */
    val discrepancias: List<Long>,
    /** Versión de las reglas de exclusión que produjo la lista; se guarda en recomendaciones.version_reglas. */
    val versionReglas: String,
) {
    val sinDato: List<Long> get() = evaluaciones.filter { it.estado == EvaluacionResumen.SIN_DATO }.map { it.productoId }
}

@Serializable
data class CandidatosPeticion(val sesionId: String, val productos: List<Long>? = null)

/** Producto que una sesión puede pedir ahora mismo: está en v_productos_compatibles. */
@Serializable
data class ProductoPedible(val productoId: Long, val nombre: String, val precioCentimos: Long, val destino: String)

@Serializable
data class PediblePeticion(val sesionId: String, val productoId: Long)

@Serializable
data class ItemAVerificar(val itemId: Long, val productoId: Long, val nombre: String, val precioCentimos: Long, val destino: String)

@Serializable
data class ItemsPeticion(val sesionId: String, val items: List<ItemAVerificar>)

@Serializable
data class AlergenosProducto(val productoId: Long, val alergenos: List<String>, val trazas: List<String>)

@Serializable
data class AlergenosCarta(val productos: List<AlergenosProducto>)

@Serializable
data class OpcionesRestriccion(val restricciones: List<OpcionPerfil>)

@Serializable
data class AlergenoFicha(val alergeno: String, val origen: String)

@Serializable
data class EvaluacionFicha(val restriccion: String, val resultado: String, val evidencia: String, val validadoPor: Long, val validadoEn: String)

/** Datos de seguridad de un producto para la ficha de validación del back-office (cocina). */
@Serializable
data class FichaSeguridad(
    val ingredientes: List<String>,
    val alergenos: List<AlergenoFicha>,
    val evaluaciones: List<EvaluacionFicha>,
    val restriccionesSinEvaluar: List<String>,
)

@Serializable
data class ImportarSeguridadPeticion(
    val archivo: ArchivoCatalogo,
    val evaluador: String,
    /** Código de producto → id, ya creado por el Servicio de Catálogo. */
    val productos: Map<String, Long>,
)

/** API interna del Servicio de Seguridad Alimentaria (Motor de Restricciones, sin IA). */
interface ApiSeguridad {
    /**
     * Disponibilidad → restricciones. Si [productos] viene, solo se evalúan esos ids (los candidatos que
     * el Catálogo declaró disponibles); igual se vuelve a comprobar la disponibilidad en la base.
     */
    fun candidatos(sesionId: String, productos: Collection<Long>? = null): ListaSegura

    fun pedible(sesionId: String, productoId: Long): ProductoPedible?

    /** Ítems de un borrador que ya no pasarían la confirmación. */
    fun itemsNoConfirmables(sesionId: String, items: List<ItemAVerificar>): List<Long>

    fun restricciones(idioma: String): List<OpcionPerfil>

    /** Alérgenos validados de cada producto, para informarlos en la carta. */
    fun alergenos(idioma: String): List<AlergenosProducto>

    fun fichaSeguridad(productoId: Long): FichaSeguridad

    /** Ingredientes, alérgenos y evaluaciones del archivo validado (procedimiento de validación, Plan §6.3). */
    fun importar(archivo: ArchivoCatalogo, evaluador: String, productos: Map<String, Long>)
}

class HttpApiSeguridad(private val cliente: ClienteInterno) : ApiSeguridad {
    override fun candidatos(sesionId: String, productos: Collection<Long>?): ListaSegura =
        cliente.post("/interno/seguridad/candidatos", CandidatosPeticion(sesionId, productos?.toList()))

    override fun pedible(sesionId: String, productoId: Long): ProductoPedible? = try {
        cliente.post<PediblePeticion, ProductoPedible>("/interno/seguridad/pedible", PediblePeticion(sesionId, productoId))
    } catch (e: ErrorRemoto) {
        if (e.codigo == "producto_no_pedible") null else throw e
    }

    override fun itemsNoConfirmables(sesionId: String, items: List<ItemAVerificar>): List<Long> =
        cliente.post<ItemsPeticion, IdsPeticion>("/interno/seguridad/items-no-confirmables", ItemsPeticion(sesionId, items)).ids

    override fun restricciones(idioma: String): List<OpcionPerfil> =
        cliente.get<OpcionesRestriccion>("/interno/seguridad/restricciones?idioma=${codificar(idioma)}").restricciones

    override fun alergenos(idioma: String): List<AlergenosProducto> =
        cliente.get<AlergenosCarta>("/interno/seguridad/alergenos?idioma=${codificar(idioma)}").productos

    override fun fichaSeguridad(productoId: Long): FichaSeguridad = cliente.get("/interno/seguridad/productos/$productoId")

    override fun importar(archivo: ArchivoCatalogo, evaluador: String, productos: Map<String, Long>) {
        cliente.enviar(
            "POST", "/interno/seguridad/importar",
            ClienteInterno.JSON.encodeToString(ImportarSeguridadPeticion.serializer(), ImportarSeguridadPeticion(archivo, evaluador, productos)),
            Ok.serializer(),
            timeoutMs = 30_000,
        )
    }
}
