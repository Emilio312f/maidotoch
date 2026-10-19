package comun

/**
 * Error esperado del negocio. El [codigo] viaja a la web-app, que muestra el texto en español o
 * inglés; el [mensaje] es para logs y para el back-office. Entre servicios viaja igual: el cliente
 * interno vuelve a lanzar el mismo código y estado, así el error llega intacto al comensal.
 */
open class ErrorNegocio(
    val estadoHttp: Int,
    val codigo: String,
    mensaje: String,
    val detalle: List<Long> = emptyList(),
) : RuntimeException(mensaje)

class NoEncontrado(codigo: String, mensaje: String) : ErrorNegocio(404, codigo, mensaje)

class NoAutenticado(mensaje: String = "Credenciales ausentes o inválidas") : ErrorNegocio(401, "no_autenticado", mensaje)

class Prohibido(codigo: String = "prohibido", mensaje: String = "Operación no permitida para este rol") :
    ErrorNegocio(403, codigo, mensaje)

class Conflicto(codigo: String, mensaje: String, detalle: List<Long> = emptyList()) : ErrorNegocio(409, codigo, mensaje, detalle)

class SolicitudInvalida(codigo: String, mensaje: String) : ErrorNegocio(400, codigo, mensaje)

/** La sesión no existe o está cerrada (401), o todavía no completó el perfil (409). */
class SesionNoDisponible(perfilIncompleto: Boolean = false) : ErrorNegocio(
    if (perfilIncompleto) 409 else 401,
    if (perfilIncompleto) "perfil_incompleto" else "sesion_no_valida",
    if (perfilIncompleto) "La sesión no tiene el perfil completo" else "La sesión no existe o está cerrada",
)

/** Otro servicio no respondió o respondió con un error no controlado. */
class ServicioNoDisponible(servicio: String, causa: String) :
    ErrorNegocio(503, "servicio_no_disponible", "Servicio $servicio no disponible: $causa")

/** Error de negocio devuelto por otro servicio, con su estado y código originales. */
class ErrorRemoto(estadoHttp: Int, codigo: String, mensaje: String, detalle: List<Long>) :
    ErrorNegocio(estadoHttp, codigo, mensaje, detalle)
