package dominio.recomendacion

/**
 * Estrategia de respaldo del Motor de Recomendación (RNF03 · RPR05): ordena la lista segura con
 * reglas fijas. Puntúa las preferencias declaradas y las palabras de la consulta; el precio no
 * interviene en ningún criterio (RPR11). Los empates se resuelven por categoría y por id.
 */
object RankingPorReglas {
    private const val PESO_PREFERENCIA = 3

    fun ordenar(candidatos: List<CandidatoRecomendable>, perfil: ContextoPerfil, consulta: String): List<CandidatoRecomendable> =
        candidatos.sortedWith(
            compareByDescending<CandidatoRecomendable> { puntaje(it, perfil, consulta) }
                .thenBy { it.ordenCategoria }
                .thenBy { it.productoId },
        )

    /** Como [ordenar], pero si algún plato coincide con preferencias o consulta, descarta los que no coinciden. */
    fun seleccionar(candidatos: List<CandidatoRecomendable>, perfil: ContextoPerfil, consulta: String): List<CandidatoRecomendable> {
        val ordenados = ordenar(candidatos, perfil, consulta)
        val conCoincidencia = ordenados.filter { puntaje(it, perfil, consulta) > 0 }
        return conCoincidencia.ifEmpty { ordenados }
    }

    fun puntaje(candidato: CandidatoRecomendable, perfil: ContextoPerfil, consulta: String): Int =
        PESO_PREFERENCIA * candidato.preferencias.keys.count { it in perfil.preferencias } +
            Texto.coincidencias(consulta, candidato).size
}

/** Una respuesta del proveedor solo conserva ids que estaban en la lista enviada (RNF01 · RPR01). */
object ValidadorRespuestaIA {
    fun filtrar(idsRespuesta: List<Long>, idsEnviados: Set<Long>): List<Long> =
        idsRespuesta.filter { it in idsEnviados }.distinct()
}
