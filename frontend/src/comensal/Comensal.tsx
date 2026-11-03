import { useCallback, useEffect, useMemo, useState } from "react";
import { NavLink, Navigate, Route, Routes } from "react-router-dom";
import { ErrorApi, apiComensal, type Carrito, type EstadoSesion, type Idioma, type Llamado, type MotivoLlamado } from "../comun/api";
import { textoError, traducir, type Clave } from "../comun/i18n";
import { Asistente } from "./Asistente";
import { Bienvenida, SinSesion } from "./Bienvenida";
import { Carta } from "./Carta";
import { Contexto, guardarSesion, idiomaDelNavegador, leerSesion, type ContextoComensal } from "./contexto";
import { Pedido } from "./Pedido";
import { Perfil } from "./Perfil";
import { Icono } from "./piezas";

export function Comensal() {
  const [sesion, setSesion] = useState<string | null>(leerSesion);
  const [estado, setEstado] = useState<EstadoSesion | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [carrito, setCarrito] = useState<Carrito | null>(null);
  const [llamado, setLlamado] = useState<Llamado | null>(null);
  const [toast, setToast] = useState<{ texto: string; n: number } | null>(null);

  const idioma: Idioma = estado?.idioma ?? idiomaDelNavegador();
  const api = useMemo(() => (sesion ? apiComensal(sesion) : null), [sesion]);

  const terminar = useCallback(() => {
    guardarSesion(null);
    setSesion(null);
    setEstado(null);
  }, []);

  const manejarError = useCallback(
    (e: unknown) => {
      const codigo = e instanceof ErrorApi ? e.codigo : "generico";
      if (codigo === "sesion_no_valida") {
        terminar();
        setAviso(textoError(idioma, codigo));
      }
      return textoError(idioma, codigo);
    },
    [idioma, terminar],
  );

  const avisar = useCallback((texto: string) => setToast((t) => ({ texto, n: (t?.n ?? 0) + 1 })), []);
  useEffect(() => {
    if (!toast) return;
    const reloj = setTimeout(() => setToast(null), 2600);
    return () => clearTimeout(reloj);
  }, [toast]);

  useEffect(() => {
    if (!api) return;
    api.estado().then(setEstado).catch(manejarError);
    api.carrito().then(setCarrito).catch(() => undefined);
    api.llamados().then((l) => setLlamado(l[0] ?? null)).catch(() => undefined);
  }, [api, manejarError]);

  // Mientras haya un llamado pendiente se consulta si ya fue atendido.
  useEffect(() => {
    if (!api || llamado?.estado !== "pendiente") return;
    const intervalo = setInterval(() => api.llamados().then((l) => setLlamado(l[0] ?? null)).catch(() => undefined), 5000);
    return () => clearInterval(intervalo);
  }, [api, llamado?.estado]);

  useEffect(() => {
    document.documentElement.lang = idioma;
  }, [idioma]);

  if (!sesion || !api) return <SinSesion idioma={idioma} aviso={aviso} />;
  if (!estado) {
    return (
      <div className="mt">
        <div className="mt-app mt-centro">
          <span className="mt-enso" aria-hidden="true" />
          <p className="mt-muted">{traducir(idioma, "activacion.cargando")}</p>
        </div>
      </div>
    );
  }

  const t = (clave: Clave, valores?: Record<string, string | number>) => traducir(idioma, clave, valores);
  const contexto: ContextoComensal = {
    api,
    estado,
    idioma,
    t,
    manejarError,
    carrito,
    setCarrito,
    setEstado,
    llamado,
    llamarMozo: async (motivo: MotivoLlamado, productoId?: number) => {
      try {
        setLlamado(await api.llamar(motivo, productoId));
        avisar(t("mozo.avisado"));
      } catch (e) {
        avisar(manejarError(e));
      }
    },
    avisar,
  };

  const articulos = carrito?.borrador?.items.reduce((n, i) => n + i.cantidad, 0) ?? 0;
  const mozoEnCamino = llamado?.estado === "pendiente";

  return (
    <Contexto.Provider value={contexto}>
      <div className="mt">
        <div className="mt-app">
          <Routes>
            <Route path="bienvenida" element={<Bienvenida />} />
            <Route path="perfil" element={<Perfil />} />
            {estado.perfilCompleto ? (
              <>
                <Route path="carta" element={<Carta />} />
                <Route path="asistente" element={<Asistente />} />
                <Route path="pedido" element={<Pedido />} />
                <Route path="*" element={<Navigate to="/carta" replace />} />
              </>
            ) : (
              <Route path="*" element={<Navigate to="/bienvenida" replace />} />
            )}
          </Routes>

          {estado.perfilCompleto && (
            <nav className="mt-nav" aria-label={t("app.nombre")}>
              <NavLink to="/carta">
                <Icono.carta />
                <span>{t("nav.carta")}</span>
              </NavLink>
              <NavLink to="/asistente">
                <Icono.chispa />
                <span>{t("nav.paraTi")}</span>
              </NavLink>
              <NavLink to="/pedido">
                {articulos > 0 && <span className="mt-badge">{articulos}</span>}
                <Icono.bolsa />
                <span>{t("nav.pedido")}</span>
              </NavLink>
              {/* RF06: el llamado al mozo está siempre a mano. */}
              <button className={mozoEnCamino ? "llamado" : ""} onClick={() => contexto.llamarMozo("asistencia")} aria-label={t("mozo.llamar")}>
                <Icono.campana />
                <span>{mozoEnCamino ? "✓ " + t("nav.mozo") : t("nav.mozo")}</span>
              </button>
            </nav>
          )}
        </div>
        {toast && (
          <div className="mt-toast" role="status" aria-live="polite" key={toast.n}>
            <span aria-hidden="true">✓</span>
            <span>{toast.texto}</span>
          </div>
        )}
      </div>
    </Contexto.Provider>
  );
}
