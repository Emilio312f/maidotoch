import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { opcionesPerfil, type OpcionPerfil } from "../comun/api";
import { useComensal } from "./contexto";

/** Perfil breve al inicio (RF07): se completa con toques, en menos de 30 segundos y sin datos personales. */
export function Perfil() {
  const { api, estado, idioma, t, setEstado, manejarError } = useComensal();
  const navegar = useNavigate();
  const [opciones, setOpciones] = useState<{ restricciones: OpcionPerfil[]; preferencias: OpcionPerfil[] } | null>(null);
  const [restricciones, setRestricciones] = useState<number[]>(estado.restricciones);
  const [preferencias, setPreferencias] = useState<number[]>(estado.preferencias);
  const [enviando, setEnviando] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    opcionesPerfil(idioma).then(setOpciones).catch((e) => setError(manejarError(e)));
  }, [idioma, manejarError]);

  const alternar = (lista: number[], fijar: (l: number[]) => void, id: number) =>
    fijar(lista.includes(id) ? lista.filter((x) => x !== id) : [...lista, id]);

  const guardar = async (sinRestricciones = false) => {
    setEnviando(true);
    setError(null);
    try {
      setEstado(await api.guardarPerfil(sinRestricciones ? [] : restricciones, preferencias));
      navegar("/carta");
    } catch (e) {
      setError(manejarError(e));
    } finally {
      setEnviando(false);
    }
  };

  if (!opciones) return <p className="cargando">{error ?? "…"}</p>;

  const grupos = (["alergia", "dieta", "otra"] as const)
    .map((tipo) => ({ tipo, opciones: opciones.restricciones.filter((r) => r.tipo === tipo) }))
    .filter((g) => g.opciones.length > 0);

  return (
    <section>
      <h1>{t("perfil.titulo")}</h1>
      <p className="suave">{t("perfil.intro")}</p>

      <h2>{t("perfil.restricciones")}</h2>
      {grupos.map((g) => (
        <div key={g.tipo}>
          <h3 className="suave">{t(`perfil.${g.tipo}`)}</h3>
          <div className="chips">
            {g.opciones.map((o) => (
              <button key={o.id} className="chip" aria-pressed={restricciones.includes(o.id)} onClick={() => alternar(restricciones, setRestricciones, o.id)}>
                {o.nombre}
              </button>
            ))}
          </div>
        </div>
      ))}
      <p className="aviso alerta">{t("perfil.aviso")}</p>

      <h2>{t("perfil.preferencias")}</h2>
      <div className="chips">
        {opciones.preferencias.map((o) => (
          <button key={o.id} className="chip" aria-pressed={preferencias.includes(o.id)} onClick={() => alternar(preferencias, setPreferencias, o.id)}>
            {o.nombre}
          </button>
        ))}
      </div>

      {error && <p className="aviso peligro">{error}</p>}
      <div className="lista">
        <button className="boton bloque" disabled={enviando} onClick={() => guardar()}>
          {t("perfil.continuar")}
        </button>
        {restricciones.length === 0 && (
          <button className="boton secundario bloque" disabled={enviando} onClick={() => guardar(true)}>
            {t("perfil.ninguna")}
          </button>
        )}
      </div>
    </section>
  );
}
