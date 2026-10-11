import { useEffect, useState } from "react";
import { ErrorApi, soles, type ItemPedido, type Pedido as TipoPedido } from "../api";
import type { Clave } from "../i18n";
import { useComensal } from "./contexto";

/** Carrito y confirmación (RF05 · RF10 · RF11): nada sale a cocina sin la confirmación explícita. */
export function Pedido() {
  const { api, t, carrito, setCarrito, manejarError } = useComensal();
  const [confirmando, setConfirmando] = useState(false);
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
      setConfirmando(false);
    } catch (e) {
      setConfirmando(false);
      setError(manejarError(e));
      if (e instanceof ErrorApi && e.codigo === "revisar_items") setInvalidos(e.detalle);
    } finally {
      setEnviando(false);
    }
  };

  if (!carrito) return <p className="cargando">{error ?? "…"}</p>;
  const borrador = carrito.borrador;

  return (
    <section>
      <h1>{t("pedido.titulo")}</h1>
      {error && <p className="aviso peligro">{error}</p>}

      {!borrador || borrador.items.length === 0 ? (
        carrito.enviados.length === 0 && <p className="suave">{t("pedido.vacio")}</p>
      ) : (
        <div className="tarjeta">
          {borrador.items.map((item) => (
            <LineaPedido
              key={item.id}
              item={item}
              invalido={invalidos.includes(item.id)}
              cambiar={(cantidad) => actualizar(() => (cantidad === 0 ? api.quitar(item.id) : api.cambiarCantidad(item.id, cantidad)))}
            />
          ))}
          <div className="separador" />
          <div className="fila entre total">
            <span>{t("pedido.total")}</span>
            <span>{soles(borrador.totalCentimos)}</span>
          </div>
          <div className="separador" />
          <button className="boton bloque" onClick={() => setConfirmando(true)}>
            {t("pedido.confirmar")}
          </button>
        </div>
      )}

      {carrito.enviados.length > 0 && (
        <>
          <h2 style={{ marginTop: 24 }}>{t("pedido.enviados")}</h2>
          <div className="lista">
            {carrito.enviados.map((p) => (
              <PedidoEnviado key={p.id} pedido={p} />
            ))}
          </div>
          <Encuesta />
        </>
      )}

      {confirmando && borrador && (
        <div className="velo" role="dialog" aria-modal="true" aria-labelledby="titulo-confirmar">
          <div className="dialogo">
            <h2 id="titulo-confirmar">{t("pedido.confirmarTitulo")}</h2>
            <ul>
              {borrador.items.map((i) => (
                <li key={i.id}>
                  {i.cantidad} × {i.nombre}
                </li>
              ))}
            </ul>
            <p className="total">
              {t("pedido.total")}: {soles(borrador.totalCentimos)}
            </p>
            <p className="aviso alerta">{t("pedido.confirmarAviso")}</p>
            <div className="lista">
              <button className="boton bloque" onClick={confirmar} disabled={enviando}>
                {t("pedido.confirmarSi")}
              </button>
              <button className="boton secundario bloque" onClick={() => setConfirmando(false)} disabled={enviando}>
                {t("pedido.confirmarNo")}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

function LineaPedido({ item, invalido, cambiar }: { item: ItemPedido; invalido: boolean; cambiar: (cantidad: number) => void }) {
  const { t } = useComensal();
  return (
    <div className={`item-pedido ${invalido ? "invalido" : ""}`}>
      <div>
        <strong>{item.nombre}</strong>
        <div className="suave">{soles(item.precioUnitarioCentimos)}</div>
        {invalido && <div className="suave">{t("pedido.revisar")}</div>}
      </div>
      <span className="precio">{soles(item.precioUnitarioCentimos * item.cantidad)}</span>
      <div className="cantidad" aria-label={item.nombre}>
        <button onClick={() => cambiar(item.cantidad - 1)} aria-label="−">−</button>
        <span>{item.cantidad}</span>
        <button onClick={() => cambiar(item.cantidad + 1)} aria-label="+" disabled={item.cantidad >= 20}>+</button>
      </div>
      <button className="enlace" onClick={() => cambiar(0)}>{t("pedido.quitar")}</button>
    </div>
  );
}

function PedidoEnviado({ pedido }: { pedido: TipoPedido }) {
  const { t } = useComensal();
  return (
    <article className="tarjeta">
      <div className="fila entre">
        <strong>{t(`pedido.estado.${pedido.estado}` as Clave)}</strong>
        <span className="precio">{soles(pedido.totalCentimos)}</span>
      </div>
      <ul className="suave" style={{ paddingLeft: 18, margin: "8px 0" }}>
        {pedido.items.map((i) => (
          <li key={i.id}>
            {i.cantidad} × {i.nombre}
          </li>
        ))}
      </ul>
      <div className="fila" style={{ flexWrap: "wrap" }}>
        {pedido.comandas.map((c) => (
          <span key={c.destino} className="etiqueta neutra">
            {t(c.destino === "cocina" ? "pedido.cocina" : "pedido.barra")}: {t(`comanda.${c.estado}` as Clave)}
          </span>
        ))}
      </div>
    </article>
  );
}

function Encuesta() {
  const { api, t } = useComensal();
  const [enviada, setEnviada] = useState(false);
  if (enviada) return <p className="aviso ok" style={{ marginTop: 16 }}>{t("encuesta.gracias")}</p>;
  return (
    <div className="tarjeta" style={{ marginTop: 16 }}>
      <p>{t("encuesta.titulo")}</p>
      <div className="estrellas">
        {[1, 2, 3, 4, 5].map((n) => (
          <button key={n} onClick={() => api.encuesta(n).then(() => setEnviada(true)).catch(() => undefined)} aria-label={`${n}/5`}>
            {n}
          </button>
        ))}
      </div>
    </div>
  );
}
