package aplicacion

import datos.CambioDisponibilidad
import datos.DetalleProducto
import datos.FuenteConexiones
import datos.Metricas
import datos.PersonalAutenticado
import datos.ProductoBackoffice
import datos.Puesto
import datos.RepositorioCatalogo
import datos.RepositorioMetricas
import datos.RepositorioPersonal
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.sql.SQLException
import java.util.Base64

@Serializable
data class PuestoConEnlaces(val puesto: Puesto, val enlaceNfc: String?, val enlaceQr: String?)

/** Back-office: disponibilidad (RF08), validación del catálogo, puestos NFC/QR y métricas del piloto. */
class ServicioBackoffice(
    private val fuente: FuenteConexiones,
    private val catalogo: ServicioCatalogo,
    private val urlPublica: String,
) {
    private val aleatorio = SecureRandom()

    fun productos(personal: PersonalAutenticado): List<ProductoBackoffice> {
        personal.exigirRol("cocina", "barra")
        return fuente.usar { RepositorioCatalogo(it).productosBackoffice() }
    }

    /** Se registra quién, cuándo y por qué; nunca se escribe productos.disponible directamente. */
    fun cambiarDisponibilidad(personal: PersonalAutenticado, productoId: Long, disponible: Boolean, motivo: String): ProductoBackoffice {
        if (motivo.isBlank()) throw SolicitudInvalida("motivo_requerido", "Indica el motivo del cambio")
        val actualizado = fuente.usar { conn ->
            val repo = RepositorioCatalogo(conn)
            val producto = repo.productoBackoffice(productoId) ?: throw NoEncontrado("producto_no_encontrado", "Producto inexistente")
            // Cocina gestiona sus platos y barra sus bebidas; administración, todo.
            personal.exigirRol(producto.destino)
            repo.cambiarDisponibilidad(productoId, disponible, personal.id, motivo.trim().take(200))
            repo.productoBackoffice(productoId)!!
        }
        catalogo.invalidar()
        return actualizado
    }

    fun historial(personal: PersonalAutenticado): List<CambioDisponibilidad> {
        personal.exigirRol("cocina", "barra")
        return fuente.usar { RepositorioCatalogo(it).historialDisponibilidad(100) }
    }

    fun detalle(personal: PersonalAutenticado, productoId: Long): DetalleProducto {
        personal.exigirRol("cocina")
        return fuente.usar { RepositorioCatalogo(it).detalle(productoId) } ?: throw NoEncontrado("producto_no_encontrado", "Producto inexistente")
    }

    /** Validación del catálogo por cocina o administración; los triggers verifican rol, textos e ingredientes. */
    fun validar(personal: PersonalAutenticado, productoId: Long): DetalleProducto {
        personal.exigirRol("cocina")
        val detalle = fuente.usar { conn ->
            val repo = RepositorioCatalogo(conn)
            repo.productoBackoffice(productoId) ?: throw NoEncontrado("producto_no_encontrado", "Producto inexistente")
            try {
                repo.validar(productoId, personal.id)
            } catch (e: SQLException) {
                throw Conflicto("validacion_rechazada", e.message ?: "Validación rechazada por la base")
            }
            repo.detalle(productoId)!!
        }
        catalogo.invalidar()
        return detalle
    }

    fun puestos(personal: PersonalAutenticado): List<PuestoConEnlaces> {
        personal.exigirRol()
        return fuente.usar { RepositorioPersonal(it).puestos() }.map(::conEnlaces)
    }

    /** Registra una mesa o puesto de barra con un token para el tag NFC y otro para el QR de respaldo. */
    fun crearPuesto(personal: PersonalAutenticado, codigo: String, tipo: String): PuestoConEnlaces {
        personal.exigirRol()
        if (tipo !in setOf("mesa", "barra")) throw SolicitudInvalida("tipo_invalido", "El tipo es mesa o barra")
        val limpio = codigo.trim()
        if (!Regex("^[A-Za-z0-9-]{1,20}$").matches(limpio)) throw SolicitudInvalida("codigo_invalido", "Código de 1 a 20 letras, números o guiones")
        return fuente.usar { conn ->
            val repo = RepositorioPersonal(conn)
            if (repo.existePuesto(limpio)) throw Conflicto("puesto_existente", "Ya existe el puesto $limpio")
            val id = repo.crearPuesto(limpio, tipo, token(), token())
            conEnlaces(repo.puestos().single { it.id == id })
        }
    }

    /** Activación por puesto: apagar uno devuelve esa mesa al pedido tradicional sin afectar a las demás. */
    fun cambiarActivoPuesto(personal: PersonalAutenticado, puestoId: Long, activo: Boolean): PuestoConEnlaces {
        personal.exigirRol()
        return fuente.usar { conn ->
            val repo = RepositorioPersonal(conn)
            if (repo.cambiarActivoPuesto(puestoId, activo) == 0) throw NoEncontrado("puesto_no_encontrado", "Puesto inexistente")
            conEnlaces(repo.puestos().single { it.id == puestoId })
        }
    }

    fun metricas(personal: PersonalAutenticado): Metricas {
        personal.exigirRol()
        return fuente.usar { RepositorioMetricas(it).metricas() }
    }

    private fun conEnlaces(puesto: Puesto) = PuestoConEnlaces(
        puesto,
        puesto.accesos.find { it.canal == "nfc" }?.let { "$urlPublica/m/${it.token}" },
        puesto.accesos.find { it.canal == "qr" }?.let { "$urlPublica/m/${it.token}" },
    )

    private fun token(): String = ByteArray(18).also(aleatorio::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}
