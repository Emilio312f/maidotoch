package aplicacion

import datos.FuenteConexiones
import datos.RepositorioPersonal
import datos.consultar
import datos.ejecutar
import datos.enTransaccion
import datos.insertar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection

@Serializable
data class ArchivoCatalogo(
    val categorias: List<CategoriaJson> = emptyList(),
    val alergenos: List<ReferenciaJson> = emptyList(),
    val ingredientes: List<IngredienteJson> = emptyList(),
    val restricciones: List<RestriccionJson> = emptyList(),
    val preferencias: List<ReferenciaJson> = emptyList(),
    val productos: List<ProductoJson> = emptyList(),
)

@Serializable
data class CategoriaJson(val codigo: String, @SerialName("nombre_es") val nombreEs: String, @SerialName("nombre_en") val nombreEn: String, val orden: Int = 0)

@Serializable
data class ReferenciaJson(val codigo: String, @SerialName("nombre_es") val nombreEs: String, @SerialName("nombre_en") val nombreEn: String)

@Serializable
data class IngredienteJson(
    val codigo: String,
    @SerialName("nombre_es") val nombreEs: String,
    @SerialName("nombre_en") val nombreEn: String,
    val alergenos: List<String> = emptyList(),
)

@Serializable
data class RestriccionJson(
    val codigo: String,
    val tipo: String,
    @SerialName("nombre_es") val nombreEs: String,
    @SerialName("nombre_en") val nombreEn: String,
    val alergeno: String? = null,
)

@Serializable
data class TextoJson(val nombre: String, val descripcion: String, val historia: String? = null)

@Serializable
data class AlergenoJson(val alergeno: String, val origen: String)

@Serializable
data class EvaluacionJson(val restriccion: String, val resultado: String, val evidencia: String)

@Serializable
data class ProductoJson(
    val codigo: String,
    val categoria: String,
    val tipo: String,
    val destino: String,
    @SerialName("precio_centimos") val precioCentimos: Long,
    @SerialName("foto_url") val fotoUrl: String? = null,
    val activo: Boolean = true,
    val textos: Map<String, TextoJson>,
    val ingredientes: List<String>,
    val alergenos: List<AlergenoJson> = emptyList(),
    val preferencias: List<String> = emptyList(),
    val evaluaciones: List<EvaluacionJson> = emptyList(),
)

@Serializable
data class ResumenImportacion(val productosNuevos: Int, val productosModificados: Int, val productosSinCambios: Int, val productosPorValidar: List<String>)

/**
 * Carga del catálogo preparado por el Responsable de Datos (procedimiento de validación, Plan §6.3).
 *
 * Solo escribe lo que cambió: cada cambio en un producto anula su validación por trigger y el plato
 * deja de recomendarse hasta que cocina lo revalide en el back-office. El importador nunca valida
 * productos ni cambia su disponibilidad.
 */
class ImportadorCatalogo(private val fuente: FuenteConexiones) {
    private val json = Json { ignoreUnknownKeys = true }

    fun importar(contenido: String, evaluador: String): ResumenImportacion {
        val archivo = json.decodeFromString(ArchivoCatalogo.serializer(), contenido)
        return fuente.usar { conn ->
            conn.enTransaccion {
                val validador = RepositorioPersonal(conn).buscar(evaluador)
                if (validador == null || !validador.activo || validador.rol !in setOf("cocina", "administrador")) {
                    throw SolicitudInvalida("evaluador_invalido", "El evaluador debe ser personal activo de cocina o administración")
                }
                Carga(conn, validador.id).ejecutar(archivo)
            }
        }
    }

    private class Carga(private val conn: Connection, private val evaluadorId: Long) {

        fun ejecutar(archivo: ArchivoCatalogo): ResumenImportacion {
            archivo.categorias.forEach {
                referencia("categorias", it.codigo, linkedMapOf("nombre_es" to it.nombreEs, "nombre_en" to it.nombreEn, "orden" to it.orden.toLong()))
            }
            archivo.alergenos.forEach { referencia("alergenos", it.codigo, linkedMapOf("nombre_es" to it.nombreEs, "nombre_en" to it.nombreEn)) }
            archivo.preferencias.forEach { referencia("preferencias", it.codigo, linkedMapOf("nombre_es" to it.nombreEs, "nombre_en" to it.nombreEn)) }
            archivo.ingredientes.forEach { ingrediente ->
                val id = referencia("ingredientes", ingrediente.codigo, linkedMapOf("nombre_es" to ingrediente.nombreEs, "nombre_en" to ingrediente.nombreEn))
                sincronizar(
                    actuales = conn.consultar("SELECT alergeno_id FROM ingrediente_alergenos WHERE ingrediente_id = ?", id) { it.getLong(1) }.toSet(),
                    deseados = ingrediente.alergenos.map { id("alergenos", it) }.toSet(),
                    insertar = { conn.ejecutar("INSERT INTO ingrediente_alergenos(ingrediente_id, alergeno_id) VALUES (?, ?)", id, it) },
                    borrar = { conn.ejecutar("DELETE FROM ingrediente_alergenos WHERE ingrediente_id = ? AND alergeno_id = ?", id, it) },
                )
            }
            archivo.restricciones.forEach { r ->
                if (r.tipo !in setOf("alergia", "dieta", "otra")) invalido("Tipo de restricción no válido en ${r.codigo}")
                referencia(
                    "restricciones", r.codigo,
                    linkedMapOf("tipo" to r.tipo, "nombre_es" to r.nombreEs, "nombre_en" to r.nombreEn, "alergeno_id" to r.alergeno?.let { id("alergenos", it) }),
                )
            }

            var nuevos = 0
            var modificados = 0
            var sinCambios = 0
            archivo.productos.forEach { producto ->
                val antes = conn.consultar("SELECT revision FROM productos WHERE codigo = ?", producto.codigo) { it.getLong(1) }.singleOrNull()
                val id = producto(producto)
                val despues = conn.consultar("SELECT revision FROM productos WHERE id = ?", id) { it.getLong(1) }.single()
                when {
                    antes == null -> nuevos++
                    antes != despues -> modificados++
                    else -> sinCambios++
                }
            }
            val porValidar = conn.consultar("SELECT codigo FROM productos WHERE validado_en IS NULL AND activo = 1 ORDER BY codigo") { it.getString(1) }
            return ResumenImportacion(nuevos, modificados, sinCambios, porValidar)
        }

        private fun producto(p: ProductoJson): Long {
            if (p.tipo !in setOf("plato", "bebida")) invalido("Tipo no válido en ${p.codigo}")
            if (p.destino !in setOf("cocina", "barra")) invalido("Destino no válido en ${p.codigo}")
            if (p.textos.keys != setOf("es", "en")) invalido("${p.codigo} necesita textos en es y en")
            if (p.ingredientes.isEmpty()) invalido("${p.codigo} necesita ingredientes")
            val categoria = id("categorias", p.categoria)
            val datos = linkedMapOf<String, Any?>(
                "categoria_id" to categoria, "tipo" to p.tipo, "destino" to p.destino,
                "precio_centimos" to p.precioCentimos, "foto_url" to p.fotoUrl, "activo" to if (p.activo) 1L else 0L,
            )
            val id = referencia("productos", p.codigo, datos)

            p.textos.forEach { (idioma, texto) ->
                val actual = conn.consultar("SELECT nombre, descripcion, historia FROM producto_textos WHERE producto_id = ? AND idioma = ?", id, idioma) {
                    TextoJson(it.getString(1), it.getString(2), it.getString(3))
                }.singleOrNull()
                if (actual != texto) {
                    conn.ejecutar(
                        """
                        INSERT INTO producto_textos(producto_id, idioma, nombre, descripcion, historia) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(producto_id, idioma) DO UPDATE SET nombre = excluded.nombre, descripcion = excluded.descripcion, historia = excluded.historia
                        """.trimIndent(),
                        id, idioma, texto.nombre, texto.descripcion, texto.historia,
                    )
                }
            }
            sincronizar(
                actuales = conn.consultar("SELECT ingrediente_id FROM producto_ingredientes WHERE producto_id = ?", id) { it.getLong(1) }.toSet(),
                deseados = p.ingredientes.map { id("ingredientes", it) }.toSet(),
                insertar = { conn.ejecutar("INSERT INTO producto_ingredientes(producto_id, ingrediente_id) VALUES (?, ?)", id, it) },
                borrar = { conn.ejecutar("DELETE FROM producto_ingredientes WHERE producto_id = ? AND ingrediente_id = ?", id, it) },
            )
            p.alergenos.forEach { if (it.origen !in setOf("contiene", "trazas")) invalido("Origen de alérgeno no válido en ${p.codigo}") }
            sincronizar(
                actuales = conn.consultar("SELECT alergeno_id, origen FROM producto_alergenos WHERE producto_id = ?", id) { it.getLong(1) to it.getString(2) }.toSet(),
                deseados = p.alergenos.map { id("alergenos", it.alergeno) to it.origen }.toSet(),
                insertar = { (a, o) -> conn.ejecutar("INSERT INTO producto_alergenos(producto_id, alergeno_id, origen) VALUES (?, ?, ?)", id, a, o) },
                borrar = { (a, o) -> conn.ejecutar("DELETE FROM producto_alergenos WHERE producto_id = ? AND alergeno_id = ? AND origen = ?", id, a, o) },
            )
            sincronizar(
                actuales = conn.consultar("SELECT preferencia_id FROM producto_preferencias WHERE producto_id = ?", id) { it.getLong(1) }.toSet(),
                deseados = p.preferencias.map { id("preferencias", it) }.toSet(),
                insertar = { conn.ejecutar("INSERT INTO producto_preferencias(producto_id, preferencia_id) VALUES (?, ?)", id, it) },
                borrar = { conn.ejecutar("DELETE FROM producto_preferencias WHERE producto_id = ? AND preferencia_id = ?", id, it) },
            )

            p.evaluaciones.forEach {
                if (it.resultado !in setOf("compatible", "incompatible", "desconocido")) invalido("Resultado no válido en ${p.codigo}")
                if (it.evidencia.isBlank()) invalido("Toda evaluación de ${p.codigo} necesita evidencia")
            }
            val actuales = conn.consultar("SELECT restriccion_id, resultado, evidencia FROM producto_restricciones WHERE producto_id = ?", id) {
                it.getLong(1) to (it.getString(2) to it.getString(3))
            }.toMap()
            val deseadas = p.evaluaciones.associate { id("restricciones", it.restriccion) to (it.resultado to it.evidencia) }
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
            return id
        }

        /** Inserta por código o actualiza solo si algún valor cambió (un UPDATE innecesario dispararía invalidaciones). */
        private fun referencia(tabla: String, codigo: String, valores: LinkedHashMap<String, Any?>): Long {
            val columnas = valores.keys.joinToString(", ")
            val actual = conn.consultar("SELECT id, $columnas FROM $tabla WHERE codigo = ?", codigo) { rs ->
                rs.getLong(1) to valores.keys.mapIndexed { i, _ -> rs.getObject(i + 2)?.let { if (it is Int) it.toLong() else it } }
            }.singleOrNull()
            if (actual == null) {
                val marcas = (listOf("codigo") + valores.keys).joinToString(", ") { "?" }
                return conn.insertar("INSERT INTO $tabla(codigo, $columnas) VALUES ($marcas)", codigo, *valores.values.toTypedArray())
            }
            val (id, existentes) = actual
            if (existentes != valores.values.toList()) {
                val asignaciones = valores.keys.joinToString(", ") { "$it = ?" }
                conn.ejecutar("UPDATE $tabla SET $asignaciones WHERE id = ?", *valores.values.toTypedArray(), id)
            }
            return id
        }

        private fun id(tabla: String, codigo: String): Long =
            conn.consultar("SELECT id FROM $tabla WHERE codigo = ?", codigo) { it.getLong(1) }.singleOrNull()
                ?: invalido("Código desconocido en $tabla: $codigo")

        private fun <T> sincronizar(actuales: Set<T>, deseados: Set<T>, insertar: (T) -> Unit, borrar: (T) -> Unit) {
            (deseados - actuales).forEach(insertar)
            (actuales - deseados).forEach(borrar)
        }

        private fun invalido(mensaje: String): Nothing = throw SolicitudInvalida("catalogo_invalido", mensaje)
    }
}
