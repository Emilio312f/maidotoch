package gateway

import comun.Servicio

/**
 * Tabla de enrutamiento del API Gateway: cada prefijo de la API pública va al servicio responsable.
 * Gana el prefijo más largo; las rutas que no están aquí no existen para el exterior (las /interno
 * nunca se exponen).
 */
object Enrutamiento {
    private val tabla: List<Pair<String, Servicio>> = listOf(
        // Comensal
        "/api/sesiones" to Servicio.SESION,
        "/api/sesion" to Servicio.SESION,
        "/api/opciones-perfil" to Servicio.CATALOGO,
        "/api/sesion/carta" to Servicio.CATALOGO,
        "/api/sesion/asistente" to Servicio.RECOMENDACION,
        "/api/sesion/recomendaciones" to Servicio.TRAZABILIDAD,
        "/api/sesion/pedido" to Servicio.PEDIDO,
        "/api/sesion/llamados" to Servicio.ATENCION,
        "/api/sesion/encuesta" to Servicio.PILOTO,
        // Back-office
        "/api/backoffice/login" to Servicio.SESION,
        "/api/backoffice/logout" to Servicio.SESION,
        "/api/backoffice/yo" to Servicio.SESION,
        "/api/backoffice/personal" to Servicio.SESION,
        "/api/backoffice/puestos" to Servicio.SESION,
        "/api/backoffice/productos" to Servicio.CATALOGO,
        "/api/backoffice/disponibilidad" to Servicio.CATALOGO,
        "/api/backoffice/comandas" to Servicio.PEDIDO,
        "/api/backoffice/comandas-atrasadas" to Servicio.PEDIDO,
        "/api/backoffice/llamados" to Servicio.ATENCION,
        "/api/backoffice/metricas" to Servicio.PILOTO,
        "/api/backoffice/mediciones" to Servicio.PILOTO,
        "/api/backoffice/reporte-diario" to Servicio.PILOTO,
        "/api/backoffice/verificacion" to Servicio.PILOTO,
        "/api/backoffice/eventos" to Servicio.BUS,
        // Fotos de la carta
        "/fotos" to Servicio.CATALOGO,
    ).sortedByDescending { it.first.length }

    fun destino(ruta: String): Servicio? =
        tabla.firstOrNull { (prefijo, _) -> ruta == prefijo || ruta.startsWith("$prefijo/") }?.second

    /** Rutas del back-office que no requieren una sesión de personal ya iniciada. */
    fun sinAutenticacion(ruta: String): Boolean = ruta == "/api/backoffice/login" || ruta == "/api/backoffice/logout"

    fun esBackoffice(ruta: String): Boolean = ruta.startsWith("/api/backoffice/")
}
