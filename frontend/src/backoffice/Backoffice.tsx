import { useEffect, useMemo, useState, type FormEvent } from "react";
import { ErrorApi, apiBackoffice, login, type Personal } from "../api";
import { Comandas, Disponibilidad, Llamados, Metricas, Puestos, Validacion } from "./Paneles";

// Back-office del personal de Maido. Solo en español: lo usa el personal del local.

const CLAVE_TOKEN = "maidotouch.personal";

function leerToken(): string | null {
  try {
    return sessionStorage.getItem(CLAVE_TOKEN);
  } catch {
    return null;
  }
}

function guardarToken(token: string | null) {
  try {
    if (token) sessionStorage.setItem(CLAVE_TOKEN, token);
    else sessionStorage.removeItem(CLAVE_TOKEN);
  } catch {
    /* sin almacenamiento: la sesión dura lo que la página */
  }
}

type Panel = "cocina" | "barra" | "llamados" | "disponibilidad" | "validacion" | "puestos" | "metricas";

const NOMBRES: Record<Panel, string> = {
  cocina: "Comandas · cocina",
  barra: "Comandas · barra",
  llamados: "Llamados",
  disponibilidad: "Disponibilidad",
  validacion: "Validación de carta",
  puestos: "Puestos NFC/QR",
  metricas: "Métricas del piloto",
};

const PANELES: Record<Personal["rol"], Panel[]> = {
  administrador: ["cocina", "barra", "llamados", "disponibilidad", "validacion", "puestos", "metricas"],
  cocina: ["cocina", "disponibilidad", "validacion"],
  barra: ["barra", "disponibilidad"],
  sala: ["llamados"],
};

export function Backoffice() {
  const [token, setToken] = useState<string | null>(leerToken);
  const [personal, setPersonal] = useState<Personal | null>(null);
  const [panel, setPanel] = useState<Panel | null>(null);
  const api = useMemo(() => (token ? apiBackoffice(token) : null), [token]);

  const salir = () => {
    api?.salir().catch(() => undefined);
    guardarToken(null);
    setToken(null);
    setPersonal(null);
  };

  useEffect(() => {
    if (!api) return;
    api
      .yo()
      .then((p) => {
        setPersonal(p);
        setPanel((actual) => actual ?? PANELES[p.rol][0]);
      })
      .catch(() => {
        guardarToken(null);
        setToken(null);
      });
  }, [api]);

  if (!api) return <Ingreso alIngresar={(t) => { guardarToken(t); setToken(t); }} />;
  if (!personal || !panel) return <p className="cargando">Cargando…</p>;

  /** Un 401 en cualquier panel cierra la sesión del back-office. */
  const manejarError = (e: unknown): string => {
    if (e instanceof ErrorApi && e.estado === 401) salir();
    if (e instanceof ErrorApi && e.estado === 0) return "Sin conexión con el servidor.";
    return e instanceof Error ? e.message : "Error inesperado";
  };

  const props = { api, personal, manejarError };

  return (
    <div className="bo">
      <header className="bo-cabecera">
        <h1>MaidoTouch · back-office</h1>
        <span className="suave">
          {personal.identificador} ({personal.rol})
        </span>
        <button className="boton secundario peque" onClick={salir}>Salir</button>
      </header>
      <nav className="bo-tabs">
        {PANELES[personal.rol].map((p) => (
          <button key={p} aria-pressed={panel === p} onClick={() => setPanel(p)}>
            {NOMBRES[p]}
          </button>
        ))}
      </nav>
      {panel === "cocina" && <Comandas {...props} destino="cocina" />}
      {panel === "barra" && <Comandas {...props} destino="barra" />}
      {panel === "llamados" && <Llamados {...props} />}
      {panel === "disponibilidad" && <Disponibilidad {...props} />}
      {panel === "validacion" && <Validacion {...props} />}
      {panel === "puestos" && <Puestos {...props} />}
      {panel === "metricas" && <Metricas {...props} />}
    </div>
  );
}

function Ingreso({ alIngresar }: { alIngresar: (token: string) => void }) {
  const [identificador, setIdentificador] = useState("");
  const [clave, setClave] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  const enviar = async (e: FormEvent) => {
    e.preventDefault();
    setEnviando(true);
    setError(null);
    try {
      alIngresar((await login(identificador.trim(), clave)).token);
    } catch (err) {
      setError(err instanceof ErrorApi && err.estado === 401 ? "Usuario o clave incorrectos." : "No se pudo ingresar. Revisa la conexión.");
    } finally {
      setEnviando(false);
    }
  };

  return (
    <form className="tarjeta login" onSubmit={enviar}>
      <h1>Back-office</h1>
      <p className="suave">Acceso del personal de Maido.</p>
      <label className="campo">
        Usuario
        <input value={identificador} onChange={(e) => setIdentificador(e.target.value)} autoComplete="username" autoCapitalize="none" required />
      </label>
      <label className="campo">
        Clave
        <input type="password" value={clave} onChange={(e) => setClave(e.target.value)} autoComplete="current-password" required />
      </label>
      {error && <p className="aviso peligro">{error}</p>}
      <button className="boton bloque" disabled={enviando}>Ingresar</button>
    </form>
  );
}
