package sesion.aplicacion

import comun.Conflicto
import comun.NoEncontrado
import comun.PersonalAutenticado
import comun.SolicitudInvalida
import comun.datos.FuenteConexiones
import comun.exigirRol
import kotlinx.serialization.Serializable
import sesion.datos.Puesto
import sesion.datos.RepositorioPersonal
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class PuestoConEnlaces(val puesto: Puesto, val enlaceNfc: String?, val enlaceQr: String?)

/** Puestos NFC/QR (RF01 · RNF08): cada mesa o puesto de barra con su token para el tag y otro para el QR. */
class ServicioPuestos(private val fuente: FuenteConexiones, private val urlPublica: String) {
    private val aleatorio = SecureRandom()

    fun puestos(personal: PersonalAutenticado): List<PuestoConEnlaces> {
        personal.exigirRol()
        return fuente.usar { RepositorioPersonal(it).puestos() }.map(::conEnlaces)
    }

    fun crearPuesto(personal: PersonalAutenticado, codigo: String, tipo: String): PuestoConEnlaces {
        personal.exigirRol()
        if (tipo !in setOf("mesa", "barra")) throw SolicitudInvalida("tipo_invalido", "El tipo es mesa o barra")
        val limpio = codigo.trim()
        if (!Regex("^[A-Za-z0-9-]{1,20}$").matches(limpio)) throw SolicitudInvalida("codigo_invalido", "Código de 1 a 20 letras, números o guiones")
        return fuente.usar { conn ->
            val repo = RepositorioPersonal(conn)
            if (repo.existePuesto(limpio)) throw Conflicto("puesto_existente", "Ya existe el puesto $limpio")
            val id = repo.crearPuesto(limpio, tipo, token(), token())
            conEnlaces(repo.puestos().single { it.id == id })
        }
    }

    /** Activación por puesto: apagar uno devuelve esa mesa al pedido tradicional sin afectar a las demás. */
    fun cambiarActivoPuesto(personal: PersonalAutenticado, puestoId: Long, activo: Boolean): PuestoConEnlaces {
        personal.exigirRol()
        return fuente.usar { conn ->
            val repo = RepositorioPersonal(conn)
            if (repo.cambiarActivoPuesto(puestoId, activo) == 0) throw NoEncontrado("puesto_no_encontrado", "Puesto inexistente")
            conEnlaces(repo.puestos().single { it.id == puestoId })
        }
    }

    private fun conEnlaces(puesto: Puesto) = PuestoConEnlaces(
        puesto,
        puesto.accesos.find { it.canal == "nfc" }?.let { "$urlPublica/m/${it.token}" },
        puesto.accesos.find { it.canal == "qr" }?.let { "$urlPublica/m/${it.token}" },
    )

    private fun token(): String = ByteArray(18).also(aleatorio::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}
