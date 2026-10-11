package aplicacion

import datos.DetalleProducto
import datos.FuenteConexiones
import datos.PersonalAutenticado
import datos.RepositorioCatalogo
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Fotos de la carta (RF02). La imagen se reduce a [LADO_MAXIMO] px y se guarda como JPEG: carga
 * rápido en 4G y pierde los metadatos de la cámara. El nombre lleva un hash del contenido, así que
 * se puede guardar en caché sin vencimiento.
 *
 * Cambiar la foto anula la validación del producto (trigger invalidar_datos_producto): cocina
 * revisa la ficha y vuelve a validarla.
 */
class ServicioFotos(private val fuente: FuenteConexiones, private val directorio: File, private val catalogo: ServicioCatalogo) {

    fun subir(personal: PersonalAutenticado, productoId: Long, contenido: ByteArray): DetalleProducto {
        personal.exigirRol("cocina")
        if (contenido.isEmpty() || contenido.size > TAMANO_MAXIMO) {
            throw SolicitudInvalida("foto_invalida", "La foto debe pesar como máximo ${TAMANO_MAXIMO / 1_000_000} MB")
        }
        val original = runCatching { ImageIO.read(ByteArrayInputStream(contenido)) }.getOrNull()
            ?: throw SolicitudInvalida("foto_invalida", "Formato no reconocido: usa JPEG o PNG")
        val jpeg = comoJpeg(reducir(original))

        val detalle = fuente.usar { conn ->
            val repo = RepositorioCatalogo(conn)
            val producto = repo.productoBackoffice(productoId) ?: throw NoEncontrado("producto_no_encontrado", "Producto inexistente")
            val nombre = "${producto.codigo.lowercase().replace(Regex("[^a-z0-9-]"), "-")}-${hash(jpeg)}.jpg"
            directorio.mkdirs()
            File(directorio, nombre).writeBytes(jpeg)
            repo.cambiarFoto(productoId, "$RUTA_PUBLICA/$nombre")
            repo.detalle(productoId)!!
        }
        catalogo.invalidar()
        return detalle
    }

    private fun reducir(imagen: BufferedImage): BufferedImage {
        val escala = minOf(1.0, LADO_MAXIMO.toDouble() / maxOf(imagen.width, imagen.height))
        val ancho = maxOf(1, (imagen.width * escala).toInt())
        val alto = maxOf(1, (imagen.height * escala).toInt())
        // Se dibuja sobre fondo blanco: JPEG no tiene transparencia.
        return BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB).also { destino ->
            val g = destino.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, ancho, alto)
            g.drawImage(imagen, 0, 0, ancho, alto, null)
            g.dispose()
        }
    }

    private fun comoJpeg(imagen: BufferedImage): ByteArray {
        val escritor = ImageIO.getImageWritersByFormatName("jpeg").next()
        val salida = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(salida).use { flujo ->
            escritor.output = flujo
            val parametros = escritor.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.82f
            }
            escritor.write(null, IIOImage(imagen, null, null), parametros)
        }
        escritor.dispose()
        return salida.toByteArray()
    }

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }

    companion object {
        const val RUTA_PUBLICA = "/fotos"
        const val LADO_MAXIMO = 800
        const val TAMANO_MAXIMO = 8_000_000
    }
}
