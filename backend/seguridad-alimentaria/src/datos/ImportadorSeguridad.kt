package seguridad.datos

import comun.contratos.ArchivoCatalogo
import comun.contratos.ProductoJson
import comun.datos.CargaPorCodigo
import comun.datos.consultar
import comun.datos.ejecutar
import java.sql.Connection

/**
 * Parte de seguridad alimentaria de la carga del catálogo validado (Plan §6.3): alérgenos,
 * ingredientes, restricciones y, por producto, ingredientes, alérgenos declarados y evaluaciones.
 *
 * Cada cambio en un producto anula su validación por trigger; el importador nunca valida productos.
 */
class ImportadorSeguridad(private val conn: Connection, private val evaluadorId: Long) {
    private val carga = CargaPorCodigo(conn)

    fun ejecutar(archivo: ArchivoCatalogo, productos: Map<String, Long>) {
        archivo.alergenos.forEach { carga.referencia("alergenos", it.codigo, linkedMapOf("nombre_es" to it.nombreEs, "nombre_en" to it.nombreEn)) }
        archivo.ingredientes.forEach { ingrediente ->
            val id = carga.referencia("ingredientes", ingrediente.codigo, linkedMapOf("nombre_es" to ingrediente.nombreEs, "nombre_en" to ingrediente.nombreEn))
            carga.sincronizar(
                actuales = conn.consultar("SELECT alergeno_id FROM ingrediente_alergenos WHERE ingrediente_id = ?", id) { it.getLong(1) }.toSet(),
                deseados = ingrediente.alergenos.map { carga.id("alergenos", it) }.toSet(),
                insertar = { conn.ejecutar("INSERT INTO ingrediente_alergenos(ingrediente_id, alergeno_id) VALUES (?, ?)", id, it) },
                borrar = { conn.ejecutar("DELETE FROM ingrediente_alergenos WHERE ingrediente_id = ? AND alergeno_id = ?", id, it) },
            )
        }
        archivo.restricciones.forEach { r ->
            if (r.tipo !in setOf("alergia", "dieta", "otra")) carga.invalido("Tipo de restricción no válido en ${r.codigo}")
            carga.referencia(
                "restricciones", r.codigo,
                linkedMapOf("tipo" to r.tipo, "nombre_es" to r.nombreEs, "nombre_en" to r.nombreEn, "alergeno_id" to r.alergeno?.let { carga.id("alergenos", it) }),
            )
        }
        archivo.productos.forEach { producto ->
            val id = productos[producto.codigo] ?: carga.invalido("El Catálogo no registró el producto ${producto.codigo}")
            producto(id, producto)
        }
    }

    private fun producto(id: Long, p: ProductoJson) {
        carga.sincronizar(
            actuales = conn.consultar("SELECT ingrediente_id FROM producto_ingredientes WHERE producto_id = ?", id) { it.getLong(1) }.toSet(),
            deseados = p.ingredientes.map { carga.id("ingredientes", it) }.toSet(),
            insertar = { conn.ejecutar("INSERT INTO producto_ingredientes(producto_id, ingrediente_id) VALUES (?, ?)", id, it) },
            borrar = { conn.ejecutar("DELETE FROM producto_ingredientes WHERE producto_id = ? AND ingrediente_id = ?", id, it) },
        )
        p.alergenos.forEach { if (it.origen !in setOf("contiene", "trazas")) carga.invalido("Origen de alérgeno no válido en ${p.codigo}") }
        carga.sincronizar(
            actuales = conn.consultar("SELECT alergeno_id, origen FROM producto_alergenos WHERE producto_id = ?", id) { it.getLong(1) to it.getString(2) }.toSet(),
            deseados = p.alergenos.map { carga.id("alergenos", it.alergeno) to it.origen }.toSet(),
            insertar = { (a, o) -> conn.ejecutar("INSERT INTO producto_alergenos(producto_id, alergeno_id, origen) VALUES (?, ?, ?)", id, a, o) },
            borrar = { (a, o) -> conn.ejecutar("DELETE FROM producto_alergenos WHERE producto_id = ? AND alergeno_id = ? AND origen = ?", id, a, o) },
        )

        p.evaluaciones.forEach {
            if (it.resultado !in setOf("compatible", "incompatible", "desconocido")) carga.invalido("Resultado no válido en ${p.codigo}")
            if (it.evidencia.isBlank()) carga.invalido("Toda evaluación de ${p.codigo} necesita evidencia")
        }
        val actuales = conn.consultar("SELECT restriccion_id, resultado, evidencia FROM producto_restricciones WHERE producto_id = ?", id) {
            it.getLong(1) to (it.getString(2) to it.getString(3))
        }.toMap()
        val deseadas = p.evaluaciones.associate { carga.id("restricciones", it.restriccion) to (it.resultado to it.evidencia) }
        deseadas.forEach { (restriccion, valor) ->
            if (actuales[restriccion] != valor) {
                conn.ejecutar(
                    """
                    INSERT INTO producto_restricciones(producto_id, restriccion_id, resultado, evidencia, validado_por) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(producto_id, restriccion_id) DO UPDATE SET resultado = excluded.resultado, evidencia = excluded.evidencia,
                      validado_por = excluded.validado_por, validado_en = strftime('%Y-%m-%dT%H:%M:%fZ','now')
                    """.trimIndent(),
                    id, restriccion, valor.first, valor.second, evaluadorId,
                )
            }
        }
        (actuales.keys - deseadas.keys).forEach {
            conn.ejecutar("DELETE FROM producto_restricciones WHERE producto_id = ? AND restriccion_id = ?", id, it)
        }
    }
}
