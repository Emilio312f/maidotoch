package dominio.recomendacion

/**
 * Construye la explicación de cada recomendación solo con datos reales: preferencias declaradas,
 * catálogo y reglas aplicadas (RNF01). La IA no redacta nada de este texto. Nunca usa frases
 * genéricas del tipo «el mejor plato para usted».
 */
object GeneradorExplicaciones {

    fun explicar(candidato: CandidatoRecomendable, perfil: ContextoPerfil, consulta: String, totalCandidatos: Int): String {
        val en = perfil.idioma == "en"
        val preferencias = candidato.preferencias.filterKeys { it in perfil.preferencias }.values.sorted()
        val palabras = Texto.coincidencias(consulta, candidato)

        val partes = buildList {
            if (preferencias.isNotEmpty()) {
                add(
                    if (en) "Matches your preferences: ${preferencias.joinToString(", ")}."
                    else "Coincide con tus preferencias: ${preferencias.joinToString(", ")}.",
                )
            }
            if (palabras.isNotEmpty()) {
                val lista = palabras.joinToString(", ") { "«$it»" }
                add(if (en) "Related to what you asked for: $lista." else "Relacionado con lo que pediste: $lista.")
            }
            if (preferencias.isEmpty() && palabras.isEmpty()) {
                add(
                    // Solo se afirma lo que el sistema verificó: no encaja con la consulta ni con las preferencias.
                    if (en) "It does not match your request or your preferences; it is one of the $totalCandidatos dishes available today."
                    else "No coincide con lo que pediste ni con tus preferencias; es uno de los $totalCandidatos platos disponibles hoy.",
                )
            }
            if (perfil.restricciones.isNotEmpty()) {
                val lista = perfil.restricciones.joinToString(", ")
                add(
                    if (en) "Checked by the kitchen as compatible with: $lista."
                    else "Validado por cocina como compatible con: $lista.",
                )
            } else {
                add(if (en) "Available on today's menu." else "Disponible hoy en la carta.")
            }
        }
        return partes.joinToString(" ")
    }
}
