package datos

import dominio.disponibilidad.EstadoCatalogo
import dominio.restricciones.DatosSeguridadProducto
import dominio.restricciones.OrigenAlergeno
import dominio.restricciones.Restriccion
import dominio.restricciones.ResultadoEvaluacion
import dominio.restricciones.TipoRestriccion
import java.sql.Connection

data class PerfilSesion(
    val sesionId: String,
    val idioma: String,
    val abierta: Boolean,
    val perfilCompleto: Boolean,
    val revisionPerfil: Long,
    val restricciones: List<Restriccion>,
)

data class ProductoEnCatalogo(val catalogo: EstadoCatalogo, val seguridad: DatosSeguridadProducto)

/** Fila de v_productos_compatibles: la fuente de verdad de lo que se puede recomendar o pedir. */
data class ProductoCompatible(val productoId: Long, val revisionProducto: Long, val revisionPerfil: Long)

data class InstantaneaSeguridad(
    val perfil: PerfilSesion,
    val productos: List<ProductoEnCatalogo>,
    val compatibles: List<ProductoCompatible>,
)

/** Solo lectura: los datos de alérgenos se modifican únicamente por el procedimiento de validación. */
class RepositorioSeguridadAlimentaria(private val conn: Connection) {

    /** Lee perfil, catálogo y vista en una sola transacción para que las revisiones sean coherentes. */
    fun instantanea(sesionId: String): InstantaneaSeguridad? = conn.leerEnTransaccion {
        perfil(sesionId)?.let { perfil ->
            InstantaneaSeguridad(perfil, productos(perfil.idioma), compatibles(sesionId))
        }
    }

    private fun perfil(sesionId: String): PerfilSesion? {
        val restricciones = conn.consultar(
            """
            SELECT r.id, r.tipo, r.alergeno_id
            FROM sesion_restricciones sr JOIN restricciones r ON r.id = sr.restriccion_id
            WHERE sr.sesion_id = ?
            """.trimIndent(),
            sesionId,
        ) { rs ->
            Restriccion(rs.getLong(1), tipoRestriccion(rs.getString(2)), rs.getObject(3)?.let { rs.getLong(3) })
        }
        return conn.consultar(
            "SELECT idioma, cierre IS NULL, perfil_completo, revision_perfil FROM sesiones WHERE id = ?",
            sesionId,
        ) { rs ->
            PerfilSesion(sesionId, rs.getString(1), rs.getInt(2) == 1, rs.getInt(3) == 1, rs.getLong(4), restricciones)
        }.singleOrNull()
    }

    private fun productos(idioma: String): List<ProductoEnCatalogo> {
        val declarados = conn.consultar("SELECT producto_id, alergeno_id, origen FROM producto_alergenos") { rs ->
            Triple(rs.getLong(1), rs.getLong(2), origenAlergeno(rs.getString(3)))
        }.groupBy { it.first }
            .mapValues { (_, filas) -> filas.groupBy({ it.second }, { it.third }).mapValues { it.value.toSet() } }

        val viaIngredientes = conn.consultar(
            """
            SELECT DISTINCT pi.producto_id, ia.alergeno_id
            FROM producto_ingredientes pi JOIN ingrediente_alergenos ia ON ia.ingrediente_id = pi.ingrediente_id
            """.trimIndent(),
        ) { rs -> rs.getLong(1) to rs.getLong(2) }
            .groupBy({ it.first }, { it.second })

        val evaluaciones = conn.consultar("SELECT producto_id, restriccion_id, resultado FROM producto_restricciones") { rs ->
            Triple(rs.getLong(1), rs.getLong(2), resultadoEvaluacion(rs.getString(3)))
        }.groupBy { it.first }
            .mapValues { (_, filas) -> filas.associate { it.second to it.third } }

        return conn.consultar(
            """
            SELECT p.id, p.activo, p.disponible, p.validado_en IS NOT NULL,
                   EXISTS (SELECT 1 FROM producto_textos t WHERE t.producto_id = p.id AND t.idioma = ?)
            FROM productos p
            """.trimIndent(),
            idioma,
        ) { rs ->
            val id = rs.getLong(1)
            ProductoEnCatalogo(
                catalogo = EstadoCatalogo(id, rs.getInt(2) == 1, rs.getInt(3) == 1, rs.getInt(5) == 1),
                seguridad = DatosSeguridadProducto(
                    productoId = id,
                    validado = rs.getInt(4) == 1,
                    alergenosDeclarados = declarados[id].orEmpty(),
                    alergenosViaIngredientes = viaIngredientes[id].orEmpty().toSet(),
                    evaluaciones = evaluaciones[id].orEmpty(),
                ),
            )
        }
    }

    private fun compatibles(sesionId: String): List<ProductoCompatible> = conn.consultar(
        "SELECT producto_id, revision, revision_perfil FROM v_productos_compatibles WHERE sesion_id = ?",
        sesionId,
    ) { rs -> ProductoCompatible(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }

    private fun tipoRestriccion(valor: String) = when (valor) {
        "alergia" -> TipoRestriccion.ALERGIA
        "dieta" -> TipoRestriccion.DIETA
        "otra" -> TipoRestriccion.OTRA
        else -> error("Tipo de restricción desconocido: $valor")
    }

    private fun origenAlergeno(valor: String) = when (valor) {
        "contiene" -> OrigenAlergeno.CONTIENE
        "trazas" -> OrigenAlergeno.TRAZAS
        else -> error("Origen de alérgeno desconocido: $valor")
    }

    private fun resultadoEvaluacion(valor: String) = when (valor) {
        "compatible" -> ResultadoEvaluacion.COMPATIBLE
        "incompatible" -> ResultadoEvaluacion.INCOMPATIBLE
        "desconocido" -> ResultadoEvaluacion.DESCONOCIDO
        else -> error("Resultado de evaluación desconocido: $valor")
    }
}
