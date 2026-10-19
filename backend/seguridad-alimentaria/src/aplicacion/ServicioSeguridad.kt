package seguridad.aplicacion

import comun.SolicitudInvalida
import comun.contratos.AlergenosProducto
import comun.contratos.ApiSeguridad
import comun.contratos.ApiSesion
import comun.contratos.ArchivoCatalogo
import comun.contratos.FichaSeguridad
import comun.contratos.ItemAVerificar
import comun.contratos.ListaSegura
import comun.contratos.OpcionPerfil
import comun.contratos.ProductoPedible
import comun.datos.FuenteConexiones
import comun.datos.enTransaccion
import comun.validarIdioma
import seguridad.datos.ImportadorSeguridad
import seguridad.datos.RepositorioCompatibles
import seguridad.datos.RepositorioSeguridadAlimentaria

/**
 * Servicio de Seguridad Alimentaria (RF14 · RNF01 · RPR03). Determinístico y sin IA: no llama a
 * ningún otro servicio para decidir. Lee en una sola transacción la vista v_productos_compatibles y
 * los datos que la vista usa, para que las revisiones de producto y de perfil sean coherentes.
 */
class ServicioSeguridad(
    private val fuente: FuenteConexiones,
    /** Solo para resolver el evaluador al importar el catálogo validado. */
    private val sesion: ApiSesion,
) : ApiSeguridad {

    override fun candidatos(sesionId: String, productos: Collection<Long>?): ListaSegura =
        calcular(sesionId, productos?.toSet()).comoLista()

    /** Resultado completo del motor, con los motivos de cada exclusión (pruebas y auditoría). */
    fun calcular(sesionId: String, productos: Set<Long>? = null): CandidatosSeguros =
        fuente.usar { conn -> ServicioCandidatosSeguros(RepositorioSeguridadAlimentaria(conn)).calcular(sesionId, productos) }

    override fun pedible(sesionId: String, productoId: Long): ProductoPedible? =
        fuente.usar { RepositorioCompatibles(it).pedible(sesionId, productoId) }

    override fun itemsNoConfirmables(sesionId: String, items: List<ItemAVerificar>): List<Long> =
        fuente.usar { RepositorioCompatibles(it).itemsNoConfirmables(sesionId, items) }

    override fun restricciones(idioma: String): List<OpcionPerfil> {
        validarIdioma(idioma)
        return fuente.usar { RepositorioCompatibles(it).restricciones(idioma) }
    }

    override fun alergenos(idioma: String): List<AlergenosProducto> {
        validarIdioma(idioma)
        return fuente.usar { RepositorioCompatibles(it).alergenos(idioma) }
    }

    override fun fichaSeguridad(productoId: Long): FichaSeguridad = fuente.usar { RepositorioCompatibles(it).ficha(productoId) }

    override fun importar(archivo: ArchivoCatalogo, evaluador: String, productos: Map<String, Long>) {
        val validador = sesion.personalPorIdentificador(evaluador)
        if (validador == null || !validador.activo || validador.rol !in setOf("cocina", "administrador")) {
            throw SolicitudInvalida("evaluador_invalido", "El evaluador debe ser personal activo de cocina o administración")
        }
        fuente.usar { conn -> conn.enTransaccion { ImportadorSeguridad(conn, validador.id).ejecutar(archivo, productos) } }
    }
}
