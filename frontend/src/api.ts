// Cliente de la API REST de MaidoTouch. Los tipos reflejan las respuestas del backend Kotlin.

export class ErrorApi extends Error {
  constructor(
    public estado: number,
    public codigo: string,
    mensaje: string,
    public detalle: number[] = [],
  ) {
    super(mensaje);
  }
}

async function pedir<T>(metodo: string, ruta: string, cuerpo?: unknown, cabeceras: Record<string, string> = {}): Promise<T> {
  let respuesta: Response;
  try {
    respuesta = await fetch(`/api${ruta}`, {
      method: metodo,
      headers: { ...(cuerpo !== undefined ? { "Content-Type": "application/json" } : {}), ...cabeceras },
      body: cuerpo !== undefined ? JSON.stringify(cuerpo) : undefined,
    });
  } catch {
    throw new ErrorApi(0, "sin_conexion", "Sin conexión");
  }
  const texto = await respuesta.text();
  const datos = texto ? JSON.parse(texto) : undefined;
  if (!respuesta.ok) {
    throw new ErrorApi(respuesta.status, datos?.error ?? "error_interno", datos?.mensaje ?? respuesta.statusText, datos?.detalle ?? []);
  }
  return datos as T;
}

// ---------- Tipos ----------

export type Idioma = "es" | "en";

export interface EstadoSesion {
  idioma: Idioma;
  abierta: boolean;
  perfilCompleto: boolean;
  puestoCodigo: string;
  puestoTipo: "mesa" | "barra";
  restricciones: number[];
  preferencias: number[];
}

export interface OpcionPerfil {
  id: number;
  codigo: string;
  tipo?: "alergia" | "dieta" | "otra";
  nombre: string;
}

export interface Categoria {
  id: number;
  codigo: string;
  nombre: string;
  orden: number;
}

export interface ProductoCarta {
  id: number;
  categoriaId: number;
  tipo: "plato" | "bebida";
  destino: "cocina" | "barra";
  precioCentimos: number;
  fotoUrl?: string;
  disponible: boolean;
  nombre: string;
  descripcion: string;
  historia?: string;
  etiquetas: string[];
  alergenos: string[];
  trazas: string[];
}

export type Aptitud = "apto" | "no_apto" | "consultar";

export interface CartaSesion {
  idioma: Idioma;
  perfilCompleto: boolean;
  categorias: Categoria[];
  productos: { producto: ProductoCarta; aptitud?: Aptitud }[];
}

export interface Recomendado {
  recomendacionId: number;
  productoId: number;
  posicion: number;
  nombre: string;
  descripcion: string;
  precioCentimos: number;
  explicacion: string;
}

export interface RespuestaAsistente {
  recomendaciones: Recomendado[];
  motor?: "ia" | "fallback";
  sinCandidatos: boolean;
  derivacion?: { motivo: MotivoLlamado; productos: string[] };
  platosSinConfirmar: number;
}

export interface ItemPedido {
  id: number;
  productoId: number;
  nombre: string;
  cantidad: number;
  precioUnitarioCentimos: number;
  destino: "cocina" | "barra";
  recomendacionId?: number;
}

export interface Pedido {
  id: string;
  estado: "borrador" | "confirmado" | "enviado" | "completado";
  creadoEn: string;
  confirmadoEn?: string;
  totalCentimos: number;
  items: ItemPedido[];
  comandas: { destino: "cocina" | "barra"; estado: string }[];
}

export interface Carrito {
  borrador?: Pedido;
  enviados: Pedido[];
}

export type MotivoLlamado = "asistencia" | "restriccion_incierta" | "fallo_ia";

export interface Llamado {
  id: number;
  motivo: MotivoLlamado;
  estado: "pendiente" | "atendido";
  puesto: string;
  producto?: string;
  creadoEn: string;
  atendidoEn?: string;
}

export interface Personal {
  id: number;
  identificador: string;
  rol: "administrador" | "cocina" | "barra" | "sala";
}

export interface Comanda {
  id: number;
  pedidoId: string;
  destino: "cocina" | "barra";
  estado: "pendiente" | "recibida" | "preparando" | "lista";
  puesto: string;
  creadaEn: string;
  recibidaEn?: string;
  intentos: number;
  items: { nombre: string; cantidad: number }[];
}

export interface ProductoBackoffice {
  id: number;
  codigo: string;
  nombre: string;
  categoria: string;
  destino: "cocina" | "barra";
  precioCentimos: number;
  activo: boolean;
  disponible: boolean;
  validado: boolean;
  revision: number;
}

export interface CambioDisponibilidad {
  productoId: number;
  producto: string;
  disponible: boolean;
  registradoPor: string;
  fecha: string;
  motivo: string;
}

export interface DetalleProducto {
  producto: ProductoBackoffice;
  textos: { idioma: Idioma; nombre: string; descripcion: string; historia?: string }[];
  ingredientes: string[];
  alergenos: { alergeno: string; origen: string }[];
  preferencias: string[];
  evaluaciones: { restriccion: string; resultado: string; evidencia: string; validadoPor: string; validadoEn: string }[];
  restriccionesSinEvaluar: string[];
  validadoPor?: string;
  validadoEn?: string;
}

export interface PuestoConEnlaces {
  puesto: { id: number; codigo: string; tipo: "mesa" | "barra"; activo: boolean; accesos: { id: number; canal: "nfc" | "qr"; token: string; activo: boolean }[] };
  enlaceNfc?: string;
  enlaceQr?: string;
}

export interface Metricas {
  recomendaciones: number;
  mostradas: number;
  aceptadas: number;
  rechazadas: number;
  tasaAceptacion?: number;
  consultasPorMotor: Record<string, number>;
  latenciaP95Ms?: number;
  llamadosPorMotivo: Record<string, number>;
  pedidosConfirmados: number;
  ticketPromedioCentimos?: number;
  encuestas: number;
  satisfaccionPromedio?: number;
}

// ---------- Comensal ----------

export const apiComensal = (sesion: string) => {
  const c = { "X-Sesion": sesion };
  return {
    estado: () => pedir<EstadoSesion>("GET", "/sesion", undefined, c),
    guardarPerfil: (restricciones: number[], preferencias: number[]) =>
      pedir<EstadoSesion>("PUT", "/sesion/perfil", { restricciones, preferencias }, c),
    cambiarIdioma: (idioma: Idioma) => pedir<EstadoSesion>("PUT", "/sesion/idioma", { idioma }, c),
    carta: () => pedir<CartaSesion>("GET", "/sesion/carta", undefined, c),
    preguntar: (consulta: string) => pedir<RespuestaAsistente>("POST", "/sesion/asistente", { consulta }, c),
    evento: (recomendacionId: number, accion: "aceptada" | "rechazada" | "modificada") =>
      pedir<unknown>("POST", `/sesion/recomendaciones/${recomendacionId}/eventos`, { accion }, c),
    carrito: () => pedir<Carrito>("GET", "/sesion/pedido", undefined, c),
    agregar: (productoId: number, cantidad = 1, recomendacionId?: number) =>
      pedir<Carrito>("POST", "/sesion/pedido/items", { productoId, cantidad, recomendacionId }, c),
    cambiarCantidad: (itemId: number, cantidad: number) => pedir<Carrito>("PATCH", `/sesion/pedido/items/${itemId}`, { cantidad }, c),
    quitar: (itemId: number) => pedir<Carrito>("DELETE", `/sesion/pedido/items/${itemId}`, undefined, c),
    confirmar: () => pedir<Pedido>("POST", "/sesion/pedido/confirmar", { confirmo: true }, c),
    llamados: () => pedir<Llamado[]>("GET", "/sesion/llamados", undefined, c),
    llamar: (motivo: MotivoLlamado, productoId?: number) => pedir<Llamado>("POST", "/sesion/llamados", { motivo, productoId }, c),
    encuesta: (satisfaccion: number) => pedir<unknown>("POST", "/sesion/encuesta", { satisfaccion }, c),
    cerrar: () => pedir<unknown>("POST", "/sesion/cerrar", undefined, c),
  };
};

export const iniciarSesion = (token: string, idioma: Idioma) =>
  pedir<{ sesionId: string; estado: EstadoSesion }>("POST", "/sesiones", { token, idioma });

export const opcionesPerfil = (idioma: Idioma) =>
  pedir<{ restricciones: OpcionPerfil[]; preferencias: OpcionPerfil[] }>("GET", `/opciones-perfil?idioma=${idioma}`);

// ---------- Back-office ----------

export const login = (identificador: string, clave: string) =>
  pedir<{ token: string; personal: Personal }>("POST", "/backoffice/login", { identificador, clave });

export const apiBackoffice = (token: string) => {
  const c = { Authorization: `Bearer ${token}` };
  return {
    yo: () => pedir<Personal>("GET", "/backoffice/yo", undefined, c),
    salir: () => pedir<unknown>("POST", "/backoffice/logout", undefined, c),
    productos: () => pedir<ProductoBackoffice[]>("GET", "/backoffice/productos", undefined, c),
    detalle: (id: number) => pedir<DetalleProducto>("GET", `/backoffice/productos/${id}`, undefined, c),
    disponibilidad: (id: number, disponible: boolean, motivo: string) =>
      pedir<ProductoBackoffice>("POST", `/backoffice/productos/${id}/disponibilidad`, { disponible, motivo }, c),
    historial: () => pedir<CambioDisponibilidad[]>("GET", "/backoffice/disponibilidad/historial", undefined, c),
    validar: (id: number) => pedir<DetalleProducto>("POST", `/backoffice/productos/${id}/validar`, undefined, c),
    comandas: (destino: "cocina" | "barra") => pedir<Comanda[]>("GET", `/backoffice/comandas?destino=${destino}`, undefined, c),
    avanzarComanda: (id: number, estado: string) => pedir<unknown>("POST", `/backoffice/comandas/${id}/estado`, { estado }, c),
    llamados: () => pedir<Llamado[]>("GET", "/backoffice/llamados", undefined, c),
    atender: (id: number) => pedir<Llamado>("POST", `/backoffice/llamados/${id}/atender`, undefined, c),
    puestos: () => pedir<PuestoConEnlaces[]>("GET", "/backoffice/puestos", undefined, c),
    crearPuesto: (codigo: string, tipo: "mesa" | "barra") => pedir<PuestoConEnlaces>("POST", "/backoffice/puestos", { codigo, tipo }, c),
    activarPuesto: (id: number, activo: boolean) => pedir<PuestoConEnlaces>("POST", `/backoffice/puestos/${id}/activo`, { activo }, c),
    metricas: () => pedir<Metricas>("GET", "/backoffice/metricas", undefined, c),
  };
};

export const soles = (centimos: number) => `S/ ${(centimos / 100).toFixed(2)}`;
