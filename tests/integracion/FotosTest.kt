package integracion

import aplicacion.Prohibido
import aplicacion.SolicitudInvalida
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import presentacion.moduloMaidoTouch
import soporte.Escenario
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fotos de la carta (RF02): subida, reducción, invalidación del producto y publicación. Imágenes FICTICIAS. */
class FotosTest {
    private val e = Escenario()

    @AfterTest
    fun cerrar() = e.close()

    private fun png(ancho: Int, alto: Int): ByteArray {
        val imagen = BufferedImage(ancho, alto, BufferedImage.TYPE_INT_ARGB)
        imagen.createGraphics().apply { color = Color(200, 80, 40); fillRect(0, 0, ancho, alto); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(imagen, "png", it) }.toByteArray()
    }

    @Test
    fun `la foto se reduce, se publica y obliga a revalidar el producto`() {
        val ceviche = e.producto("FICTICIO-P01")

        val detalle = e.app.fotos.subir(e.cocina, ceviche, png(2000, 1000))

        val url = detalle.producto.fotoUrl!!
        assertTrue(url.startsWith("/fotos/ficticio-p01-") && url.endsWith(".jpg"), url)
        val archivo = File(e.app.configuracion.directorioFotos, url.removePrefix("/fotos/"))
        val guardada = ImageIO.read(archivo)
        assertEquals(800 to 400, guardada.width to guardada.height)
        assertFalse(detalle.producto.validado, "Cambiar la foto anula la validación")
        assertFalse(ceviche in e.app.catalogo.cartaParaSesion(e.sesion()).productos.map { it.producto.id })

        e.app.backoffice.validar(e.cocina, ceviche)
        val enCarta = e.app.catalogo.cartaParaSesion(e.sesion()).productos.single { it.producto.id == ceviche }
        assertEquals(url, enCarta.producto.fotoUrl)
    }

    @Test
    fun `una foto pequena no se agranda`() {
        val detalle = e.app.fotos.subir(e.cocina, e.producto("FICTICIO-B01"), png(300, 200))

        val guardada = ImageIO.read(File(e.app.configuracion.directorioFotos, detalle.producto.fotoUrl!!.removePrefix("/fotos/")))
        assertEquals(300 to 200, guardada.width to guardada.height)
    }

    @Test
    fun `se rechazan archivos que no son imagenes y roles sin permiso`() {
        val ceviche = e.producto("FICTICIO-P01")

        assertFailsWith<SolicitudInvalida> { e.app.fotos.subir(e.cocina, ceviche, "no soy una imagen".toByteArray()) }
        assertFailsWith<SolicitudInvalida> { e.app.fotos.subir(e.cocina, ceviche, ByteArray(0)) }
        assertFailsWith<Prohibido> { e.app.fotos.subir(e.sala, ceviche, png(10, 10)) }
        assertFailsWith<Prohibido> { e.app.fotos.subir(e.barra, ceviche, png(10, 10)) }
    }

    @Test
    fun `la API recibe la foto y la sirve en fotos`() = testApplication {
        application { moduloMaidoTouch(e.app, programarTareas = false) }
        val token = e.app.personal.iniciarSesion("cocina-prueba", Escenario.CLAVE).token

        val respuesta = client.post("/api/backoffice/productos/${e.producto("FICTICIO-P04")}/foto") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Image.PNG)
            setBody(png(1200, 900))
        }
        assertEquals(HttpStatusCode.OK, respuesta.status)

        val url = e.app.backoffice.detalle(e.cocina, e.producto("FICTICIO-P04")).producto.fotoUrl!!
        val foto = client.get(url)
        assertEquals(HttpStatusCode.OK, foto.status)
        assertEquals(ContentType.Image.JPEG, foto.headers["Content-Type"]?.let(ContentType::parse)?.withoutParameters())
        assertEquals(800, ImageIO.read(ByteArrayInputStream(foto.readRawBytes())).width)
    }
}
