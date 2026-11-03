import { useNavigate } from "react-router-dom";
import type { Idioma } from "../comun/api";
import { traducir } from "../comun/i18n";
import { useComensal } from "./contexto";
import { nombrePuesto } from "./piezas";

const FOTO_PORTADA = "/demo/ceviche.jpg";

/** Portada al abrir la sesión: idioma, puesto y el inicio del perfil (RF04 · RF07). */
export function Bienvenida() {
  const { api, estado, idioma, t, setEstado, manejarError } = useComensal();
  const navegar = useNavigate();
  const elegirIdioma = async (nuevo: Idioma) => {
    if (nuevo === idioma) return;
    try {
      setEstado(await api.cambiarIdioma(nuevo));
    } catch (e) {
      alert(manejarError(e));
    }
  };
  return (
    <section className="mt-hero">
      <img className="mt-hero-foto" src={FOTO_PORTADA} alt="" />
      <div className="mt-hero-top">
        <div className="mt-hero-logo" aria-label="Maido">
          <span>MAIDO</span>
        </div>
        <div className="mt-idiomas" role="group" aria-label={t("idioma.cambiar")}>
          {(["es", "en"] as const).map((l) => (
            <button key={l} aria-pressed={idioma === l} onClick={() => elegirIdioma(l)} lang={l}>
              {l.toUpperCase()}
            </button>
          ))}
        </div>
      </div>
      <div className="mt-hero-copy">
        <div className="mt-eyebrow">{t("bienvenida.eyebrow", { puesto: nombrePuesto(estado, t) })}</div>
        <h1>{t("bienvenida.titulo")}</h1>
        <p>{t("bienvenida.texto")}</p>
        <button className="mt-btn claro bloque" onClick={() => navegar(estado.perfilCompleto ? "/carta" : "/perfil")}>
          {t("bienvenida.comenzar")} <span aria-hidden="true">→</span>
        </button>
        <p className="mt-hero-nota">{t("app.mozoSiempre")}</p>
      </div>
    </section>
  );
}

/** Sin sesión: se llega escaneando el tag NFC o el QR del puesto. */
export function SinSesion({ idioma, aviso }: { idioma: Idioma; aviso: string | null }) {
  return (
    <div className="mt">
      <div className="mt-app">
        <section className="mt-hero">
          <img className="mt-hero-foto" src={FOTO_PORTADA} alt="" />
          <div className="mt-hero-top">
            <div className="mt-hero-logo" aria-label="Maido">
              <span>MAIDO</span>
            </div>
          </div>
          <div className="mt-hero-copy">
            <h1>{traducir(idioma, "inicio.titulo")}</h1>
            {aviso && <p className="mt-nota alerta">{aviso}</p>}
            <p>{traducir(idioma, "inicio.instruccion")}</p>
            <p className="mt-hero-nota">{traducir(idioma, "app.mozoSiempre")}</p>
          </div>
        </section>
      </div>
    </div>
  );
}
