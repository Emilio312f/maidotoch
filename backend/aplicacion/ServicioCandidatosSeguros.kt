package aplicacion

import datos.RepositorioSeguridadAlimentaria
import dominio.disponibilidad.FiltroDisponibilidad
import dominio.restricciones.EstadoSeguridad
import dominio.restricciones.EvaluacionProducto
import dominio.restricciones.MotorRestricciones

/** La sesión no existe o está cerrada (401), o todavía no completó el perfil (409). */
class SesionNoDisponible(perfilIncompleto: Boolean = false) : ErrorNegocio(
    if (perfilIncompleto) 409 else 401,
    if (perfilIncompleto) "perfil_incompleto" else "sesion_no_valida",
    if (perfilIncompleto) "La sesión no tiene el perfil completo" else "La sesión no existe o está cerrada",
)

data class CandidatoSeguro(val productoId: Long, val revisionProducto: Long)

data class CandidatosSeguros(
    val sesionId: String,
    val revisionPerfil: Long,
    /** Única lista que puede seguir por la cadena hacia el ranking y la IA. */
    val seguros: List<CandidatoSeguro>,
    /** Evaluación del motor sobre los productos que pasaron el filtro de disponibilidad. */
    val evaluaciones: List<EvaluacionProducto>,
    /** Productos en que el motor y v_productos_compatibles no coinciden; se excluyen y deben revisarse. */
    val discrepancias: Set<Long>,
) {
    val sinDato: List<EvaluacionProducto> get() = evaluaciones.filter { it.estado == EstadoSeguridad.SIN_DATO }

    /** Sin candidatos no se recomienda nada y se ofrece el llamado al mozo. */
    val sinCandidatos: Boolean get() = seguros.isEmpty()
}

/**
 * Primeros eslabones de la cadena de recomendación: disponibilidad → restricciones.
 *
 * Un producto es candidato solo si el Motor de Restricciones lo declara Seguro **y** la vista
 * v_productos_compatibles lo incluye. Cualquier desacuerdo entre ambos lo excluye.
 */
class ServicioCandidatosSeguros(
    private val repositorio: RepositorioSeguridadAlimentaria,
    private val motor: MotorRestricciones = MotorRestricciones(),
) {
    fun calcular(sesionId: String): CandidatosSeguros {
        val instantanea = repositorio.instantanea(sesionId) ?: throw SesionNoDisponible()
        val perfil = instantanea.perfil
        if (!perfil.abierta) throw SesionNoDisponible()
        if (!perfil.perfilCompleto) throw SesionNoDisponible(perfilIncompleto = true)

        val disponibles = FiltroDisponibilidad.filtrar(instantanea.productos.map { it.catalogo })
            .map { it.productoId }
            .toSet()
        val evaluaciones = instantanea.productos
            .filter { it.catalogo.productoId in disponibles }
            .map { motor.evaluar(it.seguridad, perfil.restricciones) }

        val segurosMotor = evaluaciones.filter { it.estado == EstadoSeguridad.SEGURO }.map { it.productoId }.toSet()
        val vista = instantanea.compatibles
            .filter { it.revisionPerfil == perfil.revisionPerfil }
            .associateBy { it.productoId }

        val seguros = segurosMotor.mapNotNull { vista[it] }
            .map { CandidatoSeguro(it.productoId, it.revisionProducto) }
            .sortedBy { it.productoId }

        return CandidatosSeguros(
            sesionId = sesionId,
            revisionPerfil = perfil.revisionPerfil,
            seguros = seguros,
            evaluaciones = evaluaciones,
            discrepancias = (segurosMotor - vista.keys) + (vista.keys - segurosMotor),
        )
    }
}
