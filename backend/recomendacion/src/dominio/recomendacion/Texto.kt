package dominio.recomendacion

import java.text.Normalizer

/** Normalización simple para comparar la consulta con los textos del catálogo, sin IA. */
internal object Texto {
    private val vacias = setOf(
        // español
        "con", "sin", "para", "por", "que", "una", "uno", "unos", "unas", "los", "las", "del", "algo", "quiero",
        "me", "gustaria", "gusta", "algun", "alguna", "mas", "muy", "plato", "platos", "comer", "tomar", "prueba",
        "ficticio", "ficticia",
        // inglés
        "the", "and", "with", "without", "for", "something", "want", "would", "like", "some", "dish", "dishes",
        "test", "fictitious",
    )

    fun palabras(texto: String): Set<String> =
        Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in vacias }
            .toSet()

    /** Palabras de la consulta que aparecen en el nombre, la descripción o las etiquetas del producto. */
    fun coincidencias(consulta: String, candidato: CandidatoRecomendable): Set<String> {
        val delProducto = palabras(candidato.nombre + " " + candidato.descripcion + " " + candidato.preferencias.values.joinToString(" "))
        return palabras(consulta).filterTo(sortedSetOf()) { palabra ->
            delProducto.any { it == palabra || (palabra.length >= 4 && (it.startsWith(palabra) || palabra.startsWith(it))) }
        }
    }
}
