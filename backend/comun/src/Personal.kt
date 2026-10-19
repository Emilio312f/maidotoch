package comun

import kotlinx.serialization.Serializable

/** Personal del back-office ya autenticado por el API Gateway. Sin datos personales: solo un identificador de usuario. */
@Serializable
data class PersonalAutenticado(val id: Long, val identificador: String, val rol: String)

val ROLES = setOf("administrador", "cocina", "barra", "sala")

/** El administrador puede todo; los demás roles, solo lo indicado. */
fun PersonalAutenticado.exigirRol(vararg roles: String) {
    if (rol != "administrador" && rol !in roles) throw Prohibido()
}

val IDIOMAS = setOf("es", "en")

fun validarIdioma(idioma: String) {
    if (idioma !in IDIOMAS) throw SolicitudInvalida("idioma_no_soportado", "Idioma no soportado: $idioma")
}
