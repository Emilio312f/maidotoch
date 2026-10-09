import { useCallback, useEffect, useState, type FormEvent } from "react";
import QRCode from "qrcode";
import {
  soles,
  type apiBackoffice,
  type CambioDisponibilidad,
  type Comanda,
  type DetalleProducto,
  type Llamado,
  type Metricas as TipoMetricas,
  type Personal,
  type ProductoBackoffice,
  type PuestoConEnlaces,
} from "../api";

interface PropsPanel {
  api: ReturnType<typeof apiBackoffice>;
  personal: Personal;
  manejarError: (e: unknown) => string;
}

/** Consulta periódica mientras el panel está abierto (pantallas de cocina, barra y sala). */
function useSondeo<T>(cargar: () => Promise<T>, cadaMs: number, manejarError: (e: unknown) => string) {
  const [datos, setDatos] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const refrescar = useCallback(() => {
    cargar()
      .then((d) => {
        setDatos(d);
        setError(null);
      })
      .catch((e) => setError(manejarError(e)));
  }, [cargar, manejarError]);
  useEffect(() => {
    refrescar();
    if (cadaMs <= 0) return;
    const intervalo = setInterval(refrescar, cadaMs);
    return () => clearInterval(intervalo);
  }, [refrescar, cadaMs]);
  return { datos, error, refrescar };
}

const hora = (iso?: string) => (iso ? new Date(iso).toLocaleTimeString("es-PE", { hour: "2-digit", minute: "2-digit" }) : "");

const minutosDesde = (iso: string) => Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));

// ---------- Comandas (RF05) ----------

const SIGUIENTE: Record<Comanda["estado"], { estado: string; texto: string }> = {
  pendiente: { estado: "recibida", texto: "Recibir" },
  recibida: { estado: "preparando", texto: "En preparación" },
  preparando: { estado: "lista", texto: "Lista" },
  lista: { estado: "entregada", texto: "Entregada" },
};

export function Comandas({ api, manejarError, destino }: PropsPanel & { destino: "cocina" | "barra" }) {
  const cargar = useCallback(() => api.comandas(destino), [api, destino]);
  const { datos, error, refrescar } = useSondeo(cargar, 1500, manejarError);
  const [errorAccion, setErrorAccion] = useState<string | null>(null);

  const avanzar = async (comanda: Comanda) => {
    setErrorAccion(null);
    try {
      await api.avanzarComanda(comanda.id, SIGUIENTE[comanda.estado].estado);
      refrescar();
    } catch (e) {
      setErrorAccion(manejarError(e));
    }
  };

  return (
    <section>
      {(error || errorAccion) && <p className="aviso peligro">{error ?? errorAccion}</p>}
      {datos && datos.length === 0 && <p className="suave">Sin comandas activas.</p>}
      <div className="rejilla">
        {datos?.map((c) => (
          <article key={c.id} className={`tarjeta comanda ${c.estado}`}>
            <div className="fila entre">
              <h2>{c.puesto}</h2>
              <span className="etiqueta neutra">{c.estado}</span>
            </div>
            <p className="suave">
              Hace {minutosDesde(c.creadaEn)} min · {hora(c.creadaEn)}
            </p>
            <ul>
              {c.items.map((i, n) => (
                <li key={n}>
                  <strong>{i.cantidad} ×</strong> {i.nombre}
                </li>
              ))}
            </ul>
            <button className={`boton bloque ${c.estado === "pendiente" ? "" : "ok"}`} onClick={() => avanzar(c)}>
              {SIGUIENTE[c.estado].texto}
            </button>
          </article>
        ))}
      </div>
    </section>
  );
}

// ---------- Llamados al mozo (RF06 · RF14) ----------

const MOTIVOS: Record<Llamado["motivo"], string> = {
  asistencia: "Pide atención",
  restriccion_incierta: "Consulta por restricción alimentaria",
  fallo_ia: "Problema con el asistente",
};

export function Llamados({ api, manejarError }: PropsPanel) {
  const cargar = useCallback(() => api.llamados(), [api]);
  const { datos, error, refrescar } = useSondeo(cargar, 3000, manejarError);

  return (
    <section>
      {error && <p className="aviso peligro">{error}</p>}
      {datos && datos.length === 0 && <p className="suave">Sin llamados pendientes.</p>}
      <div className="rejilla">
        {datos?.map((l) => (
          <article key={l.id} className={`tarjeta comanda ${l.motivo === "restriccion_incierta" ? "pendiente" : ""}`}>
            <h2>{l.puesto}</h2>
            <p>
              <strong>{MOTIVOS[l.motivo]}</strong>
              {l.producto && <> · {l.producto}</>}
            </p>
            {l.motivo === "restriccion_incierta" && (
              <p className="aviso alerta">Confirmar con cocina antes de recomendar o servir. El sistema no pudo validar la restricción.</p>
            )}
            <p className="suave">Hace {minutosDesde(l.creadoEn)} min</p>
            <button className="boton bloque" onClick={() => api.atender(l.id).then(refrescar).catch(manejarError)}>
              Atendido
            </button>
          </article>
        ))}
      </div>
    </section>
  );
}

// ---------- Disponibilidad (RF08) ----------

export function Disponibilidad({ api, personal, manejarError }: PropsPanel) {
  const [productos, setProductos] = useState<ProductoBackoffice[] | null>(null);
  const [historial, setHistorial] = useState<CambioDisponibilidad[]>([]);
  const [error, setError] = useState<string | null>(null);

  const cargar = useCallback(() => {
    api.productos().then(setProductos).catch((e) => setError(manejarError(e)));
    api.historial().then(setHistorial).catch(() => undefined);
  }, [api, manejarError]);
  useEffect(cargar, [cargar]);

  const cambiar = async (p: ProductoBackoffice) => {
    const motivo = window.prompt(`${p.disponible ? "Desactivar" : "Activar"} «${p.nombre}». Motivo:`, p.disponible ? "Se agotó" : "Disponible para el servicio");
    if (!motivo?.trim()) return;
    try {
      await api.disponibilidad(p.id, !p.disponible, motivo.trim());
      cargar();
    } catch (e) {
      setError(manejarError(e));
    }
  };

  const propios = productos?.filter((p) => personal.rol === "administrador" || p.destino === personal.rol) ?? [];

  return (
    <section>
      <p className="suave">Activa o desactiva productos antes de cada servicio. Cada cambio queda registrado con tu usuario y el motivo.</p>
      {error && <p className="aviso peligro">{error}</p>}
      <div className="tabla-contenedor">
        <table className="tabla">
          <thead>
            <tr>
              <th>Producto</th>
              <th>Categoría</th>
              <th>Precio</th>
              <th>Estado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {propios.map((p) => (
              <tr key={p.id}>
                <td>
                  {p.nombre}
                  {!p.validado && <div className="etiqueta consultar">Sin validar: no se recomienda ni se puede pedir</div>}
                  {!p.activo && <div className="etiqueta neutra">Inactivo</div>}
                </td>
                <td>{p.categoria}</td>
                <td>{soles(p.precioCentimos)}</td>
                <td>{p.disponible ? <span className="etiqueta apto">Disponible</span> : <span className="etiqueta no_apto">No disponible</span>}</td>
                <td>
                  <button className={`boton peque ${p.disponible ? "secundario" : ""}`} onClick={() => cambiar(p)}>
                    {p.disponible ? "Desactivar" : "Activar"}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2 style={{ marginTop: 24 }}>Últimos cambios</h2>
      <div className="tabla-contenedor">
        <table className="tabla">
          <thead>
            <tr>
              <th>Fecha</th>
              <th>Producto</th>
              <th>Cambio</th>
              <th>Quién</th>
              <th>Motivo</th>
            </tr>
          </thead>
          <tbody>
            {historial.slice(0, 30).map((h, i) => (
              <tr key={i}>
                <td>{new Date(h.fecha).toLocaleString("es-PE")}</td>
                <td>{h.producto}</td>
                <td>{h.disponible ? "Activado" : "Desactivado"}</td>
                <td>{h.registradoPor}</td>
                <td>{h.motivo}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

// ---------- Validación del catálogo por cocina ----------

export function Validacion({ api, manejarError }: PropsPanel) {
  const [productos, setProductos] = useState<ProductoBackoffice[] | null>(null);
  const [detalle, setDetalle] = useState<DetalleProducto | null>(null);
  const [error, setError] = useState<string | null>(null);

  const cargar = useCallback(() => {
    api.productos().then(setProductos).catch((e) => setError(manejarError(e)));
  }, [api, manejarError]);
  useEffect(cargar, [cargar]);

  const abrir = async (id: number) => {
    setError(null);
    try {
      setDetalle(await api.detalle(id));
    } catch (e) {
      setError(manejarError(e));
    }
  };

  const validar = async () => {
    if (!detalle) return;
    const ok = window.confirm(
      "Confirmas que revisaste con cocina los textos, ingredientes, alérgenos y evaluaciones de este producto y que son correctos.",
    );
    if (!ok) return;
    try {
      setDetalle(await api.validar(detalle.producto.id));
      cargar();
    } catch (e) {
      setError(manejarError(e));
    }
  };

  const ordenados = [...(productos ?? [])].sort((a, b) => Number(a.validado) - Number(b.validado));

  return (
    <section>
      <p className="suave">
        Un producto solo se recomienda y se puede pedir si está validado. Cualquier cambio en su receta, alérgenos, textos o precio anula la
        validación automáticamente.
      </p>
      {error && <p className="aviso peligro">{error}</p>}
      <div className="rejilla">
        <div className="tarjeta">
          <h2>Productos</h2>
          {ordenados.map((p) => (
            <div key={p.id} className="fila entre" style={{ padding: "6px 0", borderBottom: "1px solid var(--borde)" }}>
              <button className="enlace" onClick={() => abrir(p.id)}>{p.nombre}</button>
              {p.validado ? <span className="etiqueta apto">Validado</span> : <span className="etiqueta consultar">Pendiente</span>}
            </div>
          ))}
        </div>
        {detalle && <FichaProducto detalle={detalle} validar={validar} />}
      </div>
    </section>
  );
}

function FichaProducto({ detalle, validar }: { detalle: DetalleProducto; validar: () => void }) {
  return (
    <div className="tarjeta" style={{ gridColumn: "span 2", minWidth: 0 }}>
      <div className="fila entre">
        <h2>{detalle.producto.nombre}</h2>
        {detalle.producto.validado ? (
          <span className="etiqueta apto">
            Validado por {detalle.validadoPor} · {new Date(detalle.validadoEn!).toLocaleString("es-PE")}
          </span>
        ) : (
          <span className="etiqueta consultar">Pendiente de validación</span>
        )}
      </div>
      <p className="suave">
        {detalle.producto.codigo} · {detalle.producto.categoria} · {soles(detalle.producto.precioCentimos)} · destino {detalle.producto.destino} · revisión{" "}
        {detalle.producto.revision}
      </p>
      {detalle.textos.map((t) => (
        <p key={t.idioma}>
          <strong>{t.idioma.toUpperCase()}:</strong> {t.nombre} — {t.descripcion}
          {t.historia && <span className="suave"> ({t.historia})</span>}
        </p>
      ))}
      <p>
        <strong>Ingredientes:</strong> {detalle.ingredientes.join(", ")}
      </p>
      <p>
        <strong>Alérgenos:</strong>{" "}
        {detalle.alergenos.length ? detalle.alergenos.map((a) => `${a.alergeno} (${a.origen})`).join(", ") : "ninguno registrado"}
      </p>
      <p>
        <strong>Etiquetas de preferencia:</strong> {detalle.preferencias.join(", ") || "ninguna"}
      </p>
      <div className="tabla-contenedor">
        <table className="tabla">
          <thead>
            <tr>
              <th>Restricción</th>
              <th>Resultado</th>
              <th>Evidencia</th>
              <th>Evaluó</th>
            </tr>
          </thead>
          <tbody>
            {detalle.evaluaciones.map((e) => (
              <tr key={e.restriccion}>
                <td>{e.restriccion}</td>
                <td>
                  <span className={`etiqueta ${e.resultado === "compatible" ? "apto" : e.resultado === "incompatible" ? "no_apto" : "consultar"}`}>{e.resultado}</span>
                </td>
                <td>{e.evidencia}</td>
                <td>{e.validadoPor}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {detalle.restriccionesSinEvaluar.length > 0 && (
        <p className="aviso alerta" style={{ marginTop: 12 }}>
          Sin evaluación para: {detalle.restriccionesSinEvaluar.join(", ")}. Para esas restricciones el plato no se recomienda y se deriva al mozo.
        </p>
      )}
      {!detalle.producto.validado && (
        <button className="boton" style={{ marginTop: 12 }} onClick={validar}>
          Validar producto
        </button>
      )}
    </div>
  );
}

// ---------- Puestos NFC/QR ----------

function Qr({ texto }: { texto: string }) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    QRCode.toDataURL(texto, { margin: 1, width: 264 }).then(setSrc).catch(() => setSrc(null));
  }, [texto]);
  return src ? <img className="qr" src={src} alt={`QR ${texto}`} /> : null;
}

export function Puestos({ api, manejarError }: PropsPanel) {
  const [puestos, setPuestos] = useState<PuestoConEnlaces[] | null>(null);
  const [codigo, setCodigo] = useState("");
  const [tipo, setTipo] = useState<"mesa" | "barra">("mesa");
  const [error, setError] = useState<string | null>(null);

  const cargar = useCallback(() => {
    api.puestos().then(setPuestos).catch((e) => setError(manejarError(e)));
  }, [api, manejarError]);
  useEffect(cargar, [cargar]);

  const crear = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.crearPuesto(codigo.trim(), tipo);
      setCodigo("");
      cargar();
    } catch (err) {
      setError(manejarError(err));
    }
  };

  const alternar = async (p: PuestoConEnlaces) => {
    try {
      await api.activarPuesto(p.puesto.id, !p.puesto.activo);
      cargar();
    } catch (e) {
      setError(manejarError(e));
    }
  };

  return (
    <section>
      <p className="suave">
        Cada puesto tiene un enlace para programar su tag NFC y otro para el QR de respaldo. Desactivar un puesto lo devuelve al pedido tradicional
        sin afectar a los demás.
      </p>
      <form className="tarjeta fila" style={{ flexWrap: "wrap", alignItems: "end", marginBottom: 16 }} onSubmit={crear}>
        <label className="campo" style={{ margin: 0 }}>
          Código
          <input value={codigo} onChange={(e) => setCodigo(e.target.value)} placeholder="M12" required maxLength={20} />
        </label>
        <label className="campo" style={{ margin: 0 }}>
          Tipo
          <select value={tipo} onChange={(e) => setTipo(e.target.value as "mesa" | "barra")}>
            <option value="mesa">Mesa</option>
            <option value="barra">Barra</option>
          </select>
        </label>
        <button className="boton">Crear puesto</button>
      </form>
      {error && <p className="aviso peligro">{error}</p>}
      <div className="rejilla">
        {puestos?.map((p) => (
          <article key={p.puesto.id} className="tarjeta">
            <div className="fila entre">
              <h2>{p.puesto.codigo}</h2>
              <span className={`etiqueta ${p.puesto.activo ? "apto" : "neutra"}`}>{p.puesto.activo ? "Activo" : "Inactivo"}</span>
            </div>
            <p className="suave">{p.puesto.tipo === "mesa" ? "Mesa" : "Barra"}</p>
            <p>
              <strong>Tag NFC:</strong> <code>{p.enlaceNfc}</code>
            </p>
            <p>
              <strong>QR de respaldo:</strong> <code>{p.enlaceQr}</code>
            </p>
            {p.enlaceQr && <Qr texto={p.enlaceQr} />}
            <div>
              <button className={`boton peque ${p.puesto.activo ? "secundario" : ""}`} onClick={() => alternar(p)}>
                {p.puesto.activo ? "Desactivar puesto" : "Activar puesto"}
              </button>
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}

// ---------- Métricas del piloto ----------

export function Metricas({ api, manejarError }: PropsPanel) {
  const cargar = useCallback(() => api.metricas(), [api]);
  const { datos: m, error } = useSondeo<TipoMetricas>(cargar, 30000, manejarError);
  if (error) return <p className="aviso peligro">{error}</p>;
  if (!m) return <p className="cargando">Cargando…</p>;

  const cifra = (titulo: string, valor: string, nota?: string) => (
    <div className="tarjeta cifra">
      <span className="suave">{titulo}</span>
      <strong>{valor}</strong>
      {nota && <span className="suave">{nota}</span>}
    </div>
  );
  const consultas = Object.values(m.consultasPorMotor).reduce((a, b) => a + b, 0);

  return (
    <section className="cifras">
      {cifra("Aceptación de recomendaciones", m.tasaAceptacion == null ? "—" : `${m.tasaAceptacion.toFixed(1)} %`, "Meta del piloto: ≥ 40 %")}
      {cifra("Recomendaciones mostradas", String(m.mostradas), `${m.aceptadas} aceptadas · ${m.rechazadas} rechazadas`)}
      {cifra(
        "Consultas al asistente",
        String(consultas),
        Object.entries(m.consultasPorMotor).map(([k, v]) => `${k}: ${v}`).join(" · ") || "sin consultas",
      )}
      {cifra("Latencia del asistente (P95)", m.latenciaP95Ms == null ? "—" : `${(m.latenciaP95Ms / 1000).toFixed(2)} s`, "Meta: ≤ 3 s")}
      {cifra("Pedidos enviados", String(m.pedidosConfirmados))}
      {cifra("Ticket promedio", m.ticketPromedioCentimos == null ? "—" : soles(m.ticketPromedioCentimos))}
      {cifra("Satisfacción (1–5)", m.satisfaccionPromedio == null ? "—" : m.satisfaccionPromedio.toFixed(2), `${m.encuestas} encuestas`)}
      {cifra(
        "Llamados al mozo",
        String(Object.values(m.llamadosPorMotivo).reduce((a, b) => a + b, 0)),
        Object.entries(m.llamadosPorMotivo).map(([k, v]) => `${k}: ${v}`).join(" · ") || "sin llamados",
      )}
    </section>
  );
}
