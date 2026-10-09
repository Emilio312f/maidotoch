package aplicacion

/**
 * Error esperado del negocio. El [codigo] viaja a la web-app, que muestra el texto en español o
 * inglés; el [mensaje] es para logs y para el back-office.
 */
open class ErrorNegocio(val estadoHttp: Int, val codigo: String, mensaje: String) : RuntimeException(mensaje)

class NoEncontrado(codigo: String, mensaje: String) : ErrorNegocio(404, codigo, mensaje)

class NoAutenticado(mensaje: String = "Credenciales ausentes o inválidas") : ErrorNegocio(401, "no_autenticado", mensaje)

class Prohibido(codigo: String = "prohibido", mensaje: String = "Operación no permitida para este rol") :
    ErrorNegocio(403, codigo, mensaje)

class Conflicto(codigo: String, mensaje: String, val detalle: List<Long> = emptyList()) : ErrorNegocio(409, codigo, mensaje)

class SolicitudInvalida(codigo: String, mensaje: String) : ErrorNegocio(400, codigo, mensaje)
