package comun.contratos

import comun.ErrorRemoto
import comun.PersonalAutenticado
import comun.http.ClienteInterno
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/** Sesión del comensal: solo puesto e idioma, sin datos personales (RNF02). */
@Serializable
data class EstadoSesion(
    val idioma: String,
    val abierta: Boolean,
    val perfilCompleto: Boolean,
    val puestoCodigo: String,
    val puestoTipo: String,
    val restricciones: List<Long>,
    val preferencias: List<Long>,
)

@Serializable
data class PersonalResumen(val id: Long, val identificador: String, val rol: String, val activo: Boolean)

@Serializable
data class SesionesPeticion(val sesiones: List<String>)

@Serializable
data class PuestosDeSesiones(val puestos: Map<String, String>)

@Serializable
data class TokenPeticion(val token: String)

@Serializable
data class Identificadores(val identificadores: Map<Long, String>)

/** API interna del Servicio de Sesión. */
interface ApiSesion {
    /** Estado de una sesión abierta; lanza SesionNoDisponible si no existe o está cerrada. */
    fun sesionAbierta(sesionId: String): EstadoSesion

    /** Código de puesto de cada sesión (para comandas y llamados). */
    fun puestosDeSesiones(sesiones: Collection<String>): Map<String, String>

    /** Personal activo con sesión vigente del back-office; lanza NoAutenticado si no. */
    fun autenticarPersonal(token: String?): PersonalAutenticado

    fun personalPorIdentificador(identificador: String): PersonalResumen?

    fun identificadoresPersonal(ids: Collection<Long>): Map<Long, String>
}

class HttpApiSesion(private val cliente: ClienteInterno) : ApiSesion {
    override fun sesionAbierta(sesionId: String): EstadoSesion = cliente.get("/interno/sesiones/${codificar(sesionId)}")

    override fun puestosDeSesiones(sesiones: Collection<String>): Map<String, String> =
        if (sesiones.isEmpty()) emptyMap()
        else cliente.post<SesionesPeticion, PuestosDeSesiones>("/interno/sesiones/puestos", SesionesPeticion(sesiones.distinct())).puestos

    override fun autenticarPersonal(token: String?): PersonalAutenticado =
        cliente.post("/interno/personal/autenticar", TokenPeticion(token.orEmpty()))

    override fun personalPorIdentificador(identificador: String): PersonalResumen? = try {
        cliente.get("/interno/personal/por-identificador/${codificar(identificador)}")
    } catch (e: ErrorRemoto) {
        if (e.estadoHttp == 404) null else throw e
    }

    override fun identificadoresPersonal(ids: Collection<Long>): Map<Long, String> =
        if (ids.isEmpty()) emptyMap()
        else cliente.post<IdsPeticion, Identificadores>("/interno/personal/identificadores", IdsPeticion(ids.distinct())).identificadores
}

internal fun codificar(valor: String): String = URLEncoder.encode(valor, Charsets.UTF_8)
