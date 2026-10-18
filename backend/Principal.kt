import aplicacion.ErrorNegocio
import aplicacion.ResultadoChequeo
import aplicacion.ServicioPiloto
import java.time.LocalDate
import java.time.ZoneId
import configuracion.Configuracion
import datos.ArchivoSqlite
import datos.BaseDatos
import datos.Migraciones
import datos.RepositorioCatalogo
import datos.RepositorioPersonal
import datos.consultar
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import presentacion.Aplicacion
import presentacion.moduloMaidoTouch
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.system.exitProcess

private const val AYUDA = """
Uso: maidotouch <comando>

  servir                                  Aplica migraciones pendientes y levanta la API y la web-app (por defecto)
  migrar                                  Respalda la base y aplica las migraciones pendientes
  crear-personal <identificador> <rol>    Crea una cuenta del back-office (rol: administrador, cocina, barra, sala)
  cambiar-clave <identificador>           Cambia la clave de una cuenta
  importar-catalogo <archivo.json> <evaluador>
                                          Carga el catálogo validado por cocina; <evaluador> es el identificador
                                          de cocina que hizo las evaluaciones de restricciones
  verificar                               Revisa los criterios de Go/No-Go comprobables (sale con código 1 si alguno falla)
  reporte-diario [AAAA-MM-DD]             Resumen del día (hora de Lima) para el monitoreo del piloto
  demo                                    Crea datos FICTICIOS de desarrollo en una base vacía

La clave se lee de MAIDOTOUCH_CLAVE o se pide por consola. Ver .env.example para la configuración.
"""

fun main(args: Array<String>) {
    val configuracion = Configuracion.desdeEntorno()
    try {
        when (args.firstOrNull() ?: "servir") {
            "servir" -> servir(configuracion)
            "migrar" -> migrar(configuracion)
            "crear-personal" -> {
                val (identificador, rol) = argumentos(args, 2)
                migrar(configuracion)
                Aplicacion(configuracion, ArchivoSqlite(configuracion.rutaBaseDatos), null).personal.crear(identificador, rol, leerClave())
                println("Cuenta $identificador ($rol) creada.")
            }
            "cambiar-clave" -> {
                val (identificador) = argumentos(args, 1)
                Aplicacion(configuracion, ArchivoSqlite(configuracion.rutaBaseDatos), null).personal.cambiarClave(identificador, leerClave())
                println("Clave de $identificador actualizada.")
            }
            "importar-catalogo" -> {
                val (archivo, evaluador) = argumentos(args, 2)
                migrar(configuracion)
                val resumen = Aplicacion(configuracion, ArchivoSqlite(configuracion.rutaBaseDatos), null)
                    .importador.importar(File(archivo).readText(), evaluador)
                println("Productos nuevos: ${resumen.productosNuevos}, modificados: ${resumen.productosModificados}, sin cambios: ${resumen.productosSinCambios}")
                if (resumen.productosPorValidar.isNotEmpty()) {
                    println("Pendientes de validación por cocina en el back-office: ${resumen.productosPorValidar.joinToString()}")
                }
            }
            "verificar" -> {
                migrar(configuracion)
                val chequeos = Aplicacion(configuracion, ArchivoSqlite(configuracion.rutaBaseDatos), null).piloto.verificar()
                chequeos.forEach { println("[%-5s] %s — %s".format(it.resultado, it.criterio, it.detalle)) }
                println("\nVerificar a mano antes del Go/No-Go:")
                ServicioPiloto.VERIFICACIONES_MANUALES.forEach { println("  [ ] $it") }
                if (chequeos.any { it.resultado == ResultadoChequeo.FALLA }) {
                    println("\nNO-GO: hay criterios en FALLA.")
                    exitProcess(1)
                }
                println("\nSin fallas en los criterios comprobables.")
            }
            "reporte-diario" -> {
                val dia = args.getOrNull(1)?.let(LocalDate::parse) ?: LocalDate.now(ZoneId.of("America/Lima"))
                print(Aplicacion(configuracion, ArchivoSqlite(configuracion.rutaBaseDatos), null).piloto.reporteDiario(dia).aTexto())
            }
            "demo" -> demo(configuracion)
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

/** Antes de aplicar migraciones sobre una base existente se respalda el archivo SQLite (Plan §6.3). */
private fun migrar(configuracion: Configuracion) {
    val archivo = File(configuracion.rutaBaseDatos)
    val directorio = Path.of(configuracion.directorioMigraciones)
    val disponibles = Files.list(directorio).use { s -> s.toList().mapNotNull { Regex("^(\\d{3})_.+\\.sql$").find(it.fileName.toString())?.groupValues?.get(1)?.toInt() } }
    val ultima = disponibles.maxOrNull() ?: 0
    val actual = if (archivo.exists()) BaseDatos.conectar("jdbc:sqlite:${archivo.path}").use { Migraciones.versionActual(it) } else 0
    if (actual >= ultima) return
    if (archivo.exists() && archivo.length() > 0) {
        val respaldo = File("${archivo.path}.respaldo-${Instant.now().toString().replace(":", "-")}")
        Files.copy(archivo.toPath(), respaldo.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        println("Respaldo creado: ${respaldo.path}")
    }
    BaseDatos.conectar("jdbc:sqlite:${archivo.path}").use { Migraciones.aplicar(it, directorio) }
    ArchivoSqlite(archivo.path).activarWal()
    println("Base en la versión $ultima (${archivo.path}).")
}

private fun servir(configuracion: Configuracion) {
    migrar(configuracion)
    // También tras restaurar un respaldo, que se guarda en modo de diario clásico.
    ArchivoSqlite(configuracion.rutaBaseDatos).activarWal()
    val app = Aplicacion(configuracion, ArchivoSqlite(configuracion.rutaBaseDatos))
    println("MaidoTouch ${configuracion.version} en el puerto ${configuracion.puerto}")
    if (app.ordenador == null) println("ANTHROPIC_API_KEY no definida: el asistente usará solo el ranking por reglas.")
    // HTTP local: en staging y producción el TLS lo termina el proxy inverso del servidor (ver README).
    embeddedServer(CIO, port = configuracion.puerto, host = "0.0.0.0") { moduloMaidoTouch(app) }.start(wait = true)
}

/**
 * Datos de DESARROLLO, todos ficticios: cuentas demo, el catálogo db/catalogo/ficticio.json validado y
 * disponible, y tres puestos. Se niega a correr si la base ya tiene productos que no son ficticios.
 */
private fun demo(configuracion: Configuracion) {
    migrar(configuracion)
    val fuente = ArchivoSqlite(configuracion.rutaBaseDatos)
    val reales = fuente.usar { conn -> conn.consultar("SELECT count(*) FROM productos WHERE codigo NOT LIKE 'FICTICIO-%'") { it.getInt(1) }.single() }
    check(reales == 0) { "La base tiene productos reales; el comando demo solo se usa en una base de desarrollo" }

    val app = Aplicacion(configuracion, fuente, null)
    val clave = System.getenv("MAIDOTOUCH_CLAVE")?.takeIf { it.length >= 10 }
        ?: ByteArray(12).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    val cuentas = listOf("admin-demo" to "administrador", "cocina-demo" to "cocina", "barra-demo" to "barra", "sala-demo" to "sala")
    cuentas.forEach { (identificador, rol) ->
        val existe = fuente.usar { RepositorioPersonal(it).buscar(identificador) } != null
        if (existe) app.personal.cambiarClave(identificador, clave) else app.personal.crear(identificador, rol, clave)
    }

    val catalogo = File("db/catalogo/ficticio.json").readText()
    app.importador.importar(catalogo, "cocina-demo")
    val cocina = fuente.usar { RepositorioPersonal(it).buscar("cocina-demo")!!.id }
    fuente.usar { conn ->
        val repo = RepositorioCatalogo(conn)
        repo.productosBackoffice().filter { it.codigo.startsWith("FICTICIO-") }.forEach { producto ->
            if (!producto.validado) repo.validar(producto.id, cocina)
            if (!producto.disponible) repo.cambiarDisponibilidad(producto.id, true, cocina, "FICTICIO: datos de demostración")
        }
    }

    val admin = app.personal.autenticar(app.personal.iniciarSesion("admin-demo", clave).token)
    listOf("FICTICIO-M1" to "mesa", "FICTICIO-M2" to "mesa", "FICTICIO-B1" to "barra").forEach { (codigo, tipo) ->
        val existe = fuente.usar { RepositorioPersonal(it).existePuesto(codigo) }
        if (!existe) app.backoffice.crearPuesto(admin, codigo, tipo)
    }

    println("Datos FICTICIOS de desarrollo listos en ${configuracion.rutaBaseDatos}.")
    println("Cuentas del back-office (${cuentas.joinToString { it.first }}), clave: $clave")
    app.backoffice.puestos(admin).forEach { println("  ${it.puesto.codigo}: ${it.enlaceNfc}") }
}
