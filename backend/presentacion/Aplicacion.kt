package presentacion

import aplicacion.ImportadorCatalogo
import aplicacion.OrquestadorRecomendacion
import aplicacion.ServicioAtencion
import aplicacion.ServicioBackoffice
import aplicacion.ServicioCatalogo
import aplicacion.ServicioComandas
import aplicacion.ServicioPedido
import aplicacion.ServicioPersonal
import aplicacion.ServicioFotos
import aplicacion.ServicioPiloto
import aplicacion.ServicioSesion
import aplicacion.ServicioTrazabilidad
import configuracion.Configuracion
import datos.FuenteConexiones
import dominio.recomendacion.OrdenadorExterno
import integracion.AdaptadorClaude
import integracion.OrdenadorProtegido

/** Composición del monolito modular: arma cada servicio con sus dependencias. */
class Aplicacion(
    val configuracion: Configuracion,
    val fuente: FuenteConexiones,
    proveedorIa: OrdenadorExterno? = configuracion.iaApiKey?.let {
        AdaptadorClaude(it, configuracion.iaModelo, configuracion.iaTimeoutMs)
    },
) {
    val ordenador: OrdenadorProtegido? = proveedorIa?.let { OrdenadorProtegido(it, configuracion.iaTimeoutMs) }
    val sesiones = ServicioSesion(fuente)
    val catalogo = ServicioCatalogo(fuente)
    val orquestador = OrquestadorRecomendacion(fuente, ordenador, configuracion.recomendacionesMaximas, configuracion.iaLimiteConsultasPorSesion)
    val trazabilidad = ServicioTrazabilidad(fuente)
    val pedidos = ServicioPedido(fuente)
    val atencion = ServicioAtencion(fuente)
    val comandas = ServicioComandas(fuente)
    val personal = ServicioPersonal(fuente, configuracion.horasSesionPersonal)
    val backoffice = ServicioBackoffice(fuente, catalogo, configuracion.urlPublica)
    val importador = ImportadorCatalogo(fuente)
    val piloto = ServicioPiloto(fuente, configuracion)
    val fotos = ServicioFotos(fuente, java.io.File(configuracion.directorioFotos), catalogo)
}
