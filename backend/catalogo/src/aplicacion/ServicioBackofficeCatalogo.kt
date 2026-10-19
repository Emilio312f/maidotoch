package catalogo.aplicacion

import catalogo.datos.CambioDisponibilidad
import catalogo.datos.ProductoBackoffice
import catalogo.datos.RepositorioCatalogo
import catalogo.datos.TextoProducto
import comun.Conflicto
import comun.NoEncontrado
import comun.PersonalAutenticado
import comun.SolicitudInvalida
import comun.contratos.ApiSeguridad
import comun.contratos.ApiSesion
import comun.datos.FuenteConexiones
import comun.exigirRol
import kotlinx.serialization.Serializable
import java.sql.SQLException

@Serializable
data class AlergenoProducto(val alergeno: String, val origen: String)

@Serializable
data class EvaluacionRestriccion(val restriccion: String, val resultado: String, val evidencia: String, val validadoPor: String, val validadoEn: String)

@Serializable
data class DetalleProducto(
    val producto: ProductoBackoffice,
    val textos: List<TextoProducto>,
    val ingredientes: List<String>,
    val alergenos: List<AlergenoProducto>,
    val preferencias: List<String>,
    val evaluaciones: List<EvaluacionRestriccion>,
    val restriccionesSinEvaluar: List<String>,
    val validadoPor: String? = null,
    val validadoEn: String? = null,
)

/** Back-office del catálogo: disponibilidad (RF08) y validación de la carta por cocina. */
class ServicioBackofficeCatalogo(
    private val fuente: FuenteConexiones,
    private val catalogo: ServicioCatalogo,
    private val sesion: ApiSesion,
    private val seguridad: ApiSeguridad,
) {
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
        val cambios = fuente.usar { RepositorioCatalogo(it).historialDisponibilidad(100) }
        val nombres = sesion.identificadoresPersonal(cambios.map { it.registradoPorId })
        return cambios.map { it.cambio.copy(registradoPor = nombres[it.registradoPorId] ?: "#${it.registradoPorId}") }
    }

    fun detalle(personal: PersonalAutenticado, productoId: Long): DetalleProducto {
        personal.exigirRol("cocina")
        val datos = fuente.usar { conn ->
            val repo = RepositorioCatalogo(conn)
            val producto = repo.productoBackoffice(productoId) ?: throw NoEncontrado("producto_no_encontrado", "Producto inexistente")
            val (validadoPor, validadoEn) = repo.validacion(productoId)
            DatosProducto(producto, repo.textos(productoId), repo.preferenciasDeProducto(productoId), validadoPor, validadoEn)
        }
        // Ingredientes, alérgenos y evaluaciones son del Servicio de Seguridad Alimentaria.
        val ficha = seguridad.fichaSeguridad(productoId)
        val nombres = sesion.identificadoresPersonal(listOfNotNull(datos.validadoPor) + ficha.evaluaciones.map { it.validadoPor })
        return DetalleProducto(
            producto = datos.producto,
            textos = datos.textos,
            ingredientes = ficha.ingredientes,
            alergenos = ficha.alergenos.map { AlergenoProducto(it.alergeno, it.origen) },
            preferencias = datos.preferencias,
            evaluaciones = ficha.evaluaciones.map {
                EvaluacionRestriccion(it.restriccion, it.resultado, it.evidencia, nombres[it.validadoPor] ?: "#${it.validadoPor}", it.validadoEn)
            },
            restriccionesSinEvaluar = ficha.restriccionesSinEvaluar,
            validadoPor = datos.validadoPor?.let { nombres[it] ?: "#$it" },
            validadoEn = datos.validadoEn,
        )
    }

    /** Validación del catálogo por cocina o administración; los triggers verifican rol, textos e ingredientes. */
    fun validar(personal: PersonalAutenticado, productoId: Long): DetalleProducto {
        personal.exigirRol("cocina")
        fuente.usar { conn ->
            val repo = RepositorioCatalogo(conn)
            repo.productoBackoffice(productoId) ?: throw NoEncontrado("producto_no_encontrado", "Producto inexistente")
            try {
                repo.validar(productoId, personal.id)
            } catch (e: SQLException) {
                throw Conflicto("validacion_rechazada", e.message ?: "Validación rechazada por la base")
            }
        }
        catalogo.invalidar()
        return detalle(personal, productoId)
    }

    private data class DatosProducto(
        val producto: ProductoBackoffice,
        val textos: List<TextoProducto>,
        val preferencias: List<String>,
        val validadoPor: Long?,
        val validadoEn: String?,
    )
}
