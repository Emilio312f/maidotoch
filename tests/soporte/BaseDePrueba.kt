package soporte

import datos.BaseDatos
import datos.ConexionUnica
import datos.Migraciones
import datos.consultar
import dominio.restricciones.ResultadoEvaluacion
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID

/**
 * Base SQLite en memoria creada desde las migraciones reales del repositorio.
 *
 * Todos los datos que crea son FICTICIOS y existen solo para las pruebas: no representan platos,
 * ingredientes ni alérgenos reales de Maido. El catálogo real lo carga y valida el Responsable de
 * Datos con cocina.
 */
class BaseDePrueba : AutoCloseable {
    val conn: Connection = BaseDatos.conectar("jdbc:sqlite::memory:")

    init {
        Migraciones.aplicar(conn, Path.of(System.getProperty("maidotouch.migraciones") ?: "db/migrations"))
    }

    /** Todas las operaciones de los servicios comparten esta única conexión en memoria. */
    val fuente = ConexionUnica(conn)

    val cocina: Long = insertar("INSERT INTO personal(identificador, rol) VALUES ('FICTICIO-cocina', 'cocina')")
    private val punto = insertar("INSERT INTO puntos_servicio(codigo, tipo) VALUES ('FICTICIO-mesa-1', 'mesa')")
    private val acceso = insertar(
        "INSERT INTO accesos(punto_id, canal, token) VALUES (?, 'nfc', 'FICTICIO-token-1')", punto,
    )
    private val categoria = insertar(
        "INSERT INTO categorias(codigo, nombre_es, nombre_en) VALUES ('FICTICIO-cat', 'Categoría ficticia', 'Fictitious category')",
    )
    private val ingredienteNeutro = ingrediente("FICTICIO-ING-neutro")
    private var secuencia = 0

    fun insertar(sql: String, vararg parametros: Any?): Long {
        ejecutar(sql, *parametros)
        return conn.consultar("SELECT last_insert_rowid()") { it.getLong(1) }.single()
    }

    fun ejecutar(sql: String, vararg parametros: Any?): Int =
        conn.prepareStatement(sql).use { st ->
            parametros.forEachIndexed { i, valor -> st.setObject(i + 1, valor) }
            st.executeUpdate()
        }

    fun alergeno(codigo: String): Long =
        insertar("INSERT INTO alergenos(codigo, nombre_es, nombre_en) VALUES (?, ?, ?)", codigo, codigo, codigo)

    fun ingrediente(codigo: String, vararg alergenos: Long): Long {
        val id = insertar("INSERT INTO ingredientes(codigo, nombre_es, nombre_en) VALUES (?, ?, ?)", codigo, codigo, codigo)
        alergenos.forEach { insertar("INSERT INTO ingrediente_alergenos(ingrediente_id, alergeno_id) VALUES (?, ?)", id, it) }
        return id
    }

    fun restriccionAlergia(codigo: String, alergenoId: Long): Long = insertar(
        "INSERT INTO restricciones(codigo, tipo, nombre_es, nombre_en, alergeno_id) VALUES (?, 'alergia', ?, ?, ?)",
        codigo, codigo, codigo, alergenoId,
    )

    fun restriccionDieta(codigo: String): Long = insertar(
        "INSERT INTO restricciones(codigo, tipo, nombre_es, nombre_en) VALUES (?, 'dieta', ?, ?)",
        codigo, codigo, codigo,
    )

    fun preferencia(codigo: String): Long =
        insertar("INSERT INTO preferencias(codigo, nombre_es, nombre_en) VALUES (?, ?, ?)", codigo, "$codigo-es", "$codigo-en")

    fun declararPreferencias(sesionId: String, vararg preferencias: Long) {
        preferencias.forEach { insertar("INSERT INTO sesion_preferencias(sesion_id, preferencia_id) VALUES (?, ?)", sesionId, it) }
    }

    class ProductoFicticio {
        internal val ingredientes = mutableListOf<Long>()
        internal val preferencias = mutableListOf<Long>()
        internal val alergenos = mutableListOf<Pair<Long, String>>()
        internal val evaluaciones = mutableListOf<Pair<Long, ResultadoEvaluacion>>()
        var validado = true
        var disponible = true
        var activo = true
        var destino = "cocina"
        var precioCentimos = 4500L

        fun ingrediente(id: Long) {
            ingredientes += id
        }

        fun etiqueta(preferenciaId: Long) {
            preferencias += preferenciaId
        }

        fun contiene(alergenoId: Long) {
            alergenos += alergenoId to "contiene"
        }

        fun trazas(alergenoId: Long) {
            alergenos += alergenoId to "trazas"
        }

        fun evaluacion(restriccionId: Long, resultado: ResultadoEvaluacion) {
            evaluaciones += restriccionId to resultado
        }

        fun compatibleCon(vararg restricciones: Long) {
            restricciones.forEach { evaluacion(it, ResultadoEvaluacion.COMPATIBLE) }
        }
    }

    /** Carga el producto como lo haría el procedimiento real: primero los datos, después la validación. */
    fun producto(configurar: ProductoFicticio.() -> Unit = {}): Long {
        val datos = ProductoFicticio().apply(configurar)
        val codigo = "FICTICIO-P${++secuencia}"
        val id = insertar(
            "INSERT INTO productos(codigo, categoria_id, tipo, destino, precio_centimos, activo) VALUES (?, ?, ?, ?, ?, ?)",
            codigo, categoria, if (datos.destino == "barra") "bebida" else "plato", datos.destino,
            datos.precioCentimos, if (datos.activo) 1 else 0,
        )
        for (idioma in listOf("es", "en")) {
            insertar(
                "INSERT INTO producto_textos(producto_id, idioma, nombre, descripcion) VALUES (?, ?, ?, ?)",
                id, idioma, "$codigo ($idioma)", "Descripción ficticia de prueba",
            )
        }
        datos.ingredientes.ifEmpty { listOf(ingredienteNeutro) }.forEach {
            insertar("INSERT INTO producto_ingredientes(producto_id, ingrediente_id) VALUES (?, ?)", id, it)
        }
        datos.alergenos.forEach { (alergeno, origen) ->
            insertar("INSERT INTO producto_alergenos(producto_id, alergeno_id, origen) VALUES (?, ?, ?)", id, alergeno, origen)
        }
        datos.preferencias.forEach {
            insertar("INSERT INTO producto_preferencias(producto_id, preferencia_id) VALUES (?, ?)", id, it)
        }
        datos.evaluaciones.forEach { (restriccion, resultado) -> evaluar(id, restriccion, resultado) }
        if (datos.validado) validar(id)
        if (datos.disponible) cambiarDisponibilidad(id, true)
        return id
    }

    fun evaluar(productoId: Long, restriccionId: Long, resultado: ResultadoEvaluacion) {
        insertar(
            "INSERT INTO producto_restricciones(producto_id, restriccion_id, resultado, evidencia, validado_por) VALUES (?, ?, ?, 'FICTICIO: evidencia de prueba', ?)",
            productoId, restriccionId, resultado.name.lowercase(), cocina,
        )
    }

    fun validar(productoId: Long) {
        ejecutar(
            "UPDATE productos SET validado_por = ?, validado_en = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?",
            cocina, productoId,
        )
    }

    /** La disponibilidad solo se cambia insertando en el historial; nunca se actualiza la columna. */
    fun cambiarDisponibilidad(productoId: Long, disponible: Boolean) {
        insertar(
            "INSERT INTO disponibilidad_historial(producto_id, disponible, registrado_por, motivo) VALUES (?, ?, ?, 'FICTICIO: prueba')",
            productoId, if (disponible) 1 else 0, cocina,
        )
    }

    fun sesion(vararg restricciones: Long, idioma: String = "es", perfilCompleto: Boolean = true): String {
        val id = UUID.randomUUID().toString()
        insertar("INSERT INTO sesiones(id, acceso_id, idioma) VALUES (?, ?, ?)", id, acceso, idioma)
        restricciones.forEach {
            insertar("INSERT INTO sesion_restricciones(sesion_id, restriccion_id) VALUES (?, ?)", id, it)
        }
        if (perfilCompleto) ejecutar("UPDATE sesiones SET perfil_completo = 1 WHERE id = ?", id)
        return id
    }

    fun revisionProducto(productoId: Long): Long =
        conn.consultar("SELECT revision FROM productos WHERE id = ?", productoId) { it.getLong(1) }.single()

    fun revisionPerfil(sesionId: String): Long =
        conn.consultar("SELECT revision_perfil FROM sesiones WHERE id = ?", sesionId) { it.getLong(1) }.single()

    fun nombre(productoId: Long, idioma: String): String =
        conn.consultar("SELECT nombre FROM producto_textos WHERE producto_id = ? AND idioma = ?", productoId, idioma) {
            it.getString(1)
        }.single()

    override fun close() = conn.close()
}
