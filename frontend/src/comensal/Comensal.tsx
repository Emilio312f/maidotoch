import { useCallback, useEffect, useMemo, useState } from "react";
import { NavLink, Navigate, Route, Routes } from "react-router-dom";
import { ErrorApi, apiComensal, type Carrito, type EstadoSesion, type Idioma, type Llamado, type MotivoLlamado } from "../api";
import { textoError, traducir, type Clave } from "../i18n";
import { Asistente } from "./Asistente";
import { Carta } from "./Carta";
import { Contexto, guardarSesion, idiomaDelNavegador, leerSesion, useComensal, type ContextoComensal } from "./contexto";
import { Pedido } from "./Pedido";
import { Perfil } from "./Perfil";

export function Comensal() {
  const [sesion, setSesion] = useState<string | null>(leerSesion);
  const [estado, setEstado] = useState<EstadoSesion | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [carrito, setCarrito] = useState<Carrito | null>(null);
  const [llamado, setLlamado] = useState<Llamado | null>(null);

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

  if (!sesion || !api) {
    return (
      <div className="centro">
        <h1>{traducir(idioma, "inicio.titulo")}</h1>
        {aviso && <p className="aviso alerta">{aviso}</p>}
        <p>{traducir(idioma, "inicio.instruccion")}</p>
        <p className="suave">{traducir(idioma, "app.mozoSiempre")}</p>
      </div>
    );
  }
  if (!estado) return <p className="cargando">{traducir(idioma, "activacion.cargando")}</p>;

  const contexto: ContextoComensal = {
    api,
    estado,
    idioma,
    t: (clave: Clave, valores?: Record<string, string | number>) => traducir(idioma, clave, valores),
    manejarError,
    carrito,
    setCarrito,
    setEstado,
    llamado,
    llamarMozo: async (motivo: MotivoLlamado, productoId?: number) => {
      try {
        setLlamado(await api.llamar(motivo, productoId));
      } catch (e) {
        alert(manejarError(e));
      }
    },
  };

  const articulos = carrito?.borrador?.items.reduce((n, i) => n + i.cantidad, 0) ?? 0;

  return (
    <Contexto.Provider value={contexto}>
      <div className="app">
        <Cabecera />
        <main className="contenido">
          {llamado && <AvisoLlamado llamado={llamado} />}
          <Routes>
            <Route path="perfil" element={<Perfil />} />
            {estado.perfilCompleto ? (
              <>
                <Route path="carta" element={<Carta />} />
                <Route path="asistente" element={<Asistente />} />
                <Route path="pedido" element={<Pedido />} />
                <Route path="*" element={<Navigate to="/carta" replace />} />
              </>
            ) : (
              <Route path="*" element={<Navigate to="/perfil" replace />} />
            )}
          </Routes>
        </main>
        {estado.perfilCompleto && (
          <div className="navegacion">
            <nav>
              <NavLink to="/carta">{contexto.t("nav.carta")}</NavLink>
              <NavLink to="/asistente">{contexto.t("nav.asistente")}</NavLink>
              <NavLink to="/pedido">
                {contexto.t("nav.pedido")}
                {articulos > 0 && <span className="contador">{articulos}</span>}
              </NavLink>
            </nav>
          </div>
        )}
      </div>
    </Contexto.Provider>
  );
}

function Cabecera() {
  const { api, estado, idioma, t, setEstado, llamarMozo, manejarError } = useComensal();
  const cambiarIdioma = async () => {
    try {
      setEstado(await api.cambiarIdioma(idioma === "es" ? "en" : "es"));
    } catch (e) {
      alert(manejarError(e));
    }
  };
  return (
    <header className="cabecera">
      <div className="marca">
        {t("app.nombre")}
        <span className="puesto">{estado.puestoCodigo}</span>
      </div>
      <button className="boton claro" onClick={cambiarIdioma} lang={idioma === "es" ? "en" : "es"}>
        {t("idioma.cambiar")}
      </button>
      {/* RF06: el llamado al mozo está en todas las pantallas. */}
      <button className="boton peque" onClick={() => llamarMozo("asistencia")}>
        {t("mozo.llamar")}
      </button>
    </header>
  );
}

function AvisoLlamado({ llamado }: { llamado: Llamado }) {
  const { t } = useComensal();
  if (llamado.estado === "pendiente") return <p className="aviso ok" role="status">{t("mozo.llamado")}</p>;
  return null;
}
