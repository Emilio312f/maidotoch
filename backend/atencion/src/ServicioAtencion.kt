package atencion

import comun.NoEncontrado
import comun.PersonalAutenticado
import comun.SolicitudInvalida
import comun.contratos.ApiCatalogo
import comun.contratos.ApiSesion
import comun.contratos.Evento
import comun.contratos.PublicadorEventos
import comun.datos.FuenteConexiones
import comun.exigirRol

/**
 * Servicio de Atención (RF06 · RF14): llamados al mozo desde cualquier pantalla y derivaciones por
 * restricción no validable. Cada llamado se publica en el bus para la pantalla de sala.
 */
class ServicioAtencion(
    private val fuente: FuenteConexiones,
    private val sesion: ApiSesion,
    private val catalogo: ApiCatalogo,
    private val eventos: PublicadorEventos,
) {
    private val motivos = setOf("asistencia", "restriccion_incierta", "fallo_ia")

    fun llamar(sesionId: String, motivo: String, productoId: Long?): Llamado {
        if (motivo !in motivos) throw SolicitudInvalida("motivo_invalido", "Motivo no válido: $motivo")
        val estado = sesion.sesionAbierta(sesionId)
        if (productoId != null && !catalogo.existe(productoId)) {
            throw SolicitudInvalida("producto_desconocido", "Producto inexistente")
        }
        val (registrado, nuevo) = fuente.usar { conn ->
            val repo = RepositorioLlamados(conn)
            // Un llamado pendiente con el mismo motivo no se duplica.
            val pendiente = repo.llamadoPendiente(sesionId, motivo)
            val id = pendiente ?: repo.crearLlamado(sesionId, motivo, productoId)
            repo.llamado(id)!! to (pendiente == null)
        }
        if (nuevo) eventos.publicar(Evento("sala", Evento.LLAMADO_NUEVO, registrado.llamado.id.toString()))
        return completar(listOf(registrado), estado.idioma).single()
    }

    fun llamadosDeSesion(sesionId: String): List<Llamado> {
        val estado = sesion.sesionAbierta(sesionId)
        return completar(fuente.usar { RepositorioLlamados(it).llamadosDeSesion(sesionId) }, estado.idioma)
    }

    fun pendientes(personal: PersonalAutenticado): List<Llamado> {
        personal.exigirRol("sala")
        return completar(fuente.usar { RepositorioLlamados(it).llamadosPendientes() }, "es")
    }

    fun atender(personal: PersonalAutenticado, llamadoId: Long): Llamado {
        personal.exigirRol("sala")
        val registrado = fuente.usar { conn ->
            val repo = RepositorioLlamados(conn)
            repo.llamado(llamadoId) ?: throw NoEncontrado("llamado_no_encontrado", "Llamado inexistente")
            repo.atenderLlamado(llamadoId, personal.id)
            repo.llamado(llamadoId)!!
        }
        eventos.publicar(Evento("sala", Evento.LLAMADO_ATENDIDO, llamadoId.toString()))
        return completar(listOf(registrado), "es").single()
    }

    /** Agrega el código de puesto (Sesión) y el nombre del producto (Catálogo). */
    private fun completar(llamados: List<LlamadoRegistrado>, idioma: String): List<Llamado> {
        if (llamados.isEmpty()) return emptyList()
        val puestos = sesion.puestosDeSesiones(llamados.map { it.sesionId })
        val productos = catalogo.nombres(llamados.mapNotNull { it.productoId }, idioma)
        return llamados.map { r ->
            r.llamado.copy(puesto = puestos[r.sesionId] ?: "?", producto = r.productoId?.let { productos[it] }?.ifEmpty { null })
        }
    }
}
