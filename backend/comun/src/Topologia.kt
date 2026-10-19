package comun

import java.io.File

/** Servicios del Diseño Arquitectónico v1.1, con el puerto local por defecto de cada uno. */
enum class Servicio(val clave: String, val puertoPorDefecto: Int) {
    GATEWAY("gateway", 8080),
    SESION("sesion", 8101),
    CATALOGO("catalogo", 8102),
    SEGURIDAD("seguridad-alimentaria", 8103),
    RECOMENDACION("recomendacion", 8104),
    ADAPTADOR_IA("adaptador-ia", 8105),
    PEDIDO("pedido", 8106),
    ATENCION("atencion", 8107),
    TRAZABILIDAD("trazabilidad", 8108),
    PILOTO("piloto", 8109),
    BUS("bus", 8110),
    ;

    /** Sufijo de las variables de entorno: MAIDOTOUCH_URL_<SUFIJO> y MAIDOTOUCH_TOKEN_<SUFIJO>. */
    val sufijo: String get() = clave.uppercase().replace('-', '_')
}

/** Almacén PKCS12 con el certificado del tráfico interno entre servicios (Diseño v1.1 §9). */
data class Tls(val almacen: String, val clave: String) {
    companion object {
        const val ALIAS = "maidotouch"
    }
}

/**
 * Dónde escucha cada servicio y con qué token se le habla. Cada servicio valida un token emitido
 * para él; los tokens viven solo en variables de entorno, nunca en el repositorio.
 */
data class Topologia(
    val urls: Map<Servicio, String>,
    private val tokens: Map<Servicio, String>,
    val tls: Tls? = null,
) {
    fun url(servicio: Servicio): String = urls.getValue(servicio)

    fun token(servicio: Servicio): String = tokens[servicio]
        ?: error("Falta MAIDOTOUCH_TOKEN_${servicio.sufijo}: genera los tokens con ops/preparar-servidor.sh, scripts/desarrollo.sh o maidotouch-herramientas tokens")

    companion object {
        const val LARGO_MINIMO_TOKEN = 32

        fun desdeEntorno(entorno: Map<String, String> = System.getenv()): Topologia {
            fun texto(nombre: String) = entorno[nombre]?.trim()?.takeIf { it.isNotEmpty() }
            val tokens = Servicio.entries.mapNotNull { s -> texto("MAIDOTOUCH_TOKEN_${s.sufijo}")?.let { s to it } }.toMap()
            tokens.forEach { (s, t) ->
                require(t.length >= LARGO_MINIMO_TOKEN) { "MAIDOTOUCH_TOKEN_${s.sufijo} debe tener al menos $LARGO_MINIMO_TOKEN caracteres" }
            }
            val almacen = texto("MAIDOTOUCH_TLS_ALMACEN")
            return Topologia(
                urls = Servicio.entries.associateWith { s ->
                    (texto("MAIDOTOUCH_URL_${s.sufijo}") ?: "http://127.0.0.1:${s.puertoPorDefecto}").trimEnd('/')
                },
                tokens = tokens,
                tls = almacen?.let { Tls(it, texto("MAIDOTOUCH_TLS_CLAVE") ?: error("Falta MAIDOTOUCH_TLS_CLAVE")) },
            )
        }
    }
}

/** Valores que comparten todos los servicios. */
object Entorno {
    fun texto(nombre: String, entorno: Map<String, String> = System.getenv()): String? =
        entorno[nombre]?.trim()?.takeIf { it.isNotEmpty() }

    /** Archivo SQLite con el esquema de seguridad alimentaria (ver docs/diseno-arquitectonico-v1.1.md, §Datos). */
    fun rutaBaseDatos(entorno: Map<String, String> = System.getenv()): String = texto("MAIDOTOUCH_DB_PATH", entorno) ?: "maidotouch.db"

    /** Tiempo máximo de espera al proveedor de IA antes del fallback (RNF04 · RPR05): 5 s por defecto. */
    fun timeoutIaMs(entorno: Map<String, String> = System.getenv()): Long = texto("MAIDOTOUCH_IA_TIMEOUT_MS", entorno)?.toLong() ?: 5_000

    /** Versión desplegada (archivo VERSION del paquete); el despliegue la usa para verificar el arranque. */
    fun version(): String = sequenceOf(File("VERSION"), File("../VERSION"))
        .firstOrNull { it.isFile }?.readText()?.trim()?.ifEmpty { null } ?: "desarrollo"
}
