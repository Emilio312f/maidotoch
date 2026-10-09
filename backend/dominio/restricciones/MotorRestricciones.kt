package dominio.restricciones

/**
 * Motor de Restricciones Alimentarias (RF14 · RNF01 · RPR03).
 *
 * Determinístico y sin IA: decide solo con los datos validados del catálogo. Ante cualquier duda el
 * producto no es Seguro. Las reglas son fijas; no se pueden quitar ni reemplazar desde fuera.
 */
class MotorRestricciones {
    private val reglas = listOf(reglaAlergenoDeclarado, reglaAlergenoViaIngrediente, reglaEvaluacionValidada)

    fun evaluar(producto: DatosSeguridadProducto, restricciones: Collection<Restriccion>): EvaluacionProducto {
        val motivos = buildList {
            if (!producto.validado) add(Motivo(TipoMotivo.PRODUCTO_NO_VALIDADO))
            for (restriccion in restricciones) {
                for (regla in reglas) addAll(regla.aplicar(producto, restriccion))
            }
        }
        val estado = when {
            motivos.any { it.tipo.estado == EstadoSeguridad.INSEGURO } -> EstadoSeguridad.INSEGURO
            motivos.isNotEmpty() -> EstadoSeguridad.SIN_DATO
            else -> EstadoSeguridad.SEGURO
        }
        return EvaluacionProducto(producto.productoId, estado, motivos)
    }
}
