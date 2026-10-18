package dominio.restricciones

/** Versión del conjunto de reglas de exclusión; se registra en recomendaciones.version_reglas. */
const val VERSION_REGLAS = "restricciones-v1"

enum class TipoRestriccion { ALERGIA, DIETA, OTRA }

enum class OrigenAlergeno { CONTIENE, TRAZAS }

enum class ResultadoEvaluacion { COMPATIBLE, INCOMPATIBLE, DESCONOCIDO }

/** Seguro: se puede recomendar. Inseguro: se excluye. Sin dato: se excluye y puede derivarse al mozo (RF14). */
enum class EstadoSeguridad { SEGURO, INSEGURO, SIN_DATO }

data class Restriccion(val id: Long, val tipo: TipoRestriccion, val alergenoId: Long?) {
    init {
        require((tipo == TipoRestriccion.ALERGIA) == (alergenoId != null)) {
            "Una restricción de alergia necesita alérgeno y las demás no lo llevan (restricción $id)"
        }
    }
}

/** Datos validados de un producto que el motor usa para decidir. Ninguno de ellos sale hacia la IA. */
data class DatosSeguridadProducto(
    val productoId: Long,
    val validado: Boolean,
    val alergenosDeclarados: Map<Long, Set<OrigenAlergeno>> = emptyMap(),
    val alergenosViaIngredientes: Set<Long> = emptySet(),
    /** Ausencia de evaluación = desconocido, nunca compatible por defecto. */
    val evaluaciones: Map<Long, ResultadoEvaluacion> = emptyMap(),
)

enum class TipoMotivo(val estado: EstadoSeguridad) {
    PRODUCTO_NO_VALIDADO(EstadoSeguridad.SIN_DATO),
    ALERGENO_CONTIENE(EstadoSeguridad.INSEGURO),
    ALERGENO_TRAZAS(EstadoSeguridad.INSEGURO),
    ALERGENO_VIA_INGREDIENTE(EstadoSeguridad.INSEGURO),
    EVALUACION_INCOMPATIBLE(EstadoSeguridad.INSEGURO),
    EVALUACION_DESCONOCIDA(EstadoSeguridad.SIN_DATO),
    SIN_EVALUACION(EstadoSeguridad.SIN_DATO),
}

/** Regla que excluyó al producto y, si aplica, la restricción de la sesión que la activó. */
data class Motivo(val tipo: TipoMotivo, val restriccionId: Long? = null)

data class EvaluacionProducto(val productoId: Long, val estado: EstadoSeguridad, val motivos: List<Motivo>)
