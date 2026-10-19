package seguridad.aplicacion

import comun.SesionNoDisponible
import comun.contratos.CandidatoSeguro
import comun.contratos.EvaluacionResumen
import comun.contratos.ListaSegura
import dominio.disponibilidad.FiltroDisponibilidad
import dominio.restricciones.EstadoSeguridad
import dominio.restricciones.EvaluacionProducto
import dominio.restricciones.MotorRestricciones
import dominio.restricciones.VERSION_REGLAS
import seguridad.datos.RepositorioSeguridadAlimentaria

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

    /** Lo que sale del servicio: ids, revisiones y estados; nunca los motivos ni los alérgenos. */
    fun comoLista(): ListaSegura = ListaSegura(
        sesionId = sesionId,
        revisionPerfil = revisionPerfil,
        seguros = seguros,
        evaluaciones = evaluaciones.map {
            EvaluacionResumen(
                it.productoId,
                when (it.estado) {
                    EstadoSeguridad.SEGURO -> EvaluacionResumen.SEGURO
                    EstadoSeguridad.INSEGURO -> EvaluacionResumen.INSEGURO
                    EstadoSeguridad.SIN_DATO -> EvaluacionResumen.SIN_DATO
                },
            )
        },
        discrepancias = discrepancias.sorted(),
        versionReglas = VERSION_REGLAS,
    )
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
    /**
     * Si [soloProductos] viene, se evalúan solo esos ids (los que el Catálogo declaró disponibles); la
     * disponibilidad se vuelve a comprobar aquí, sobre la misma instantánea de la base.
     */
    fun calcular(sesionId: String, soloProductos: Set<Long>? = null): CandidatosSeguros {
        val instantanea = repositorio.instantanea(sesionId) ?: throw SesionNoDisponible()
        val perfil = instantanea.perfil
        if (!perfil.abierta) throw SesionNoDisponible()
        if (!perfil.perfilCompleto) throw SesionNoDisponible(perfilIncompleto = true)

        val productos = instantanea.productos.filter { soloProductos == null || it.catalogo.productoId in soloProductos }
        val disponibles = FiltroDisponibilidad.filtrar(productos.map { it.catalogo })
            .map { it.productoId }
            .toSet()
        val evaluaciones = productos
            .filter { it.catalogo.productoId in disponibles }
            .map { motor.evaluar(it.seguridad, perfil.restricciones) }

        val segurosMotor = evaluaciones.filter { it.estado == EstadoSeguridad.SEGURO }.map { it.productoId }.toSet()
        val vista = instantanea.compatibles
            .filter { it.revisionPerfil == perfil.revisionPerfil }
            .filter { soloProductos == null || it.productoId in soloProductos }
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
