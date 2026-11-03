import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { soles, type Aptitud, type CartaSesion, type ProductoCarta } from "../comun/api";
import { textoVisible } from "../comun/textos";
import { useComensal } from "./contexto";
import { BarraSuperior, Cargando, Foto, Icono, fotoDe } from "./piezas";

type Item = { producto: ProductoCarta; aptitud?: Aptitud };

function saludo(): "carta.saludo.manana" | "carta.saludo.tarde" | "carta.saludo.noche" {
  const hora = new Date().getHours();
  return hora < 12 ? "carta.saludo.manana" : hora < 19 ? "carta.saludo.tarde" : "carta.saludo.noche";
}

/** Carta digital (RF02 · RF12): disponibilidad actual y aptitud para el perfil, calculada en el servidor. */
export function Carta() {
  const { api, idioma, t, manejarError } = useComensal();
  const [carta, setCarta] = useState<CartaSesion | null>(null);
  const [categoria, setCategoria] = useState<number | null>(null);
  const [busqueda, setBusqueda] = useState("");
  // Con alergias o dietas declaradas, los platos no aptos se ocultan; el comensal puede ver la carta completa.
  const [mostrarTodos, setMostrarTodos] = useState(false);
  const [abierto, setAbierto] = useState<Item | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.carta().then(setCarta).catch((e) => setError(manejarError(e)));
  }, [api, idioma, manejarError]);

  const termino = busqueda.trim().toLowerCase();
  const deCategoria = useMemo(
    () =>
      (carta?.productos ?? []).filter(
        ({ producto: p }) =>
          (categoria === null || p.categoriaId === categoria) &&
          (!termino || `${textoVisible(p.nombre)} ${textoVisible(p.descripcion)} ${p.etiquetas.join(" ")}`.toLowerCase().includes(termino)),
      ),
    [carta, categoria, termino],
  );

  if (!carta) {
    return (
      <section>
        <BarraSuperior />
        {error ? <p className="mt-nota peligro" style={{ margin: 20 }}>{error}</p> : <Cargando />}
      </section>
    );
  }

  const noAptos = deCategoria.filter((p) => p.aptitud === "no_apto").length;
  const visibles = mostrarTodos ? deCategoria : deCategoria.filter((p) => p.aptitud !== "no_apto");

  return (
    <section>
      <BarraSuperior />
      <div className="mt-vista">
        <div className="mt-saludo">
          <div className="mt-eyebrow">{t("carta.eyebrow")}</div>
          <h1>{t(saludo())}</h1>
          <p className="mt-muted" style={{ margin: 0 }}>
            {t("carta.pista")} <Link className="mt-enlace" to="/perfil">{t("perfil.editar")}</Link>
          </p>
        </div>

        <label className="mt-buscar">
          <Icono.lupa />
          <input type="search" value={busqueda} placeholder={t("carta.buscar")} aria-label={t("carta.buscar")} onChange={(e) => setBusqueda(e.target.value)} />
        </label>

        <div className="mt-filtros" role="tablist">
          <button className="mt-filtro" aria-pressed={categoria === null} onClick={() => setCategoria(null)}>
            {t("carta.todas")}
          </button>
          {carta.categorias.map((c) => (
            <button key={c.id} className="mt-filtro" aria-pressed={categoria === c.id} onClick={() => setCategoria(c.id)}>
              {textoVisible(c.nombre)}
            </button>
          ))}
        </div>

        {error && <p className="mt-nota peligro" style={{ margin: "0 20px 12px" }}>{error}</p>}
        {noAptos > 0 && (
          <div className="mt-nota info" style={{ margin: "0 20px 12px" }}>
            <span className="mt-nota-icono" aria-hidden="true">◐</span>
            <span>{mostrarTodos ? t("carta.mostrandoTodos") : t("carta.ocultos", { n: noAptos })}</span>
            <button className="mt-enlace" onClick={() => setMostrarTodos(!mostrarTodos)}>
              {mostrarTodos ? t("carta.ocultarNoAptos") : t("carta.verTodos")}
            </button>
          </div>
        )}

        <div className="mt-seccion">
          <h2>{t("carta.descubrir")}</h2>
          <span className="mt-muted" style={{ fontSize: 13.5 }}>{t("carta.opciones", { n: visibles.length })}</span>
        </div>

        {visibles.length === 0 ? (
          <div className="mt-vacio">
            <div className="mt-simbolo"><Icono.lupa /></div>
            <h2>{termino ? t("carta.sinResultados") : t("carta.sinAptos")}</h2>
            {termino && <p className="mt-muted">{t("carta.sinResultadosTexto")}</p>}
          </div>
        ) : (
          <div className="mt-lista">
            {visibles.map((item) => (
              <TarjetaProducto key={item.producto.id} item={item} abrir={() => setAbierto(item)} />
            ))}
          </div>
        )}
      </div>
      {abierto && <Ficha item={abierto} cerrar={() => setAbierto(null)} />}
    </section>
  );
}

/** Agrega al carrito con aviso; lo comparten la tarjeta y la ficha. */
function useAgregar(producto: ProductoCarta) {
  const { api, t, setCarrito, manejarError, avisar } = useComensal();
  const [estado, setEstado] = useState<"listo" | "agregando" | "agregado">("listo");
  const agregar = async () => {
    setEstado("agregando");
    try {
      setCarrito(await api.agregar(producto.id));
      avisar(t("toast.agregado"));
      setEstado("agregado");
      setTimeout(() => setEstado("listo"), 1500);
    } catch (e) {
      avisar(manejarError(e));
      setEstado("listo");
    }
  };
  return { estado, agregar };
}

function EtiquetaAptitud({ producto, aptitud }: Item) {
  const { t } = useComensal();
  if (!producto.disponible) return null;
  if (aptitud === "apto") return <span className="mt-tag apto">✓ {t("carta.apto")}</span>;
  if (aptitud === "consultar") return <span className="mt-tag consultar">{t("carta.consultar")}</span>;
  if (aptitud === "no_apto") return <span className="mt-tag no-apto">{t("carta.noApto")}</span>;
  return null;
}

function TarjetaProducto({ item, abrir }: { item: Item; abrir: () => void }) {
  const { producto, aptitud } = item;
  const { t } = useComensal();
  const { estado, agregar } = useAgregar(producto);
  const pedible = producto.disponible && aptitud === "apto";
  const nombre = textoVisible(producto.nombre);

  return (
    <article className={`mt-card ${producto.disponible ? "" : "mt-agotado"}`} onClick={abrir}>
      <div className="mt-card-foto">
        <Foto src={fotoDe(producto)} nombre={producto.nombre} />
        {!producto.disponible && <span className="mt-cinta">{t("carta.agotado")}</span>}
      </div>
      <div className="mt-card-cuerpo">
        <div className="mt-card-top">
          <h3>
            <button className="mt-card-titulo" onClick={(e) => { e.stopPropagation(); abrir(); }}>{nombre}</button>
          </h3>
          <span className="mt-precio">{soles(producto.precioCentimos)}</span>
        </div>
        <p className="mt-desc">{textoVisible(producto.descripcion)}</p>
        <div className="mt-tags">
          <EtiquetaAptitud producto={producto} aptitud={aptitud} />
          {producto.etiquetas.slice(0, 1).map((e) => <span key={e} className="mt-tag">{e}</span>)}
        </div>
      </div>
      {pedible ? (
        <button className={`mt-mas ${estado === "agregado" ? "hecho" : ""}`} onClick={(e) => { e.stopPropagation(); agregar(); }} disabled={estado === "agregando"} aria-label={t("carta.agregarA", { nombre })}>
          {estado === "agregado" ? "✓" : "+"}
        </button>
      ) : (
        producto.disponible && (
          <button className="mt-mas aviso" onClick={(e) => { e.stopPropagation(); abrir(); }} aria-label={`${nombre} · ${t("carta.verMas")}`}>!</button>
        )
      )}
    </article>
  );
}

/** Ficha del plato en una hoja inferior: foto grande, alérgenos, historia y la acción que corresponda. */
function Ficha({ item, cerrar }: { item: Item; cerrar: () => void }) {
  const { producto, aptitud } = item;
  const { t, llamarMozo } = useComensal();
  const { estado, agregar } = useAgregar(producto);
  const pedible = producto.disponible && aptitud === "apto";
  const foto = fotoDe(producto);

  useEffect(() => {
    const tecla = (e: KeyboardEvent) => e.key === "Escape" && cerrar();
    window.addEventListener("keydown", tecla);
    return () => window.removeEventListener("keydown", tecla);
  }, [cerrar]);

  return (
    <div className="mt-velo" onClick={(e) => e.target === e.currentTarget && cerrar()}>
      <article className="mt-hoja" role="dialog" aria-modal="true" aria-labelledby="ficha-titulo">
        <span className="mt-hoja-asa" aria-hidden="true" />
        <button className="mt-icono mt-cerrar" onClick={cerrar} aria-label={t("comun.cerrar")}>×</button>
        {foto ? <img className="mt-hoja-foto" src={foto} alt="" /> : <div className="mt-hoja-foto" />}
        <div className="mt-hoja-cuerpo">
          <div className="mt-hoja-cab">
            <h2 id="ficha-titulo">{textoVisible(producto.nombre)}</h2>
            <span className="mt-precio">{soles(producto.precioCentimos)}</span>
          </div>
          <p className="mt-muted">{textoVisible(producto.descripcion)}</p>
          <div className="mt-tags" style={{ padding: 0 }}>
            <EtiquetaAptitud producto={producto} aptitud={aptitud} />
            {producto.etiquetas.map((e) => <span key={e} className="mt-tag">{e}</span>)}
          </div>

          {(producto.alergenos.length > 0 || producto.trazas.length > 0 || producto.historia) && (
            <dl className="mt-datos">
              {producto.alergenos.length > 0 && (
                <div><dt>{t("carta.contiene")}</dt><dd>{producto.alergenos.join(", ")}</dd></div>
              )}
              {producto.trazas.length > 0 && (
                <div><dt>{t("carta.trazas")}</dt><dd>{producto.trazas.join(", ")}</dd></div>
              )}
              {producto.historia && (
                <div><dt>{t("carta.historia")}</dt><dd>{producto.historia}</dd></div>
              )}
            </dl>
          )}

          {!producto.disponible ? null : aptitud === "no_apto" ? (
            <div className="mt-nota peligro"><span className="mt-nota-icono" aria-hidden="true">⚠</span><span>{t("carta.noAptoDetalle")}</span></div>
          ) : aptitud === "consultar" ? (
            <div className="mt-nota alerta"><span className="mt-nota-icono" aria-hidden="true">ⓘ</span><span>{t("carta.consultarDetalle")}</span></div>
          ) : producto.alergenos.length > 0 ? (
            <div className="mt-nota info"><span className="mt-nota-icono" aria-hidden="true">ⓘ</span><span>{t("carta.alergenosNota")}</span></div>
          ) : null}

          {pedible ? (
            <button className="mt-btn primario bloque" disabled={estado === "agregando"} onClick={async () => { await agregar(); cerrar(); }}>
              {t("carta.anadirPedido")} · {soles(producto.precioCentimos)}
            </button>
          ) : (
            <button
              className="mt-btn borde bloque"
              onClick={() => { llamarMozo(aptitud === "consultar" ? "restriccion_incierta" : "asistencia", aptitud === "consultar" ? producto.id : undefined); cerrar(); }}
            >
              {!producto.disponible ? t("carta.consultarDisponibilidad") : t("asistente.consultarMozo")}
            </button>
          )}
        </div>
      </article>
    </div>
  );
}
