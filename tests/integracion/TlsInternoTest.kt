package integracion

import comun.Servicio
import comun.Tls
import soporte.Escenario
import soporte.SistemaHttp
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Tráfico interno entre servicios con TLS (Diseño v1.1 §9), con un certificado FICTICIO de prueba. */
class TlsInternoTest {
    private val e = Escenario()
    private val directorio = Files.createTempDirectory("maidotouch-tls").toFile()

    @AfterTest
    fun cerrar() {
        e.close()
        directorio.deleteRecursively()
    }

    /** Mismo procedimiento que ops/preparar-servidor.sh. */
    private fun almacen(): Tls {
        val archivo = File(directorio, "tls-interno.p12")
        val clave = "clave-tls-ficticia"
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val proceso = ProcessBuilder(
            keytool, "-genkeypair", "-alias", Tls.ALIAS, "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
            "-dname", "CN=maidotouch-prueba", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
            "-storetype", "PKCS12", "-keystore", archivo.absolutePath, "-storepass", clave, "-keypass", clave,
        ).redirectErrorStream(true).start()
        assertEquals(0, proceso.waitFor(), proceso.inputStream.bufferedReader().readText())
        return Tls(archivo.absolutePath, clave)
    }

    @Test
    fun `los servicios se hablan por TLS y no aceptan HTTP plano`() {
        SistemaHttp(e.db, tls = almacen()).use { sistema ->
            val salud = sistema.pedir("GET", "/api/salud")
            assertEquals(200, salud.statusCode(), salud.body())

            val sesion = e.sesion(restricciones = listOf("FICTICIO-alergia-mani"))
            val asistente = sistema.pedir("POST", "/api/sesion/asistente", """{"consulta":"algo"}""", mapOf("X-Sesion" to sesion))
            assertEquals(200, asistente.statusCode(), asistente.body())

            val sinTls = sistema.url(Servicio.SEGURIDAD).replace("https://", "http://")
            assertFailsWith<IOException> {
                sistema.http.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("$sinTls/salud")).timeout(java.time.Duration.ofSeconds(3)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(),
                )
            }
        }
    }
}
