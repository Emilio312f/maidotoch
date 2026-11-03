import { useEffect, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import type { EstadoSesion, ProductoCarta } from "../comun/api";
import { fotoDemo } from "../comun/fotosDemo";
import type { Clave } from "../comun/i18n";
import { textoVisible } from "../comun/textos";
import { useComensal } from "./contexto";

/** «Mesa 08» o «Barra 2» a partir del código del puesto (sin la marca de datos de prueba). */
export function nombrePuesto(estado: EstadoSesion, t: (c: Clave, v?: Record<string, string | number>) => string): string {
  const codigo = estado.puestoCodigo.replace(/^FICTICIO-/i, "");
  const numero = codigo.replace(/^[MB]-?/i, "") || codigo;
  return t(estado.puestoTipo === "barra" ? "puesto.barra" : "puesto.mesa", { n: numero });
}

export function fotoDe(producto: Pick<ProductoCarta, "fotoUrl" | "nombre">): string | undefined {
  return producto.fotoUrl ?? fotoDemo(producto.nombre);
}

/** Fotos de la carta por id de producto, para pantallas que reciben solo el id (asistente, pedido). */
export function useFotos(): Record<number, string | undefined> {
  const { api } = useComensal();
  const [fotos, setFotos] = useState<Record<number, string | undefined>>({});
  useEffect(() => {
    api
      .carta()
      .then((c) => setFotos(Object.fromEntries(c.productos.map(({ producto }) => [producto.id, fotoDe(producto)]))))
      .catch(() => undefined);
  }, [api]);
  return fotos;
}

/** Foto del plato; si no hay, la inicial sobre un degradado. */
export function Foto({ src, nombre, className }: { src?: string; nombre: string; className?: string }) {
  if (src) return <img className={className} src={src} alt="" loading="lazy" decoding="async" />;
  return (
    <span className={`mt-inicial ${className ?? ""}`} aria-hidden="true">
      {textoVisible(nombre).charAt(0)}
    </span>
  );
}

export function Marca() {
  return (
    <div className="mt-marca">
      <span className="mt-enso" aria-hidden="true" />
      <span>MAIDO</span>
    </div>
  );
}

/** Barra superior: con «volver» y título en los pasos, o con la marca en las pantallas principales. */
export function BarraSuperior({ volver, titulo, derecha }: { volver?: string; titulo?: string; derecha?: ReactNode }) {
  const { estado, idioma, t, setEstado, api, manejarError } = useComensal();
  const navegar = useNavigate();
  const cambiarIdioma = async () => {
    try {
      setEstado(await api.cambiarIdioma(idioma === "es" ? "en" : "es"));
    } catch (e) {
      alert(manejarError(e));
    }
  };
  return (
    <header className="mt-topbar">
      {volver ? (
        <button className="mt-icono" onClick={() => navegar(volver)} aria-label={t("comun.volver")}>
          ←
        </button>
      ) : (
        <Marca />
      )}
      {titulo && <span className="mt-topbar-titulo">{titulo}</span>}
      <div className="mt-acciones">
        {derecha}
        <button className="mt-icono idioma" onClick={cambiarIdioma} lang={idioma === "es" ? "en" : "es"} aria-label={t("idioma.cambiar")}>
          {idioma === "es" ? "EN" : "ES"}
        </button>
        {!volver && (
          <span className="mt-pill">
            <span className="mt-punto" aria-hidden="true" />
            {nombrePuesto(estado, t)}
          </span>
        )}
      </div>
    </header>
  );
}

export function Cargando() {
  return (
    <div className="mt-esqueleto" aria-busy="true">
      <div />
      <div />
      <div />
    </div>
  );
}

const trazo = { fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round" } as const;

export const Icono = {
  carta: () => (
    <svg viewBox="0 0 24 24" {...trazo} aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h10" /></svg>
  ),
  chispa: () => (
    <svg viewBox="0 0 24 24" {...trazo} aria-hidden="true">
      <path d="m12 3 1.7 5.3L19 10l-5.3 1.7L12 17l-1.7-5.3L5 10l5.3-1.7L12 3Z" />
      <path d="m19 16 .8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8L19 16Z" />
    </svg>
  ),
  bolsa: () => (
    <svg viewBox="0 0 24 24" {...trazo} aria-hidden="true"><path d="M5 8h14l-1 12H6L5 8Z" /><path d="M9 9V6a3 3 0 0 1 6 0v3" /></svg>
  ),
  campana: () => (
    <svg viewBox="0 0 24 24" {...trazo} aria-hidden="true"><path d="M7 10a5 5 0 0 1 10 0c0 6 3 6 3 7H4c0-1 3-1 3-7Z" /><path d="M10 20h4" /></svg>
  ),
  lupa: () => (
    <svg viewBox="0 0 24 24" {...trazo} aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>
  ),
  enviar: () => (
    <svg viewBox="0 0 24 24" {...trazo} aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
  ),
};
