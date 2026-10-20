import { useEffect, useState } from "react";
import { soles, type Aptitud, type CartaSesion, type ProductoCarta } from "../comun/api";
import { fotoDemo } from "../comun/fotosDemo";
import { textoVisible } from "../comun/textos";
import { useComensal } from "./contexto";

/** Carta digital (RF02 · RF12): disponibilidad actual y aptitud para el perfil, calculada en el servidor. */
export function Carta() {
  const { api, idioma, t, manejarError } = useComensal();
  const [carta, setCarta] = useState<CartaSesion | null>(null);
  const [categoria, setCategoria] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.carta().then(setCarta).catch((e) => setError(manejarError(e)));
  }, [api, idioma, manejarError]);

  if (!carta) return <p className="cargando">{error ?? "…"}</p>;

  const visibles = carta.productos.filter((p) => categoria === null || p.producto.categoriaId === categoria);

  return (
    <section>
      <div className="pestanas" role="tablist">
        <button className="chip" aria-pressed={categoria === null} onClick={() => setCategoria(null)}>
          {t("carta.todas")}
        </button>
        {carta.categorias.map((c) => (
          <button key={c.id} className="chip" aria-pressed={categoria === c.id} onClick={() => setCategoria(c.id)}>
            {textoVisible(c.nombre)}
          </button>
        ))}
      </div>
      {error && <p className="aviso peligro">{error}</p>}
      <div className="lista">
        {visibles.map(({ producto, aptitud }) => (
          <TarjetaProducto key={producto.id} producto={producto} aptitud={aptitud} />
        ))}
      </div>
    </section>
  );
}

function TarjetaProducto({ producto, aptitud }: { producto: ProductoCarta; aptitud?: Aptitud }) {
  const { api, t, setCarrito, llamarMozo, manejarError } = useComensal();
  const [abierto, setAbierto] = useState(false);
  const [estado, setEstado] = useState<"listo" | "agregando" | "agregado">("listo");
  const [error, setError] = useState<string | null>(null);

  const agregar = async () => {
    setEstado("agregando");
    setError(null);
    try {
      setCarrito(await api.agregar(producto.id));
      setEstado("agregado");
      setTimeout(() => setEstado("listo"), 1500);
    } catch (e) {
      setError(manejarError(e));
      setEstado("listo");
    }
  };

  const pedible = producto.disponible && aptitud === "apto";
  const foto = producto.fotoUrl ?? fotoDemo(producto.nombre);

  return (
    <article className={`tarjeta producto ${producto.disponible ? "" : "agotado"}`}>
      {foto ? (
        <img className="foto" src={foto} alt="" loading="lazy" width={88} height={88} />
      ) : (
        <div className="foto" aria-hidden="true">{textoVisible(producto.nombre).charAt(0)}</div>
      )}
      <div>
        <div className="fila entre">
          <h3>{textoVisible(producto.nombre)}</h3>
          <span className="precio">{soles(producto.precioCentimos)}</span>
        </div>
        <p className="descripcion">{textoVisible(producto.descripcion)}</p>
        <div className="acciones">
          <div className="fila" style={{ flexWrap: "wrap", gap: 6 }}>
            {!producto.disponible && <span className="etiqueta neutra">{t("carta.agotado")}</span>}
            {producto.disponible && aptitud === "apto" && <span className="etiqueta apto">{t("carta.apto")}</span>}
            {producto.disponible && aptitud === "no_apto" && <span className="etiqueta no_apto">{t("carta.noApto")}</span>}
            {producto.disponible && aptitud === "consultar" && <span className="etiqueta consultar">{t("carta.consultar")}</span>}
          </div>
          {pedible && (
            <button className="boton peque" onClick={agregar} disabled={estado === "agregando"}>
              {estado === "agregado" ? `✓ ${t("carta.agregado")}` : t("carta.agregar")}
            </button>
          )}
          {producto.disponible && aptitud === "consultar" && (
            <button className="boton secundario peque" onClick={() => llamarMozo("restriccion_incierta", producto.id)}>
              {t("asistente.consultarMozo")}
            </button>
          )}
        </div>
        {aptitud === "consultar" && producto.disponible && <p className="suave detalle">{t("carta.consultarDetalle")}</p>}
        {error && <p className="aviso peligro detalle">{error}</p>}
        <button className="enlace" onClick={() => setAbierto(!abierto)} aria-expanded={abierto}>
          {abierto ? t("carta.verMenos") : t("carta.verMas")}
        </button>
        {abierto && (
          <div className="detalle">
            {producto.etiquetas.length > 0 && <p className="suave">{producto.etiquetas.join(" · ")}</p>}
            {producto.alergenos.length > 0 && (
              <p>
                <strong>{t("carta.contiene")}:</strong> {producto.alergenos.join(", ")}
              </p>
            )}
            {producto.trazas.length > 0 && (
              <p>
                <strong>{t("carta.trazas")}:</strong> {producto.trazas.join(", ")}
              </p>
            )}
            {producto.historia && (
              <p>
                <strong>{t("carta.historia")}:</strong> {producto.historia}
              </p>
            )}
          </div>
        )}
      </div>
    </article>
  );
}
