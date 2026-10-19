package comun.contratos

import kotlinx.serialization.Serializable

@Serializable
data class ErrorRespuesta(val error: String, val mensaje: String, val detalle: List<Long> = emptyList())

@Serializable
data class Ok(val ok: Boolean = true)

@Serializable
data class SaludServicio(val servicio: String, val estado: String, val version: String)

@Serializable
data class IdsPeticion(val ids: List<Long>)

@Serializable
data class IdsIdiomaPeticion(val ids: List<Long>, val idioma: String)

@Serializable
data class Nombres(val nombres: Map<Long, String>)

@Serializable
data class TextoPlano(val texto: String)
