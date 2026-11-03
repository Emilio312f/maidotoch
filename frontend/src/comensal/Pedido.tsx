import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ErrorApi, soles, type ItemPedido, type Pedido as TipoPedido } from "../comun/api";
import type { Clave } from "../comun/i18n";
import { textoVisible } from "../comun/textos";
import { useComensal } from "./contexto";
import { BarraSuperior, Cargando, Foto, Icono, nombrePuesto, useFotos } from "./piezas";

/**
 * Carrito, revisión y estado (RF05 · RF10 · RF11): nada sale a cocina sin la confirmación explícita
 * en la pantalla de revisión, y un pedido enviado no se cancela desde la app.
 */
export function Pedido() {
  const { api, estado, t, carrito, setCarrito, manejarError } = useComensal();
  const navegar = useNavigate();
  const fotos = useFotos();
  const [revisando, setRevisando] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [invalidos, setInvalidos] = useState<number[]>([]);

  useEffect(() => {
    api.carrito().then(setCarrito).catch((e) => setError(manejarError(e)));
  }, [api, setCarrito, manejarError]);

  // Seguimiento de los pedidos enviados hasta que se entreguen.
  const enCurso = carrito?.enviados.some((p) => p.estado !== "completado") ?? false;
  useEffect(() => {
    if (!enCurso) return;
    const intervalo = setInterval(() => api.carrito().then(setCarrito).catch(() => undefined), 5000);
    return () => clearInterval(intervalo);
  }, [api, enCurso, setCarrito]);

  const actualizar = async (accion: () => ReturnType<typeof api.carrito>) => {
    setError(null);
    try {
      setCarrito(await accion());
      setInvalidos([]);
    } catch (e) {
      setError(manejarError(e));
    }
  };

  const confirmar = async () => {
    setEnviando(true);
    setError(null);
    try {
      await api.confirmar();
      setCarrito(await api.carrito());
      setRevisando(false);
      window.scrollTo(0, 0);
    } catch (e) {
      setRevisando(false);
      setError(manejarError(e));
      if (e instanceof ErrorApi && e.codigo === "revisar_items") setInvalidos(e.detalle);
    } finally {
      setEnviando(false);
    }
  };

  if (!carrito) {
    return (
      <section>
        <BarraSuperior />
        {error ? <p className="mt-nota peligro" style={{ margin: 20 }}>{error}</p> : <Cargando />}
      </section>
    );
  }

  const borrador = carrito.borrador?.items.length ? carrito.borrador : undefined;
  const cantidad = borrador?.items.reduce((n, i) => n + i.cantidad, 0) ?? 0;
  const puesto = nombrePuesto(estado, t);

  // Revisión: último paso antes de enviar.
  if (borrador && revisando) {
    return (
      <section>
        <header className="mt-topbar">
          <button className="mt-icono" onClick={() => setRevisando(false)} aria-label={t("comun.volver")}>←</button>
          <span className="mt-topbar-titulo">{t("pedido.confirmarTitulo")}</span>
          <span style={{ width: 40 }} />
        </header>
        <div className="mt-pad mt-vista">
          <div className="mt-eyebrow">{t("pedido.ultimoPaso")}</div>
          <h1>{t("pedido.revisionTitulo")}</h1>
          <p className="mt-muted">{t("pedido.confirmarAviso")}</p>
          <div className="mt-items">
            {borrador.items.map((i) => (
              <div className="mt-item" key={i.id}>
                <Foto className="mt-item-foto" src={fotos[i.productoId]} nombre={i.nombre} />
                <div>
                  <strong>{i.cantidad} × {textoVisible(i.nombre)}</strong>
                  <div className="mt-muted" style={{ fontSize: 13 }}>{t(i.destino === "cocina" ? "pedido.cocina" : "pedido.barra")}</div>
                </div>
                <strong>{soles(i.precioUnitarioCentimos * i.cantidad)}</strong>
              </div>
            ))}
          </div>
          <div className="mt-resumen">
            <div className="mt-linea"><span>{t("pedido.puesto")}</span><strong>{puesto}</strong></div>
            <div className="mt-linea"><span>{t("pedido.items")}</span><strong>{t("pedido.cantidadProductos", { n: cantidad })}</strong></div>
            <div className="mt-linea total"><span>{t("pedido.total")}</span><span>{soles(borrador.totalCentimos)}</span></div>
          </div>
          <div className="mt-nota ok"><span className="mt-nota-icono" aria-hidden="true">✓</span><span>{t("pedido.revisionNota")}</span></div>
          <div className="mt-pila">
            <button className="mt-btn primario bloque" onClick={confirmar} disabled={enviando}>
              {enviando ? t("pedido.enviando") : t("pedido.confirmarSi")}
            </button>
            <button className="mt-btn fantasma bloque" onClick={() => setRevisando(false)} disabled={enviando}>
              {t("pedido.seguirEditando")}
            </button>
          </div>
        </div>
      </section>
    );
  }

  // Carrito con productos.
  if (borrador) {
    return (
      <section>
        <BarraSuperior />
        <div className="mt-pad mt-vista" style={{ paddingBottom: 180 }}>
          <div className="mt-eyebrow">{puesto}</div>
          <h1>{t("pedido.titulo")}</h1>
          <p className="mt-muted">{t("pedido.editable")}</p>
          {error && <p className="mt-nota peligro">{error}</p>}
          <div className="mt-items">
            {borrador.items.map((item) => (
              <LineaPedido
                key={item.id}
                item={item}
                foto={fotos[item.productoId]}
                invalido={invalidos.includes(item.id)}
                cambiar={(n) => actualizar(() => (n === 0 ? api.quitar(item.id) : api.cambiarCantidad(item.id, n)))}
              />
            ))}
          </div>
          <div className="mt-resumen">
            <div className="mt-linea"><span>{t("pedido.items")}</span><strong>{t("pedido.cantidadProductos", { n: cantidad })}</strong></div>
            <div className="mt-linea total"><span>{t("pedido.total")}</span><span>{soles(borrador.totalCentimos)}</span></div>
          </div>
          {carrito.enviados.length > 0 && <Anteriores pedidos={carrito.enviados} />}
        </div>
        <div className="mt-fijo">
          <button className="mt-btn primario bloque" onClick={() => { setRevisando(true); window.scrollTo(0, 0); }}>
            {t("pedido.revisarBoton")} · {soles(borrador.totalCentimos)}
          </button>
        </div>
      </section>
    );
  }

  // Sin borrador: estado del último pedido enviado, o el carrito vacío.
  const [ultimo, ...anteriores] = [...carrito.enviados].sort((a, b) => (b.confirmadoEn ?? b.creadoEn).localeCompare(a.confirmadoEn ?? a.creadoEn));
  return (
    <section>
      <BarraSuperior />
      <div className="mt-vista">
        {error && <p className="mt-nota peligro" style={{ margin: 20 }}>{error}</p>}
        {!ultimo ? (
          <div className="mt-vacio">
            <div className="mt-simbolo"><Icono.bolsa /></div>
            <h1>{t("pedido.vacioTitulo")}</h1>
            <p className="mt-muted">{t("pedido.vacio")}</p>
            <button className="mt-btn primario" onClick={() => navegar("/carta")}>{t("asistente.verCarta")}</button>
          </div>
        ) : (
          <EstadoPedido pedido={ultimo} anteriores={anteriores} />
        )}
      </div>
    </section>
  );
}

function LineaPedido({ item, foto, invalido, cambiar }: { item: ItemPedido; foto?: string; invalido: boolean; cambiar: (cantidad: number) => void }) {
  const { t } = useComensal();
  const nombre = textoVisible(item.nombre);
  return (
    <div className={`mt-item ${invalido ? "invalido" : ""}`}>
      <Foto className="mt-item-foto" src={foto} nombre={item.nombre} />
      <div>
        <strong>{nombre}</strong>
        {invalido && <div className="mt-muted" style={{ fontSize: 13, color: "var(--danger)" }}>{t("pedido.revisar")}</div>}
        <div className="mt-cantidad" role="group" aria-label={nombre}>
          <button onClick={() => cambiar(item.cantidad - 1)} aria-label={item.cantidad === 1 ? t("pedido.quitar") : "−"}>
            {item.cantidad === 1 ? "×" : "−"}
          </button>
          <span>{item.cantidad}</span>
          <button onClick={() => cambiar(item.cantidad + 1)} aria-label="+" disabled={item.cantidad >= 20}>+</button>
        </div>
      </div>
      <strong>{soles(item.precioUnitarioCentimos * item.cantidad)}</strong>
    </div>
  );
}

/** Paso actual del pedido según sus comandas: 0 registrado, 1 recibido, 2 en preparación, 3 entregado. */
function pasoActual(pedido: TipoPedido): number {
  if (pedido.estado === "completado") return 3;
  const estados = pedido.comandas.map((c) => c.estado);
  if (estados.length && estados.every((e) => e === "entregada")) return 3;
  if (estados.some((e) => e === "preparando" || e === "lista" || e === "entregada")) return 2;
  if (estados.length && estados.every((e) => e !== "pendiente")) return 1;
  return 0;
}

/** Texto de a quién se espera todavía, o si ya está en preparación. */
function estadoVisible(pedido: TipoPedido): Clave {
  if (pedido.estado === "completado") return "pedido.estado.completado";
  const pendientes = pedido.comandas.filter((c) => c.estado === "pendiente").map((c) => c.destino);
  if (pendientes.length === 0) return "pedido.estado.preparando";
  if (pendientes.length > 1) return "pedido.estado.esperandoAmbos";
  return pendientes[0] === "cocina" ? "pedido.estado.esperandoCocina" : "pedido.estado.esperandoBarra";
}

function EstadoPedido({ pedido, anteriores }: { pedido: TipoPedido; anteriores: TipoPedido[] }) {
  const { t, llamarMozo } = useComensal();
  const navegar = useNavigate();
  const actual = pasoActual(pedido);
  const pasos: { titulo: Clave; texto: string }[] = [
    { titulo: "pedido.paso.registrado", texto: t("pedido.paso.registradoTexto") },
    { titulo: "pedido.paso.recibido", texto: t(estadoVisible(pedido)) },
    { titulo: "pedido.paso.preparacion", texto: actual >= 2 ? t("pedido.estado.preparando") : t("pedido.paso.pendiente") },
    { titulo: "pedido.paso.entregado", texto: actual >= 3 ? t("pedido.estado.completado") : t("pedido.paso.pendiente") },
  ];
  return (
    <div className="mt-estado">
      <div className="mt-anillo" aria-hidden="true">✓</div>
      <span className="mt-numero">{t("pedido.numero", { n: `MT-${pedido.id.slice(-4).toUpperCase()}` })} · {soles(pedido.totalCentimos)}</span>
      <h1>{t("pedido.enviadoTitulo")}</h1>
      <p className="mt-muted">{t("pedido.enviadoTexto")}</p>
      <div className="mt-comandas">
        {pedido.comandas.map((c) => (
          <span key={c.destino} className={`mt-tag ${c.estado === "pendiente" ? "consultar" : "apto"}`}>
            {t(c.destino === "cocina" ? "pedido.cocina" : "pedido.barra")}: {t(`comanda.${c.estado}` as Clave)}
          </span>
        ))}
      </div>
      <ol className="mt-linea-tiempo" style={{ listStyle: "none", padding: 0 }}>
        {pasos.map((p, i) => (
          <li key={p.titulo} className={`mt-paso ${i < actual || actual === 3 ? "hecho" : i === actual ? "actual" : ""}`} aria-current={i === actual ? "step" : undefined}>
            <span className="mt-paso-punto" aria-hidden="true" />
            <div>
              <strong>{t(p.titulo)}</strong>
              <p>{i <= actual ? p.texto : t("pedido.paso.pendiente")}</p>
            </div>
          </li>
        ))}
      </ol>
      <div className="mt-pila">
        <button className="mt-btn primario bloque" onClick={() => navegar("/carta")}>{t("pedido.otro")}</button>
        <button className="mt-btn borde bloque" onClick={() => llamarMozo("asistencia")}>{t("mozo.llamar")}</button>
      </div>
      <Encuesta />
      {anteriores.length > 0 && <Anteriores pedidos={anteriores} />}
    </div>
  );
}

function Anteriores({ pedidos }: { pedidos: TipoPedido[] }) {
  const { t } = useComensal();
  return (
    <div style={{ marginTop: 26, textAlign: "left" }}>
      <div className="mt-eyebrow">{t("pedido.anteriores")}</div>
      {pedidos.map((p) => (
        <div className="mt-anterior" key={p.id}>
          <div className="mt-linea" style={{ margin: 0 }}>
            <strong>{t(estadoVisible(p))}</strong>
            <strong>{soles(p.totalCentimos)}</strong>
          </div>
          <div className="mt-muted" style={{ fontSize: 13, marginTop: 4 }}>
            {p.items.map((i) => `${i.cantidad} × ${textoVisible(i.nombre)}`).join(" · ")}
          </div>
        </div>
      ))}
    </div>
  );
}

function Encuesta() {
  const { api, t } = useComensal();
  const [enviada, setEnviada] = useState(false);
  if (enviada) return <p className="mt-nota ok" style={{ marginTop: 22, justifyContent: "center" }}>{t("encuesta.gracias")}</p>;
  return (
    <div style={{ marginTop: 28 }}>
      <p className="mt-muted" style={{ fontSize: 14 }}>{t("encuesta.titulo")}</p>
      <div className="mt-estrellas">
        {[1, 2, 3, 4, 5].map((n) => (
          <button key={n} onClick={() => api.encuesta(n).then(() => setEnviada(true)).catch(() => undefined)} aria-label={`${n}/5`}>
            ★
          </button>
        ))}
      </div>
    </div>
  );
}
