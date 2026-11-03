import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { opcionesPerfil, type OpcionPerfil } from "../comun/api";
import { useComensal } from "./contexto";
import { BarraSuperior, Cargando } from "./piezas";

/**
 * Perfil breve al inicio (RF07), en dos pasos: gustos y restricciones. Se completa con toques, en
 * menos de 30 segundos y sin datos personales. Para continuar hay que declarar las restricciones o
 * marcar «Ninguna»: la carta segura depende de esa respuesta.
 */
export function Perfil() {
  const { api, estado, idioma, t, setEstado, manejarError } = useComensal();
  const navegar = useNavigate();
  const [opciones, setOpciones] = useState<{ restricciones: OpcionPerfil[]; preferencias: OpcionPerfil[] } | null>(null);
  const [paso, setPaso] = useState<1 | 2>(1);
  const [restricciones, setRestricciones] = useState<number[]>(estado.restricciones);
  const [preferencias, setPreferencias] = useState<number[]>(estado.preferencias);
  const [ninguna, setNinguna] = useState(estado.perfilCompleto && estado.restricciones.length === 0);
  const [enviando, setEnviando] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    opcionesPerfil(idioma).then(setOpciones).catch((e) => setError(manejarError(e)));
  }, [idioma, manejarError]);

  const alternar = (lista: number[], fijar: (l: number[]) => void, id: number) =>
    fijar(lista.includes(id) ? lista.filter((x) => x !== id) : [...lista, id]);

  const alternarRestriccion = (id: number) => {
    setNinguna(false);
    alternar(restricciones, setRestricciones, id);
  };

  const guardar = async () => {
    setEnviando(true);
    setError(null);
    try {
      setEstado(await api.guardarPerfil(ninguna ? [] : restricciones, preferencias));
      navegar("/carta");
    } catch (e) {
      setError(manejarError(e));
    } finally {
      setEnviando(false);
    }
  };

  const listo = ninguna || restricciones.length > 0;
  const volver = paso === 2 ? undefined : estado.perfilCompleto ? "/carta" : "/bienvenida";

  return (
    <section>
      {paso === 2 ? (
        <header className="mt-topbar">
          <button className="mt-icono" onClick={() => setPaso(1)} aria-label={t("comun.volver")}>←</button>
          <span className="mt-topbar-titulo">{t("perfil.seguridad")}</span>
          <span style={{ width: 40 }} />
        </header>
      ) : (
        <BarraSuperior volver={volver} titulo={t("perfil.etiqueta")} />
      )}
      <div className="mt-progreso" aria-hidden="true">
        <span style={{ width: paso === 1 ? "45%" : "90%" }} />
      </div>

      {!opciones ? (
        error ? <p className="mt-nota peligro" style={{ margin: 20 }}>{error}</p> : <Cargando />
      ) : (
        <div className="mt-pad mt-vista" key={paso}>
          <div className="mt-eyebrow">{t("perfil.paso", { n: paso })}</div>
          {paso === 1 ? (
            <>
              <h1>{t("perfil.gustosTitulo")}</h1>
              <p className="mt-muted">{t("perfil.gustosIntro")}</p>
              <div className="mt-opciones">
                {opciones.preferencias.map((o) => (
                  <button key={o.id} className="mt-opcion" aria-pressed={preferencias.includes(o.id)} onClick={() => alternar(preferencias, setPreferencias, o.id)}>
                    <span className="mt-dot" aria-hidden="true" />
                    {o.nombre}
                  </button>
                ))}
              </div>
              <div className="mt-pila">
                <button className="mt-btn primario bloque" onClick={() => setPaso(2)}>{t("perfil.siguiente")}</button>
                {preferencias.length === 0 && (
                  <button className="mt-btn fantasma bloque" onClick={() => setPaso(2)}>{t("perfil.saltar")}</button>
                )}
              </div>
            </>
          ) : (
            <>
              <h1>{t("perfil.restriccionesTitulo")}</h1>
              <p className="mt-muted">{t("perfil.restriccionesIntro")}</p>
              {(["alergia", "dieta", "otra"] as const).map((tipo) => {
                const deTipo = opciones.restricciones.filter((r) => r.tipo === tipo);
                if (deTipo.length === 0) return null;
                return (
                  <div key={tipo}>
                    <div className="mt-grupo">{t(`perfil.${tipo}`)}</div>
                    <div className="mt-opciones" style={{ marginBottom: 4 }}>
                      {deTipo.map((o) => (
                        <button key={o.id} className="mt-opcion" aria-pressed={!ninguna && restricciones.includes(o.id)} onClick={() => alternarRestriccion(o.id)}>
                          <span className="mt-dot" aria-hidden="true" />
                          {o.nombre}
                        </button>
                      ))}
                    </div>
                  </div>
                );
              })}
              <div className="mt-opciones">
                <button
                  className="mt-opcion ninguna"
                  aria-pressed={ninguna}
                  onClick={() => {
                    setNinguna(!ninguna);
                    setRestricciones([]);
                  }}
                >
                  <span className="mt-dot" aria-hidden="true" />
                  {t("perfil.ningunaOpcion")}
                </button>
              </div>
              <div className="mt-nota alerta">
                <span className="mt-nota-icono" aria-hidden="true">ⓘ</span>
                <span>{t("perfil.notaIa")} {t("perfil.aviso")}</span>
              </div>
              {error && <p className="mt-nota peligro">{error}</p>}
              <button className="mt-btn primario bloque" disabled={!listo || enviando} onClick={guardar}>
                {t("perfil.continuar")}
              </button>
              {!listo && <p className="mt-muted" style={{ fontSize: 13, textAlign: "center", marginTop: 10 }}>{t("perfil.elige")}</p>}
            </>
          )}
        </div>
      )}
    </section>
  );
}
