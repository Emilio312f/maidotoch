package dominio.restricciones

/** Specification: cada regla examina un producto frente a una restricción y devuelve los motivos de exclusión. */
internal fun interface ReglaExclusion {
    fun aplicar(producto: DatosSeguridadProducto, restriccion: Restriccion): List<Motivo>
}

/** El alérgeno de la restricción figura en el producto, como contenido o como trazas. */
internal val reglaAlergenoDeclarado = ReglaExclusion { producto, restriccion ->
    val origenes = restriccion.alergenoId?.let { producto.alergenosDeclarados[it] }.orEmpty()
    origenes.sorted().map { origen ->
        val tipo = when (origen) {
            OrigenAlergeno.CONTIENE -> TipoMotivo.ALERGENO_CONTIENE
            OrigenAlergeno.TRAZAS -> TipoMotivo.ALERGENO_TRAZAS
        }
        Motivo(tipo, restriccion.id)
    }
}

/** El alérgeno de la restricción llega por alguno de los ingredientes del producto. */
internal val reglaAlergenoViaIngrediente = ReglaExclusion { producto, restriccion ->
    val alergeno = restriccion.alergenoId
    if (alergeno != null && alergeno in producto.alergenosViaIngredientes) {
        listOf(Motivo(TipoMotivo.ALERGENO_VIA_INGREDIENTE, restriccion.id))
    } else {
        emptyList()
    }
}

/** Solo una evaluación `compatible` validada por cocina habilita el producto para esa restricción. */
internal val reglaEvaluacionValidada = ReglaExclusion { producto, restriccion ->
    when (producto.evaluaciones[restriccion.id]) {
        ResultadoEvaluacion.COMPATIBLE -> emptyList()
        ResultadoEvaluacion.INCOMPATIBLE -> listOf(Motivo(TipoMotivo.EVALUACION_INCOMPATIBLE, restriccion.id))
        ResultadoEvaluacion.DESCONOCIDO -> listOf(Motivo(TipoMotivo.EVALUACION_DESCONOCIDA, restriccion.id))
        null -> listOf(Motivo(TipoMotivo.SIN_EVALUACION, restriccion.id))
    }
}
