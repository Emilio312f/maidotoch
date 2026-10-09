package aplicacion

import datos.Comanda
import datos.FuenteConexiones
import datos.Llamado
import datos.PersonalAutenticado
import datos.RepositorioOperacion
import datos.consultar
import datos.enTransaccion
import java.sql.SQLException

/** Servicio de Atención (RF06 · RF14): llamados al mozo desde cualquier pantalla. */
class ServicioAtencion(private val fuente: FuenteConexiones) {
    private val motivos = setOf("asistencia", "restriccion_incierta", "fallo_ia")

    fun llamar(sesionId: String, motivo: String, productoId: Long?): Llamado {
        if (motivo !in motivos) throw SolicitudInvalida("motivo_invalido", "Motivo no válido: $motivo")
        return fuente.usar { conn ->
            ServicioSesion.sesionAbierta(conn, sesionId)
            if (productoId != null && conn.consultar("SELECT 1 FROM productos WHERE id = ?", productoId) { true }.isEmpty()) {
                throw SolicitudInvalida("producto_desconocido", "Producto inexistente")
            }
            val repo = RepositorioOperacion(conn)
            // Un llamado pendiente con el mismo motivo no se duplica.
            val id = repo.llamadoPendiente(sesionId, motivo) ?: repo.crearLlamado(sesionId, motivo, productoId)
            repo.llamado(id)!!
        }
    }

    fun llamadosDeSesion(sesionId: String): List<Llamado> = fuente.usar { conn ->
        ServicioSesion.sesionAbierta(conn, sesionId)
        RepositorioOperacion(conn).llamadosDeSesion(sesionId)
    }

    fun pendientes(personal: PersonalAutenticado): List<Llamado> {
        personal.exigirRol("sala")
        return fuente.usar { RepositorioOperacion(it).llamadosPendientes() }
    }

    fun atender(personal: PersonalAutenticado, llamadoId: Long): Llamado {
        personal.exigirRol("sala")
        return fuente.usar { conn ->
            val repo = RepositorioOperacion(conn)
            repo.llamado(llamadoId) ?: throw NoEncontrado("llamado_no_encontrado", "Llamado inexistente")
            repo.atenderLlamado(llamadoId, personal.id)
            repo.llamado(llamadoId)!!
        }
    }
}

/**
 * Comandas a cocina/barra (RF05). La tabla comandas es la bandeja: la pantalla de cada destino las
 * consulta, cada consulta de una comanda pendiente cuenta como intento de entrega, y la comanda queda
 * pendiente hasta que la pantalla confirma la recepción.
 */
class ServicioComandas(private val fuente: FuenteConexiones) {
    private val destinos = setOf("cocina", "barra")
    private val estados = setOf("recibida", "preparando", "lista", "entregada")

    fun activas(personal: PersonalAutenticado, destino: String): List<Comanda> {
        exigirDestino(personal, destino)
        return fuente.usar { conn ->
            val repo = RepositorioOperacion(conn)
            repo.registrarIntentos(destino)
            repo.comandasActivas(destino)
        }
    }

    fun avanzar(personal: PersonalAutenticado, comandaId: Long, estado: String) {
        if (estado !in estados) throw SolicitudInvalida("estado_invalido", "Estado no válido: $estado")
        fuente.usar { conn ->
            val repo = RepositorioOperacion(conn)
            val (pedidoId, destino) = repo.comanda(comandaId) ?: throw NoEncontrado("comanda_no_encontrada", "Comanda inexistente")
            exigirDestino(personal, destino)
            try {
                conn.enTransaccion {
                    repo.cambiarEstadoComanda(comandaId, estado)
                    repo.sincronizarPedido(pedidoId)
                }
            } catch (e: SQLException) {
                throw Conflicto("transicion_invalida", e.message ?: "Transición de comanda no permitida")
            }
        }
    }

    private fun exigirDestino(personal: PersonalAutenticado, destino: String) {
        if (destino !in destinos) throw SolicitudInvalida("destino_invalido", "Destino no válido: $destino")
        personal.exigirRol(destino)
    }
}
