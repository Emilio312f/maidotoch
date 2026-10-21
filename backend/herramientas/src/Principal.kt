package herramientas

import comun.Entorno
import comun.ErrorNegocio
import comun.PersonalAutenticado
import comun.Servicio
import comun.Topologia
import comun.contratos.CuentaPeticion
import comun.contratos.DisponibilidadPeticion
import comun.contratos.ImportarCatalogoPeticion
import comun.contratos.Ok
import comun.contratos.PersonalResumen
import comun.contratos.PuestoPeticion
import comun.contratos.ResumenImportacion
import comun.contratos.TextoPlano
import comun.datos.ArchivoSqlite
import comun.datos.BaseDatos
import comun.datos.Migraciones
import comun.datos.consultar
import comun.http.CABECERA_PERSONAL
import comun.http.ClienteInterno
import comun.http.codificarPersonal
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.system.exitProcess

private const val AYUDA = """
Uso: maidotouch-herramientas <comando>

  migrar                                  Respalda la base y aplica las migraciones pendientes (antes de levantar los servicios)
  tokens                                  Genera tokens de servicio nuevos (líneas MAIDOTOUCH_TOKEN_*=...)
  crear-personal <identificador> <rol>    Crea una cuenta del back-office (rol: administrador, cocina, barra, sala)
  cambiar-clave <identificador>           Cambia la clave de una cuenta
  importar-catalogo <archivo.json> <evaluador>
                                          Carga el catálogo validado por cocina; <evaluador> es el identificador
                                          de cocina que hizo las evaluaciones de restricciones
  verificar                               Revisa los criterios de Go/No-Go comprobables (sale con código 1 si alguno falla)
  reporte-diario [AAAA-MM-DD]             Resumen del día (hora de Lima) para el monitoreo del piloto
  demo [catalogo.json]                    Base de desarrollo o de demostración: cuentas demo, el catálogo indicado
                                          (por defecto db/catalogo/ficticio.json) validado y disponible, y tres puestos

Salvo migrar y tokens, los comandos hablan con los servicios en ejecución (usan las URL y los tokens de
MAIDOTOUCH_URL_* y MAIDOTOUCH_TOKEN_*). La clave se lee de MAIDOTOUCH_CLAVE o se pide por consola.
"""

@Serializable
private data class ChequeoCli(val criterio: String, val resultado: String, val detalle: String)

@Serializable
private data class VerificacionCli(val chequeos: List<ChequeoCli>, val manuales: List<String>, val apto: Boolean)

@Serializable
private data class CambioClave(val identificador: String, val clave: String)

@Serializable
private data class ProductoDemo(val id: Long, val codigo: String, val validado: Boolean, val disponible: Boolean)

@Serializable
private data class AccesoDemo(val canal: String, val token: String)

@Serializable
private data class PuestoDemo(val codigo: String, val accesos: List<AccesoDemo>)

@Serializable
private data class PuestoConEnlacesDemo(val puesto: PuestoDemo, val enlaceNfc: String? = null)

fun main(args: Array<String>) {
    try {
        when (args.firstOrNull() ?: "ayuda") {
            "migrar" -> migrar(Entorno.rutaBaseDatos(), Entorno.texto("MAIDOTOUCH_MIGRACIONES_DIR") ?: "db/migrations")
            "tokens" -> Servicio.entries.forEach { println("MAIDOTOUCH_TOKEN_${it.sufijo}=${aleatorio(32)}") }
            "crear-personal" -> {
                val (identificador, rol) = argumentos(args, 2)
                Operacion().sesion.post<CuentaPeticion, Ok>("/interno/personal", CuentaPeticion(identificador, rol, leerClave()))
                println("Cuenta $identificador ($rol) creada.")
            }
            "cambiar-clave" -> {
                val (identificador) = argumentos(args, 1)
                Operacion().sesion.post<CambioClave, Ok>("/interno/personal/clave", CambioClave(identificador, leerClave()))
                println("Clave de $identificador actualizada.")
            }
            "importar-catalogo" -> {
                val (archivo, evaluador) = argumentos(args, 2)
                val resumen = Operacion().importar(File(archivo).readText(), evaluador)
                println("Productos nuevos: ${resumen.productosNuevos}, modificados: ${resumen.productosModificados}, sin cambios: ${resumen.productosSinCambios}")
                if (resumen.productosPorValidar.isNotEmpty()) {
                    println("Pendientes de validación por cocina en el back-office: ${resumen.productosPorValidar.joinToString()}")
                }
            }
            "verificar" -> {
                val verificacion = Operacion().piloto.get<VerificacionCli>("/interno/piloto/verificacion")
                verificacion.chequeos.forEach { println("[%-5s] %s — %s".format(it.resultado, it.criterio, it.detalle)) }
                println("\nVerificar a mano antes del Go/No-Go:")
                verificacion.manuales.forEach { println("  [ ] $it") }
                if (!verificacion.apto) {
                    println("\nNO-GO: hay criterios en FALLA.")
                    exitProcess(1)
                }
                println("\nSin fallas en los criterios comprobables.")
            }
            "reporte-diario" -> {
                val fecha = args.getOrNull(1)?.let { "?fecha=$it" }.orEmpty()
                print(Operacion().piloto.get<TextoPlano>("/interno/piloto/reporte-diario$fecha").texto)
            }
            "demo" -> demo(args.getOrNull(1) ?: "db/catalogo/ficticio.json")
            "ayuda", "--help", "-h" -> println(AYUDA.trimIndent())
            else -> {
                System.err.println(AYUDA.trimIndent())
                exitProcess(2)
            }
        }
    } catch (e: ErrorNegocio) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}

/** Clientes hacia los servicios en ejecución, con sus tokens. */
private class Operacion(topologia: Topologia = Topologia.desdeEntorno()) {
    private val http = ClienteInterno.http(topologia.tls)
    val sesion = ClienteInterno.para(Servicio.SESION, topologia, http, 30_000)
    val catalogo = ClienteInterno.para(Servicio.CATALOGO, topologia, http, 60_000)
    val piloto = ClienteInterno.para(Servicio.PILOTO, topologia, http, 30_000)

    fun importar(contenido: String, evaluador: String): ResumenImportacion =
        catalogo.post("/interno/catalogo/importar", ImportarCatalogoPeticion(contenido, evaluador))

    /** Identidad del personal para usar las rutas del back-office desde la consola. */
    fun como(identificador: String): Map<String, String> {
        val p = sesion.get<PersonalResumen>("/interno/personal/por-identificador/$identificador")
        return mapOf(CABECERA_PERSONAL to codificarPersonal(PersonalAutenticado(p.id, p.identificador, p.rol)))
    }
}

private fun argumentos(args: Array<String>, cantidad: Int): List<String> {
    if (args.size != cantidad + 1) {
        System.err.println(AYUDA.trimIndent())
        exitProcess(2)
    }
    return args.drop(1)
}

private fun leerClave(): String {
    System.getenv("MAIDOTOUCH_CLAVE")?.takeIf { it.isNotEmpty() }?.let { return it }
    val consola = System.console() ?: error("Define MAIDOTOUCH_CLAVE o ejecuta el comando en una terminal")
    val clave = String(consola.readPassword("Clave (mínimo 10 caracteres): "))
    val repetida = String(consola.readPassword("Repite la clave: "))
    check(clave == repetida) { "Las claves no coinciden" }
    return clave
}

private fun aleatorio(bytes: Int): String =
    ByteArray(bytes).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

/** Antes de aplicar migraciones sobre una base existente se respalda el archivo SQLite (Plan §6.3). */
fun migrar(rutaBase: String, directorioMigraciones: String) {
    val archivo = File(rutaBase)
    val directorio = Path.of(directorioMigraciones)
    val disponibles = Files.list(directorio).use { s -> s.toList().mapNotNull { Regex("^(\\d{3})_.+\\.sql$").find(it.fileName.toString())?.groupValues?.get(1)?.toInt() } }
    val ultima = disponibles.maxOrNull() ?: 0
    val actual = if (archivo.exists()) BaseDatos.conectar("jdbc:sqlite:${archivo.path}").use { Migraciones.versionActual(it) } else 0
    if (actual < ultima) {
        if (archivo.exists() && archivo.length() > 0) {
            val respaldo = File("${archivo.path}.respaldo-${Instant.now().toString().replace(":", "-")}")
            Files.copy(archivo.toPath(), respaldo.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            println("Respaldo creado: ${respaldo.path}")
        }
        BaseDatos.conectar("jdbc:sqlite:${archivo.path}").use { Migraciones.aplicar(it, directorio) }
    }
    // WAL: varios procesos (un servicio por proceso) leen mientras otro escribe. También tras restaurar un respaldo.
    ArchivoSqlite(archivo.path).activarWal()
    println("Base en la versión $ultima (${archivo.path}).")
}

/**
 * Base de desarrollo o de demostración: cuentas demo, el catálogo [archivo] validado por cocina-demo y
 * disponible, y tres puestos. Se niega a correr si la base ya tiene productos que no son de ese archivo,
 * para no tocar nunca una base con el catálogo cargado por el Responsable de Datos.
 */
private fun demo(archivo: String) {
    val contenido = File(archivo).readText()
    val codigos = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        .decodeFromString(comun.contratos.ArchivoCatalogo.serializer(), contenido).productos.map { it.codigo }.toSet()
    val ajenos = ArchivoSqlite(Entorno.rutaBaseDatos()).usar { conn ->
        conn.consultar("SELECT codigo FROM productos") { it.getString(1) }.filter { it !in codigos }
    }
    check(ajenos.isEmpty()) { "La base tiene productos que no son de $archivo (${ajenos.take(5).joinToString()}); usa una base nueva" }

    val op = Operacion()
    val clave = System.getenv("MAIDOTOUCH_CLAVE")?.takeIf { it.length >= 10 } ?: aleatorio(12)
    val cuentas = listOf("admin-demo" to "administrador", "cocina-demo" to "cocina", "barra-demo" to "barra", "sala-demo" to "sala")
    cuentas.forEach { (identificador, rol) ->
        try {
            op.sesion.post<CuentaPeticion, Ok>("/interno/personal", CuentaPeticion(identificador, rol, clave))
        } catch (e: ErrorNegocio) {
            if (e.codigo != "identificador_existente") throw e
            op.sesion.post<CambioClave, Ok>("/interno/personal/clave", CambioClave(identificador, clave))
        }
    }

    op.importar(contenido, "cocina-demo")
    val cocina = op.como("cocina-demo")
    val admin = op.como("admin-demo")
    op.catalogo.get<List<ProductoDemo>>("/api/backoffice/productos", cocina).filter { it.codigo in codigos }.forEach { producto ->
        if (!producto.validado) op.catalogo.enviar("POST", "/api/backoffice/productos/${producto.id}/validar", null, kotlinx.serialization.json.JsonElement.serializer(), cocina)
        if (!producto.disponible) {
            op.catalogo.post<DisponibilidadPeticion, kotlinx.serialization.json.JsonElement>(
                "/api/backoffice/productos/${producto.id}/disponibilidad", DisponibilidadPeticion(true, "Carga inicial del catálogo de demostración"), admin,
            )
        }
    }

    val existentes = op.sesion.get<List<PuestoConEnlacesDemo>>("/api/backoffice/puestos", admin).map { it.puesto.codigo }.toSet()
    listOf("M1" to "mesa", "M2" to "mesa", "B1" to "barra").forEach { (codigo, tipo) ->
        if (codigo !in existentes) op.sesion.post<PuestoPeticion, PuestoConEnlacesDemo>("/api/backoffice/puestos", PuestoPeticion(codigo, tipo), admin)
    }

    println("Catálogo $archivo (${codigos.size} productos) validado y disponible en ${Entorno.rutaBaseDatos()}.")
    println("Cuentas del back-office (${cuentas.joinToString { it.first }}), clave: $clave")
    op.sesion.get<List<PuestoConEnlacesDemo>>("/api/backoffice/puestos", admin).forEach { println("  ${it.puesto.codigo}: ${it.enlaceNfc}") }
}
