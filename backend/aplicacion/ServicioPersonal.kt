package aplicacion

import datos.Credencial
import datos.CuentaPersonal
import datos.FuenteConexiones
import datos.PersonalAutenticado
import datos.RepositorioPersonal
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Serializable
data class SesionPersonal(val token: String, val personal: PersonalAutenticado)

val ROLES = setOf("administrador", "cocina", "barra", "sala")

fun PersonalAutenticado.exigirRol(vararg roles: String) {
    if (rol != "administrador" && rol !in roles) throw Prohibido()
}

/** Autenticación del back-office (RF08 · RPR10): PBKDF2 para las claves y tokens con hash en la base. */
class ServicioPersonal(private val fuente: FuenteConexiones, private val horasSesion: Long = 12) {
    private val aleatorio = SecureRandom()

    fun crear(identificador: String, rol: String, clave: String): Long {
        if (rol !in ROLES) throw SolicitudInvalida("rol_invalido", "Rol no válido: $rol")
        if (!Regex("^[a-z0-9][a-z0-9._-]{2,39}$").matches(identificador)) {
            throw SolicitudInvalida("identificador_invalido", "Usa un identificador de usuario (minúsculas, números, . _ -), no un nombre de persona")
        }
        validarClave(clave)
        return fuente.usar { conn ->
            val repo = RepositorioPersonal(conn)
            if (repo.buscar(identificador) != null) throw Conflicto("identificador_existente", "Ya existe $identificador")
            val id = repo.crear(identificador, rol)
            repo.guardarCredencial(id, derivar(clave))
            id
        }
    }

    fun cambiarClave(identificador: String, clave: String) {
        validarClave(clave)
        fuente.usar { conn ->
            val repo = RepositorioPersonal(conn)
            val personal = repo.buscar(identificador) ?: throw NoEncontrado("personal_no_encontrado", "No existe $identificador")
            repo.guardarCredencial(personal.id, derivar(clave))
        }
    }

    // ---- Administración de cuentas desde el back-office (Plan §5) ----

    fun listar(admin: PersonalAutenticado): List<CuentaPersonal> {
        admin.exigirRol()
        return fuente.usar { RepositorioPersonal(it).listar() }
    }

    fun crearDesdeBackoffice(admin: PersonalAutenticado, identificador: String, rol: String, clave: String): CuentaPersonal {
        admin.exigirRol()
        val id = crear(identificador.trim(), rol, clave)
        return fuente.usar { RepositorioPersonal(it).listar().single { c -> c.id == id } }
    }

    fun cambiarActivo(admin: PersonalAutenticado, personalId: Long, activo: Boolean): CuentaPersonal {
        admin.exigirRol()
        if (personalId == admin.id && !activo) throw Conflicto("auto_desactivacion", "No puedes desactivar tu propia cuenta")
        return fuente.usar { conn ->
            val repo = RepositorioPersonal(conn)
            repo.porId(personalId) ?: throw NoEncontrado("personal_no_encontrado", "Cuenta inexistente")
            repo.cambiarActivo(personalId, activo)
            repo.listar().single { it.id == personalId }
        }
    }

    /** Asigna una clave nueva y cierra las sesiones abiertas de esa cuenta. */
    fun restablecerClave(admin: PersonalAutenticado, personalId: Long, clave: String) {
        admin.exigirRol()
        validarClave(clave)
        fuente.usar { conn ->
            val repo = RepositorioPersonal(conn)
            repo.porId(personalId) ?: throw NoEncontrado("personal_no_encontrado", "Cuenta inexistente")
            repo.guardarCredencial(personalId, derivar(clave))
            repo.cerrarSesionesDe(personalId)
        }
    }

    fun iniciarSesion(identificador: String, clave: String): SesionPersonal = fuente.usar { conn ->
        val repo = RepositorioPersonal(conn)
        val personal = repo.buscar(identificador)
        val credencial = personal?.credencial
        // Se deriva siempre, exista o no el usuario, para no revelar cuáles existen por el tiempo de respuesta.
        val valida = verificar(clave, credencial ?: Credencial(SIN_USUARIO, SIN_USUARIO, ITERACIONES))
        if (personal == null || credencial == null || !personal.activo || !valida) throw NoAutenticado()
        val token = ByteArray(32).also(aleatorio::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        repo.crearSesion(hashToken(token), personal.id, horasSesion)
        SesionPersonal(token, PersonalAutenticado(personal.id, personal.identificador, personal.rol))
    }

    fun autenticar(token: String?): PersonalAutenticado {
        if (token.isNullOrBlank()) throw NoAutenticado()
        return fuente.usar { RepositorioPersonal(it).sesion(hashToken(token)) } ?: throw NoAutenticado()
    }

    fun cerrarSesion(token: String) = fuente.usar { RepositorioPersonal(it).cerrarSesion(hashToken(token)) }

    private fun validarClave(clave: String) {
        if (clave.length < 10) throw SolicitudInvalida("clave_corta", "La clave debe tener al menos 10 caracteres")
    }

    private fun derivar(clave: String, sal: ByteArray = ByteArray(16).also(aleatorio::nextBytes), iteraciones: Int = ITERACIONES): Credencial {
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(clave.toCharArray(), sal, iteraciones, 256)).encoded
        return Credencial(Base64.getEncoder().encodeToString(hash), Base64.getEncoder().encodeToString(sal), iteraciones)
    }

    private fun verificar(clave: String, credencial: Credencial): Boolean {
        val sal = runCatching { Base64.getDecoder().decode(credencial.sal) }.getOrDefault(ByteArray(16))
        val calculado = derivar(clave, sal, credencial.iteraciones)
        return MessageDigest.isEqual(calculado.hash.toByteArray(), credencial.hash.toByteArray())
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ITERACIONES = 210_000
        const val SIN_USUARIO = "AAAAAAAAAAAAAAAAAAAAAA=="
    }
}
