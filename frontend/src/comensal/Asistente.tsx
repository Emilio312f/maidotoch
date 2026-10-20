import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { soles, type Recomendado, type RespuestaAsistente } from "../comun/api";
import { textoVisible } from "../comun/textos";
import { useComensal } from "./contexto";

/**
 * Asistente (RF03 · RF09 · RF14): el texto de cada explicación lo arma el servidor con datos del
 * catálogo y del perfil; esta pantalla solo lo muestra.
 */
export function Asistente() {
  const { api, t, manejarError, llamarMozo } = useComensal();
  const navegar = useNavigate();
  const [consulta, setConsulta] = useState("");
  const [respuesta, setRespuesta] = useState<RespuestaAsistente | null>(null);
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const preguntar = async (evento: FormEvent) => {
    evento.preventDefault();
    setCargando(true);
    setError(null);
    try {
      setRespuesta(await api.preguntar(consulta));
    } catch (e) {
      setError(manejarError(e));
    } finally {
      setCargando(false);
    }
  };

  return (
    <section>
      <h1>{t("asistente.titulo")}</h1>
      <p className="suave">{t("asistente.intro")}</p>
      <form className="consulta lista" onSubmit={preguntar}>
        <textarea
          value={consulta}
          maxLength={300}
          placeholder={t("asistente.placeholder")}
          onChange={(e) => setConsulta(e.target.value)}
          aria-label={t("asistente.titulo")}
        />
        <button className="boton bloque" disabled={cargando}>
          {cargando ? t("asistente.pensando") : t("asistente.preguntar")}
        </button>
      </form>

      {error && <p className="aviso peligro">{error}</p>}

      {respuesta && (
        <div className="lista" style={{ marginTop: 16 }} aria-live="polite">
          {respuesta.derivacion && respuesta.derivacion.motivo === "restriccion_incierta" && respuesta.derivacion.productos.length > 0 && (
            <div className="aviso alerta">
              <p>{t("asistente.derivacion", { productos: respuesta.derivacion.productos.join(", ") })}</p>
              <button className="boton secundario peque" onClick={() => llamarMozo("restriccion_incierta")}>
                {t("asistente.consultarMozo")}
              </button>
            </div>
          )}

          {respuesta.sinCoincidencia && (
            <div className="aviso info">
              <p>{t("asistente.sinCoincidencia")}</p>
              <button className="boton secundario peque" onClick={() => navegar("/carta")}>
                {t("asistente.verCarta")}
              </button>
            </div>
          )}

          {respuesta.sinCandidatos ? (
            <div className="aviso alerta">
              <p>{t("asistente.sinCandidatos")}</p>
              <button className="boton peque" onClick={() => llamarMozo(respuesta.derivacion?.motivo ?? "asistencia")}>
                {t("mozo.llamar")}
              </button>
            </div>
          ) : (
            respuesta.recomendaciones.map((r) => <TarjetaRecomendacion key={r.recomendacionId} recomendacion={r} />)
          )}

          {respuesta.motor === "fallback" && !respuesta.sinCoincidencia && <p className="suave">{t("asistente.motorReglas")}</p>}
          {!respuesta.sinCandidatos && respuesta.platosSinConfirmar > 0 && (
            <p className="suave">{t("asistente.sinConfirmar", { n: respuesta.platosSinConfirmar })}</p>
          )}
        </div>
      )}
    </section>
  );
}

function TarjetaRecomendacion({ recomendacion }: { recomendacion: Recomendado }) {
  const { api, t, setCarrito, manejarError } = useComensal();
  const [estado, setEstado] = useState<"abierta" | "agregada" | "rechazada">("abierta");
  const [error, setError] = useState<string | null>(null);

  const agregar = async () => {
    setError(null);
    try {
      // Agregar con el id de la recomendación la registra como aceptada (RF13).
      setCarrito(await api.agregar(recomendacion.productoId, 1, recomendacion.recomendacionId));
      setEstado("agregada");
    } catch (e) {
      setError(manejarError(e));
    }
  };

  const rechazar = async () => {
    setEstado("rechazada");
    api.evento(recomendacion.recomendacionId, "rechazada").catch(() => undefined);
  };

  return (
    <article className="tarjeta recomendacion">
      <div className="fila entre">
        <div className="fila">
          <span className="posicion">{recomendacion.posicion}</span>
          <h3 style={{ margin: 0 }}>{textoVisible(recomendacion.nombre)}</h3>
        </div>
        <span className="precio">{soles(recomendacion.precioCentimos)}</span>
      </div>
      <p className="suave" style={{ marginTop: 6 }}>{textoVisible(recomendacion.descripcion)}</p>
      <div className="porque">
        <strong>{t("asistente.porque")}</strong>
        {recomendacion.explicacion}
      </div>
      {error && <p className="aviso peligro">{error}</p>}
      {estado === "abierta" && (
        <div className="fila">
          <button className="boton peque" onClick={agregar}>{t("asistente.agregar")}</button>
          <button className="boton secundario peque" onClick={rechazar}>{t("asistente.noGracias")}</button>
        </div>
      )}
      {estado === "agregada" && <span className="etiqueta apto">✓ {t("carta.agregado")}</span>}
      {estado === "rechazada" && <span className="etiqueta neutra">{t("asistente.descartado")}</span>}
    </article>
  );
}
