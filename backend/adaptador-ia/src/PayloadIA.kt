package ia

import comun.contratos.SolicitudOrdenamiento
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Contenido exacto del mensaje que viaja al proveedor de IA. Solo lleva la consulta y, por cada
 * candidato seguro, id, nombre, descripción y etiquetas de preferencia.
 */
object PayloadIA {
    @Serializable
    private data class Candidato(val id: Long, val nombre: String, val descripcion: String, val etiquetas: List<String>)

    @Serializable
    private data class Mensaje(val consulta: String, val candidatos: List<Candidato>)

    private val json = Json { encodeDefaults = true }

    fun json(solicitud: SolicitudOrdenamiento): String = json.encodeToString(
        Mensaje.serializer(),
        Mensaje(
            consulta = solicitud.consulta,
            candidatos = solicitud.candidatos.map { Candidato(it.id, it.nombre, it.descripcion, it.etiquetas) },
        ),
    )

    @Serializable
    private data class Respuesta(val ids: List<Long>)

    private val lector = Json { ignoreUnknownKeys = true }

    fun leerIds(texto: String): List<Long> = lector.decodeFromString(Respuesta.serializer(), texto.trim()).ids
}
