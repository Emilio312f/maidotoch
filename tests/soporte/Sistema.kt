package soporte

import atencion.ServicioAtencion
import bus.BusEventos
import catalogo.AppCatalogo
import comun.contratos.ApiIA
import comun.contratos.ApiTrazabilidad
import comun.contratos.PublicadorEventos
import comun.datos.FuenteConexiones
import ia.ServicioAdaptadorIA
import pedido.AppPedido
import piloto.ServicioPiloto
import recomendacion.aplicacion.OrquestadorRecomendacion
import seguridad.aplicacion.ServicioSeguridad
import sesion.AppSesion
import trazabilidad.ServicioTrazabilidad
import java.io.File
import java.nio.file.Files

/**
 * Todos los servicios en un mismo proceso, conectados por sus interfaces (ApiSesion, ApiSeguridad…)
 * en lugar de HTTP. Cada servicio usa sus propias conexiones a la base, igual que en el servidor.
 * Las pruebas por HTTP, con el API Gateway y los tokens de servicio, usan [SistemaHttp].
 */
class Sistema(
    fuente: FuenteConexiones,
    ia: ApiIA? = null,
    val directorioFotos: File = Files.createTempDirectory("maidotouch-fotos").toFile(),
    urlPublica: String = "http://localhost:5173",
    maximoRecomendaciones: Int = 3,
    limiteConsultasIa: Int = 10,
    timeoutIaMs: Long = 5_000,
) {
    val bus = BusEventos()

    private val appSesion = AppSesion(fuente, urlPublica)
    val sesiones = appSesion.sesiones
    val personal = appSesion.personal
    val puestos = appSesion.puestos
    val apiSesion = appSesion.interno

    val seguridad = ServicioSeguridad(fuente, apiSesion)

    private val appCatalogo = AppCatalogo(fuente, directorioFotos, apiSesion, seguridad)
    val catalogo = appCatalogo.catalogo
    val backoffice = appCatalogo.backoffice
    val fotos = appCatalogo.fotos
    val importador = appCatalogo.importador

    private val puertoTrazabilidad = object : ApiTrazabilidad {
        override fun registrarEvento(recomendacionId: Long, accion: String) = trazabilidad.registrarEvento(recomendacionId, accion)
    }
    val orquestador: OrquestadorRecomendacion = OrquestadorRecomendacion(
        fuente, apiSesion, catalogo, seguridad, ia, puertoTrazabilidad, maximoRecomendaciones, limiteConsultasIa, timeoutIaMs,
    )
    val trazabilidad: ServicioTrazabilidad = ServicioTrazabilidad(fuente, apiSesion, orquestador)

    private val appPedido = AppPedido(fuente, apiSesion, seguridad, orquestador, trazabilidad, bus)
    val pedidos = appPedido.pedidos
    val comandas = appPedido.comandas
    val eventos: PublicadorEventos = appPedido.eventos

    val atencion = ServicioAtencion(fuente, apiSesion, catalogo, eventos)
    val piloto = ServicioPiloto(fuente, apiSesion, ia ?: ServicioAdaptadorIA(null, timeoutIaMs, null), urlPublica)
}
