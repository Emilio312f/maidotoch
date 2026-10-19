package comun.contratos

import kotlinx.serialization.Serializable

// Cuerpos de las peticiones de la API pública (web-app del comensal y back-office).

@Serializable
data class IniciarSesion(val token: String, val idioma: String = "es")

@Serializable
data class PerfilPeticion(val restricciones: List<Long> = emptyList(), val preferencias: List<Long> = emptyList())

@Serializable
data class IdiomaPeticion(val idioma: String)

@Serializable
data class ConsultaPeticion(val consulta: String = "")

@Serializable
data class EventoPeticion(val accion: String)

@Serializable
data class AgregarItemPeticion(val productoId: Long, val cantidad: Int = 1, val recomendacionId: Long? = null)

@Serializable
data class CantidadPeticion(val cantidad: Int)

@Serializable
data class ConfirmarPeticion(val confirmo: Boolean = false)

@Serializable
data class LlamadoPeticion(val motivo: String = "asistencia", val productoId: Long? = null)

@Serializable
data class EncuestaPeticion(val satisfaccion: Int)

@Serializable
data class LoginPeticion(val identificador: String, val clave: String)

@Serializable
data class DisponibilidadPeticion(val disponible: Boolean, val motivo: String)

@Serializable
data class EstadoPeticion(val estado: String)

@Serializable
data class PuestoPeticion(val codigo: String, val tipo: String)

@Serializable
data class ActivoPeticion(val activo: Boolean)

@Serializable
data class CuentaPeticion(val identificador: String, val rol: String, val clave: String)

@Serializable
data class ClavePeticion(val clave: String)

@Serializable
data class MedicionPeticion(
    val fase: String,
    val fecha: String,
    val comensales: Int,
    val importeCentimos: Long,
    val tiempoPedidoSegundos: Int,
    val consultasMozo: Int,
    val fuente: String,
)
