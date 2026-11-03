import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { soles, type Recomendado, type RespuestaAsistente } from "../comun/api";
import type { Clave } from "../comun/i18n";
import { textoVisible } from "../comun/textos";
import { useComensal } from "./contexto";
import { BarraSuperior, Foto, Icono, useFotos } from "./piezas";

const IDEAS: Clave[] = ["asistente.idea1", "asistente.idea2", "asistente.idea3", "asistente.idea4"];

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
  // La respuesta del asistente trae solo el id del producto; la foto se toma de la carta.
  const fotos = useFotos();

  const preguntar = async (texto: string) => {
    setCargando(true);
    setError(null);
    try {
      setRespuesta(await api.preguntar(texto));
    } catch (e) {
      setError(manejarError(e));
    } finally {
      setCargando(false);
    }
  };

  const enviar = (evento: FormEvent) => {
    evento.preventDefault();
    preguntar(consulta);
  };

  return (
    <section>
      <BarraSuperior />
      <div className="mt-vista">
        <div className="mt-pad" style={{ paddingBottom: 14 }}>
          <div className="mt-eyebrow">{t("asistente.eyebrow")}</div>
          <h1>{t("asistente.titulo")}</h1>
          <p className="mt-muted" style={{ margin: 0 }}>{t("asistente.intro")}</p>
        </div>

        <div className="mt-rec-hero">
          <div className="mt-eyebrow">{t("asistente.comoTitulo")}</div>
          <h2>{t("asistente.comoSubtitulo")}</h2>
          <p>{t("asistente.comoTexto")}</p>
        </div>

        <form className="mt-consulta" onSubmit={enviar}>
          <div className="mt-consulta-caja">
            <textarea
              value={consulta}
              rows={1}
              maxLength={300}
              placeholder={t("asistente.placeholder")}
              onChange={(e) => setConsulta(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  preguntar(consulta);
                }
              }}
              aria-label={t("asistente.titulo")}
            />
            <button className="mt-enviar" disabled={cargando} aria-label={t("asistente.preguntar")}>
              <Icono.enviar />
            </button>
          </div>
          <div className="mt-ideas">
            {IDEAS.map((clave) => (
              <button
                key={clave}
                type="button"
                className="mt-idea"
                disabled={cargando}
                onClick={() => {
                  setConsulta(t(clave));
                  preguntar(t(clave));
                }}
              >
                {t(clave)}
              </button>
            ))}
          </div>
        </form>

        {cargando && (
          <div className="mt-pensando" role="status">
            <span className="mt-enso" aria-hidden="true" />
            {t("asistente.pensando")}
          </div>
        )}
        {error && <p className="mt-nota peligro" style={{ margin: "14px 20px" }}>{error}</p>}

        {respuesta && !cargando && (
          <div aria-live="polite" style={{ paddingTop: 16 }}>
            <div style={{ padding: "0 20px" }}>
              {respuesta.derivacion?.motivo === "restriccion_incierta" && respuesta.derivacion.productos.length > 0 && (
                <div className="mt-nota alerta">
                  <span className="mt-nota-icono" aria-hidden="true">ⓘ</span>
                  <span>{t("asistente.derivacion", { productos: respuesta.derivacion.productos.join(", ") })}</span>
                  <button className="mt-enlace" onClick={() => llamarMozo("restriccion_incierta")}>{t("asistente.consultarMozo")}</button>
                </div>
              )}
              {respuesta.sinCoincidencia && (
                <div className="mt-nota info">
                  <span className="mt-nota-icono" aria-hidden="true">⌕</span>
                  <span>{t("asistente.sinCoincidencia")}</span>
                  <button className="mt-enlace" onClick={() => navegar("/carta")}>{t("asistente.verCarta")}</button>
                </div>
              )}
            </div>

            {respuesta.sinCandidatos ? (
              <div className="mt-vacio" style={{ paddingTop: 32 }}>
                <div className="mt-simbolo">♢</div>
                <h2>{t("asistente.sinCandidatos")}</h2>
                <button className="mt-btn primario" onClick={() => llamarMozo(respuesta.derivacion?.motivo ?? "asistencia")}>
                  {t("mozo.llamar")}
                </button>
              </div>
            ) : (
              <div className="mt-lista">
                {respuesta.recomendaciones.map((r) => (
                  <TarjetaRecomendacion key={r.recomendacionId} recomendacion={r} foto={fotos[r.productoId]} />
                ))}
              </div>
            )}

            <div style={{ padding: "0 20px" }}>
              {respuesta.motor === "fallback" && !respuesta.sinCoincidencia && <p className="mt-muted" style={{ fontSize: 13 }}>{t("asistente.motorReglas")}</p>}
              {!respuesta.sinCandidatos && respuesta.platosSinConfirmar > 0 && (
                <p className="mt-muted" style={{ fontSize: 13 }}>{t("asistente.sinConfirmar", { n: respuesta.platosSinConfirmar })}</p>
              )}
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

function TarjetaRecomendacion({ recomendacion, foto }: { recomendacion: Recomendado; foto?: string }) {
  const { api, t, setCarrito, manejarError, avisar } = useComensal();
  const [estado, setEstado] = useState<"abierta" | "agregada" | "rechazada">("abierta");

  const agregar = async () => {
    try {
      // Agregar con el id de la recomendación la registra como aceptada (RF13).
      setCarrito(await api.agregar(recomendacion.productoId, 1, recomendacion.recomendacionId));
      setEstado("agregada");
      avisar(t("toast.agregado"));
    } catch (e) {
      avisar(manejarError(e));
    }
  };

  const rechazar = () => {
    setEstado("rechazada");
    api.evento(recomendacion.recomendacionId, "rechazada").catch(() => undefined);
  };

  return (
    <article className={`mt-card mt-rec ${estado === "rechazada" ? "mt-agotado" : ""}`} style={{ cursor: "default" }}>
      <div className="mt-card-foto">
        <Foto src={foto} nombre={recomendacion.nombre} />
        <span className="mt-posicion" aria-label={t("asistente.sugerencia", { n: recomendacion.posicion })}>{recomendacion.posicion}</span>
      </div>
      <div className="mt-card-cuerpo">
        <div className="mt-card-top">
          <h3>{textoVisible(recomendacion.nombre)}</h3>
          <span className="mt-precio">{soles(recomendacion.precioCentimos)}</span>
        </div>
        <div className="mt-por-que"><Icono.chispa />{t("asistente.porque")}</div>
        <p className="mt-explicacion">{recomendacion.explicacion}</p>
        {estado === "abierta" && (
          <div className="mt-rec-acciones">
            <button className="mt-btn primario peque" onClick={agregar}>+ {t("carta.agregar")}</button>
            <button className="mt-btn borde peque" onClick={rechazar}>{t("asistente.noGracias")}</button>
          </div>
        )}
        {estado === "agregada" && <span className="mt-tag apto" style={{ alignSelf: "flex-start" }}>✓ {t("carta.agregado")}</span>}
        {estado === "rechazada" && <span className="mt-tag" style={{ alignSelf: "flex-start" }}>{t("asistente.descartado")}</span>}
      </div>
    </article>
  );
}
