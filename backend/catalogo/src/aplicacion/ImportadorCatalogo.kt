package catalogo.aplicacion

import catalogo.datos.RepositorioCatalogo
import comun.SolicitudInvalida
import comun.contratos.ApiSeguridad
import comun.contratos.ApiSesion
import comun.contratos.ArchivoCatalogo
import comun.contratos.ProductoJson
import comun.contratos.ResumenImportacion
import comun.contratos.TextoJson
import comun.datos.CargaPorCodigo
import comun.datos.FuenteConexiones
import comun.datos.consultar
import comun.datos.ejecutar
import comun.datos.enTransaccion
import kotlinx.serialization.json.Json
import java.sql.Connection

/**
 * Carga del catálogo preparado por el Responsable de Datos (procedimiento de validación, Plan §6.3).
 *
 * El Catálogo escribe categorías, preferencias, productos y textos; el Servicio de Seguridad
 * Alimentaria escribe ingredientes, alérgenos y evaluaciones. Solo se escribe lo que cambió: cada
 * cambio en un producto anula su validación por trigger y el plato deja de recomendarse hasta que
 * cocina lo revalide. El importador nunca valida productos ni cambia su disponibilidad.
 *
 * Las dos partes no son una sola transacción. Si la segunda falla, los productos tocados quedan sin
 * validar (no se recomiendan ni se pueden pedir) hasta repetir la importación, que es idempotente.
 */
class ImportadorCatalogo(
    private val fuente: FuenteConexiones,
    private val sesion: ApiSesion,
    private val seguridad: ApiSeguridad,
    private val catalogo: ServicioCatalogo,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun importar(contenido: String, evaluador: String): ResumenImportacion {
        val archivo = json.decodeFromString(ArchivoCatalogo.serializer(), contenido)
        archivo.productos.forEach(::revisarProducto)
        val validador = sesion.personalPorIdentificador(evaluador)
        if (validador == null || !validador.activo || validador.rol !in setOf("cocina", "administrador")) {
            throw SolicitudInvalida("evaluador_invalido", "El evaluador debe ser personal activo de cocina o administración")
        }

        val antes = fuente.usar { RepositorioCatalogo(it).revisiones() }
        val ids = fuente.usar { conn -> conn.enTransaccion { CargaCatalogo(conn).ejecutar(archivo) } }
        try {
            seguridad.importar(archivo, evaluador, ids)
        } finally {
            catalogo.invalidar()
        }

        return fuente.usar { conn ->
            val repo = RepositorioCatalogo(conn)
            val despues = repo.revisiones()
            val codigos = archivo.productos.map { it.codigo }
            ResumenImportacion(
                productosNuevos = codigos.count { it !in antes },
                productosModificados = codigos.count { it in antes && antes[it] != despues[it] },
                productosSinCambios = codigos.count { it in antes && antes[it] == despues[it] },
                productosPorValidar = repo.porValidar(),
            )
        }
    }

    private fun revisarProducto(p: ProductoJson) {
        fun invalido(mensaje: String): Nothing = throw SolicitudInvalida("catalogo_invalido", mensaje)
        if (p.tipo !in setOf("plato", "bebida")) invalido("Tipo no válido en ${p.codigo}")
        if (p.destino !in setOf("cocina", "barra")) invalido("Destino no válido en ${p.codigo}")
        if (p.textos.keys != setOf("es", "en")) invalido("${p.codigo} necesita textos en es y en")
        if (p.ingredientes.isEmpty()) invalido("${p.codigo} necesita ingredientes")
    }

    private class CargaCatalogo(private val conn: Connection) {
        private val carga = CargaPorCodigo(conn)

        /** Devuelve código → id de cada producto del archivo. */
        fun ejecutar(archivo: ArchivoCatalogo): Map<String, Long> {
            archivo.categorias.forEach {
                carga.referencia("categorias", it.codigo, linkedMapOf("nombre_es" to it.nombreEs, "nombre_en" to it.nombreEn, "orden" to it.orden.toLong()))
            }
            archivo.preferencias.forEach { carga.referencia("preferencias", it.codigo, linkedMapOf("nombre_es" to it.nombreEs, "nombre_en" to it.nombreEn)) }
            return archivo.productos.associate { it.codigo to producto(it) }
        }

        private fun producto(p: ProductoJson): Long {
            val datos = linkedMapOf<String, Any?>(
                "categoria_id" to carga.id("categorias", p.categoria), "tipo" to p.tipo, "destino" to p.destino,
                "precio_centimos" to p.precioCentimos, "foto_url" to p.fotoUrl, "activo" to if (p.activo) 1L else 0L,
            )
            val id = carga.referencia("productos", p.codigo, datos)

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
            carga.sincronizar(
                actuales = conn.consultar("SELECT preferencia_id FROM producto_preferencias WHERE producto_id = ?", id) { it.getLong(1) }.toSet(),
                deseados = p.preferencias.map { carga.id("preferencias", it) }.toSet(),
                insertar = { conn.ejecutar("INSERT INTO producto_preferencias(producto_id, preferencia_id) VALUES (?, ?)", id, it) },
                borrar = { conn.ejecutar("DELETE FROM producto_preferencias WHERE producto_id = ? AND preferencia_id = ?", id, it) },
            )
            return id
        }
    }
}
