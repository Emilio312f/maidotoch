package configuracion

/**
 * Configuración leída de variables de entorno (ver .env.example). Los secretos, como la clave del
 * proveedor de IA, nunca se escriben en el repositorio.
 */
data class Configuracion(
    val rutaBaseDatos: String = "maidotouch.db",
    val puerto: Int = 8080,
    /** URL pública de la web-app; con ella se arman los enlaces que van en los tags NFC y en los QR. */
    val urlPublica: String = "http://localhost:5173",
    val directorioFrontend: String? = "frontend/dist",
    val directorioMigraciones: String = "db/migrations",
    /** Sin clave no hay proveedor de IA y el asistente opera siempre con el ranking por reglas. */
    val iaApiKey: String? = null,
    val iaModelo: String = "claude-opus-5-5",
    /** Tiempo máximo de espera al proveedor de IA antes de pasar al fallback (RNF04, RPR05). */
    val iaTimeoutMs: Long = 5_000,
    /** Consultas con IA permitidas por sesión para contener el costo (RP05). */
    val iaLimiteConsultasPorSesion: Int = 10,
    val recomendacionesMaximas: Int = 3,
    /** Una sesión de comensal abierta más de estas horas se cierra y su perfil se descarta (RNF02). */
    val horasSesionComensal: Long = 6,
    val horasSesionPersonal: Long = 12,
) {
    companion object {
        fun desdeEntorno(entorno: Map<String, String> = System.getenv()): Configuracion {
            val base = Configuracion()
            fun texto(nombre: String) = entorno[nombre]?.trim()?.takeIf { it.isNotEmpty() }
            return Configuracion(
                rutaBaseDatos = texto("MAIDOTOUCH_DB_PATH") ?: base.rutaBaseDatos,
                puerto = texto("MAIDOTOUCH_PUERTO")?.toInt() ?: base.puerto,
                urlPublica = (texto("MAIDOTOUCH_URL_PUBLICA") ?: base.urlPublica).trimEnd('/'),
                directorioFrontend = texto("MAIDOTOUCH_FRONTEND_DIR") ?: base.directorioFrontend,
                directorioMigraciones = texto("MAIDOTOUCH_MIGRACIONES_DIR") ?: base.directorioMigraciones,
                iaApiKey = texto("ANTHROPIC_API_KEY"),
                iaModelo = texto("MAIDOTOUCH_IA_MODELO") ?: base.iaModelo,
                iaTimeoutMs = texto("MAIDOTOUCH_IA_TIMEOUT_MS")?.toLong() ?: base.iaTimeoutMs,
                iaLimiteConsultasPorSesion = texto("MAIDOTOUCH_IA_LIMITE_CONSULTAS")?.toInt() ?: base.iaLimiteConsultasPorSesion,
                recomendacionesMaximas = texto("MAIDOTOUCH_RECOMENDACIONES_MAX")?.toInt() ?: base.recomendacionesMaximas,
                horasSesionComensal = texto("MAIDOTOUCH_SESION_HORAS")?.toLong() ?: base.horasSesionComensal,
                horasSesionPersonal = texto("MAIDOTOUCH_PERSONAL_SESION_HORAS")?.toLong() ?: base.horasSesionPersonal,
            )
        }
    }
}
