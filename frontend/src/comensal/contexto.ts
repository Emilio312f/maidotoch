import { createContext, useContext } from "react";
import type { Carrito, EstadoSesion, Idioma, Llamado, MotivoLlamado, apiComensal } from "../api";
import type { Clave } from "../i18n";

export interface ContextoComensal {
  api: ReturnType<typeof apiComensal>;
  estado: EstadoSesion;
  idioma: Idioma;
  t: (clave: Clave, valores?: Record<string, string | number>) => string;
  /** Traduce un error de la API y, si la sesión terminó, vuelve a la pantalla de inicio. */
  manejarError: (e: unknown) => string;
  carrito: Carrito | null;
  setCarrito: (c: Carrito) => void;
  setEstado: (e: EstadoSesion) => void;
  llamado: Llamado | null;
  llamarMozo: (motivo: MotivoLlamado, productoId?: number) => Promise<void>;
}

export const Contexto = createContext<ContextoComensal | null>(null);

export function useComensal(): ContextoComensal {
  const ctx = useContext(Contexto);
  if (!ctx) throw new Error("useComensal fuera de <Comensal>");
  return ctx;
}

// La sesión vive solo en esta pestaña (sessionStorage): sin cuentas ni datos personales.
const CLAVE_SESION = "maidotouch.sesion";

export function leerSesion(): string | null {
  try {
    return sessionStorage.getItem(CLAVE_SESION);
  } catch {
    return null;
  }
}

export function guardarSesion(id: string | null) {
  try {
    if (id) sessionStorage.setItem(CLAVE_SESION, id);
    else sessionStorage.removeItem(CLAVE_SESION);
  } catch {
    /* sin almacenamiento disponible: la sesión dura lo que la página */
  }
}

export function idiomaDelNavegador(): Idioma {
  return navigator.language?.toLowerCase().startsWith("en") ? "en" : "es";
}
