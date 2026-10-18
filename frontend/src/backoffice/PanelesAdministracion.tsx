import { useCallback, useEffect, useState, type FormEvent } from "react";
import {
  soles,
  type apiBackoffice,
  type CuentaPersonal,
  type Fase,
  type Medicion,
  type Personal,
  type ReporteDiario,
  type ResumenFase,
  type Verificacion,
} from "../api";

interface PropsPanel {
  api: ReturnType<typeof apiBackoffice>;
  personal: Personal;
  manejarError: (e: unknown) => string;
}

// ---------- Cuentas del personal (Plan §5) ----------

const ROLES: Personal["rol"][] = ["cocina", "barra", "sala", "administrador"];

export function Cuentas({ api, personal, manejarError }: PropsPanel) {
  const [cuentas, setCuentas] = useState<CuentaPersonal[] | null>(null);
  const [identificador, setIdentificador] = useState("");
  const [rol, setRol] = useState<Personal["rol"]>("sala");
  const [clave, setClave] = useState("");
  const [mensaje, setMensaje] = useState<{ tipo: "ok" | "peligro"; texto: string } | null>(null);

  const cargar = useCallback(() => {
    api.cuentas().then(setCuentas).catch((e) => setMensaje({ tipo: "peligro", texto: manejarError(e) }));
  }, [api, manejarError]);
  useEffect(cargar, [cargar]);

  const ejecutar = async (accion: () => Promise<unknown>, exito: string) => {
    setMensaje(null);
    try {
      await accion();
      setMensaje({ tipo: "ok", texto: exito });
      cargar();
    } catch (e) {
      setMensaje({ tipo: "peligro", texto: manejarError(e) });
    }
  };

  const crear = (e: FormEvent) => {
    e.preventDefault();
    ejecutar(async () => {
      await api.crearCuenta(identificador.trim(), rol, clave);
      setIdentificador("");
      setClave("");
    }, `Cuenta ${identificador.trim()} creada.`);
  };

  const restablecer = (c: CuentaPersonal) => {
    const nueva = window.prompt(`Nueva clave para ${c.identificador} (mínimo 10 caracteres). Se cerrarán sus sesiones abiertas.`);
    if (nueva) ejecutar(() => api.restablecerClave(c.id, nueva), `Clave de ${c.identificador} actualizada.`);
  };

  return (
    <section>
      <p className="suave">
        Usa identificadores de usuario (por ejemplo <code>cocina-01</code>), no nombres de personas. Desactivar una cuenta cierra sus sesiones
        abiertas de inmediato.
      </p>
      <form className="tarjeta fila" style={{ flexWrap: "wrap", alignItems: "end", marginBottom: 16 }} onSubmit={crear}>
        <label className="campo" style={{ margin: 0 }}>
          Usuario
          <input value={identificador} onChange={(e) => setIdentificador(e.target.value)} placeholder="sala-01" required autoCapitalize="none" />
        </label>
        <label className="campo" style={{ margin: 0 }}>
          Rol
          <select value={rol} onChange={(e) => setRol(e.target.value as Personal["rol"])}>
            {ROLES.map((r) => (
              <option key={r} value={r}>{r}</option>
            ))}
          </select>
        </label>
        <label className="campo" style={{ margin: 0 }}>
          Clave inicial
          <input type="password" value={clave} onChange={(e) => setClave(e.target.value)} minLength={10} required autoComplete="new-password" />
        </label>
        <button className="boton">Crear cuenta</button>
      </form>
      {mensaje && <p className={`aviso ${mensaje.tipo}`}>{mensaje.texto}</p>}
      <div className="tabla-contenedor">
        <table className="tabla">
          <thead>
            <tr>
              <th>Usuario</th>
              <th>Rol</th>
              <th>Estado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {cuentas?.map((c) => (
              <tr key={c.id}>
                <td>{c.identificador}</td>
                <td>{c.rol}</td>
                <td>
                  {c.activo ? <span className="etiqueta apto">Activa</span> : <span className="etiqueta neutra">Inactiva</span>}
                  {!c.tieneClave && <span className="etiqueta consultar"> Sin clave</span>}
                </td>
                <td className="fila" style={{ flexWrap: "wrap" }}>
                  <button className="boton secundario peque" onClick={() => restablecer(c)}>Cambiar clave</button>
                  {c.id !== personal.id && (
                    <button
                      className="boton secundario peque"
                      onClick={() => ejecutar(() => api.activarCuenta(c.id, !c.activo), `Cuenta ${c.identificador} ${c.activo ? "desactivada" : "activada"}.`)}
                    >
                      {c.activo ? "Desactivar" : "Activar"}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

// ---------- Piloto: mediciones, reporte diario y Go/No-Go ----------

const FASES: Record<Fase, string> = { linea_base: "Línea base", piloto: "Piloto", post_lanzamiento: "Post-lanzamiento" };

const hoyEnLima = () => new Date().toLocaleDateString("en-CA", { timeZone: "America/Lima" });

export function Piloto(props: PropsPanel) {
  return (
    <section className="lista">
      <GoNoGo {...props} />
      <Reporte {...props} />
      <Mediciones {...props} />
    </section>
  );
}

function GoNoGo({ api, manejarError }: PropsPanel) {
  const [verificacion, setVerificacion] = useState<Verificacion | null>(null);
  const [error, setError] = useState<string | null>(null);
  const revisar = () => api.verificacion().then(setVerificacion).catch((e) => setError(manejarError(e)));

  return (
    <div className="tarjeta">
      <div className="fila entre">
        <h2>Criterios de Go/No-Go</h2>
        <button className="boton peque" onClick={revisar}>Revisar ahora</button>
      </div>
      {error && <p className="aviso peligro">{error}</p>}
      {verificacion && (
        <>
          <p className={`aviso ${verificacion.apto ? "ok" : "peligro"}`}>
            {verificacion.apto ? "Sin fallas en los criterios comprobables." : "NO-GO: hay criterios en falla."}
          </p>
          <div className="tabla-contenedor">
            <table className="tabla">
              <tbody>
                {verificacion.chequeos.map((c) => (
                  <tr key={c.criterio}>
                    <td>
                      <span className={`etiqueta ${c.resultado === "OK" ? "apto" : c.resultado === "FALLA" ? "no_apto" : "consultar"}`}>{c.resultado}</span>
                    </td>
                    <td>{c.criterio}</td>
                    <td className="suave">{c.detalle}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <h3 style={{ marginTop: 12 }}>Verificar a mano</h3>
          <ul>
            {verificacion.manuales.map((m) => (
              <li key={m}>{m}</li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function Reporte({ api, manejarError }: PropsPanel) {
  const [fecha, setFecha] = useState(hoyEnLima);
  const [reporte, setReporte] = useState<ReporteDiario | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.reporteDiario(fecha).then(setReporte).catch((e) => setError(manejarError(e)));
  }, [api, fecha, manejarError]);

  const motor = (r: Record<string, number>) => Object.entries(r).map(([k, v]) => `${k}: ${v}`).join(" · ") || "—";

  return (
    <div className="tarjeta">
      <div className="fila entre" style={{ flexWrap: "wrap" }}>
        <h2>Reporte diario</h2>
        <input type="date" value={fecha} onChange={(e) => setFecha(e.target.value)} />
      </div>
      <p className="suave">Resumen para el monitoreo diario del piloto. Se puede copiar y enviar al Director de Proyecto.</p>
      {error && <p className="aviso peligro">{error}</p>}
      {reporte && (
        <div className="tabla-contenedor">
          <table className="tabla">
            <tbody>
              <tr><th>Sesiones</th><td>{reporte.sesiones}</td></tr>
              <tr><th>Consultas al asistente</th><td>{motor(reporte.consultasPorMotor)}</td></tr>
              <tr>
                <th>Latencia del asistente</th>
                <td>
                  P95 {reporte.latenciaP95Ms == null ? "—" : `${reporte.latenciaP95Ms} ms`} · máx. {reporte.latenciaMaximaMs == null ? "—" : `${reporte.latenciaMaximaMs} ms`} · sobre 3 s: {reporte.consultasSobrePresupuesto}
                </td>
              </tr>
              <tr>
                <th>Recomendaciones</th>
                <td>
                  {reporte.mostradas} mostradas · {reporte.aceptadas} aceptadas · {reporte.rechazadas} rechazadas
                  {reporte.mostradas > 0 && ` · aceptación ${((100 * reporte.aceptadas) / reporte.mostradas).toFixed(1)} %`}
                </td>
              </tr>
              <tr>
                <th>Pedidos</th>
                <td>
                  {reporte.pedidosConfirmados} · ticket promedio {reporte.ticketPromedioCentimos == null ? "—" : soles(reporte.ticketPromedioCentimos)}
                </td>
              </tr>
              <tr>
                <th>Comandas</th>
                <td>
                  {reporte.comandas} · sin recibir {reporte.comandasSinRecibir} · recepción P95 {reporte.recepcionP95Segundos == null ? "—" : `${reporte.recepcionP95Segundos} s`}
                </td>
              </tr>
              <tr>
                <th>Llamados al mozo</th>
                <td>
                  {motor(reporte.llamadosPorMotivo)} · atención promedio {reporte.atencionPromedioSegundos == null ? "—" : `${reporte.atencionPromedioSegundos} s`}
                </td>
              </tr>
              <tr><th>Cambios de disponibilidad</th><td>{reporte.cambiosDisponibilidad}</td></tr>
              <tr>
                <th>Encuestas</th>
                <td>
                  {reporte.encuestas} · satisfacción {reporte.satisfaccionPromedio?.toFixed(2) ?? "—"}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Mediciones({ api, manejarError }: PropsPanel) {
  const [datos, setDatos] = useState<{ mediciones: Medicion[]; resumen: ResumenFase[] } | null>(null);
  const [mensaje, setMensaje] = useState<{ tipo: "ok" | "peligro"; texto: string } | null>(null);
  const [form, setForm] = useState({ fase: "linea_base" as Fase, fecha: hoyEnLima(), comensales: "", importe: "", tiempoMin: "", consultas: "", fuente: "" });

  useEffect(() => {
    api.mediciones().then(setDatos).catch((e) => setMensaje({ tipo: "peligro", texto: manejarError(e) }));
  }, [api, manejarError]);

  const registrar = async (e: FormEvent) => {
    e.preventDefault();
    setMensaje(null);
    try {
      setDatos(
        await api.registrarMedicion({
          fase: form.fase,
          fecha: form.fecha,
          comensales: Number(form.comensales),
          importeCentimos: Math.round(Number(form.importe) * 100),
          tiempoPedidoSegundos: Math.round(Number(form.tiempoMin) * 60),
          consultasMozo: Number(form.consultas),
          fuente: form.fuente,
        }),
      );
      setForm({ ...form, comensales: "", importe: "", tiempoMin: "", consultas: "" });
      setMensaje({ tipo: "ok", texto: "Medición registrada." });
    } catch (err) {
      setMensaje({ tipo: "peligro", texto: manejarError(err) });
    }
  };

  const campo = (clave: keyof typeof form, etiqueta: string, props: Record<string, unknown> = {}) => (
    <label className="campo" style={{ margin: 0 }}>
      {etiqueta}
      <input value={form[clave]} onChange={(e) => setForm({ ...form, [clave]: e.target.value })} required {...props} />
    </label>
  );

  return (
    <div className="tarjeta">
      <h2>Mediciones del piloto</h2>
      <p className="suave">
        Registra cada servicio medido: la línea base antes del lanzamiento y luego el piloto, para comparar ticket, tiempo de pedido y consultas al
        mozo por comensal.
      </p>
      {datos && datos.resumen.length > 0 && (
        <div className="tabla-contenedor" style={{ marginBottom: 12 }}>
          <table className="tabla">
            <thead>
              <tr>
                <th>Fase</th>
                <th>Comensales</th>
                <th>Ticket por comensal</th>
                <th>Tiempo de pedido</th>
                <th>Consultas al mozo por comensal</th>
              </tr>
            </thead>
            <tbody>
              {datos.resumen.map((r) => (
                <tr key={r.fase}>
                  <td>{FASES[r.fase]}</td>
                  <td>{r.comensales}</td>
                  <td>{soles(r.ticketPorComensalCentimos)}</td>
                  <td>{(r.tiempoPedidoPromedioSegundos / 60).toFixed(1)} min</td>
                  <td>{r.consultasMozoPorComensal.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <form className="fila" style={{ flexWrap: "wrap", alignItems: "end" }} onSubmit={registrar}>
        <label className="campo" style={{ margin: 0 }}>
          Fase
          <select value={form.fase} onChange={(e) => setForm({ ...form, fase: e.target.value as Fase })}>
            {Object.entries(FASES).map(([k, v]) => (
              <option key={k} value={k}>{v}</option>
            ))}
          </select>
        </label>
        {campo("fecha", "Fecha", { type: "date" })}
        {campo("comensales", "Comensales", { type: "number", min: 1 })}
        {campo("importe", "Importe total (S/)", { type: "number", min: 0, step: "0.01" })}
        {campo("tiempoMin", "Tiempo medio de pedido (min)", { type: "number", min: 0, step: "0.1" })}
        {campo("consultas", "Consultas al mozo", { type: "number", min: 0 })}
        {campo("fuente", "Fuente del dato", { placeholder: "Observación en sala" })}
        <button className="boton">Registrar</button>
      </form>
      {mensaje && <p className={`aviso ${mensaje.tipo}`} style={{ marginTop: 12 }}>{mensaje.texto}</p>}
      {datos && datos.mediciones.length > 0 && (
        <div className="tabla-contenedor" style={{ marginTop: 12 }}>
          <table className="tabla">
            <thead>
              <tr>
                <th>Fecha</th>
                <th>Fase</th>
                <th>Comensales</th>
                <th>Importe</th>
                <th>Tiempo</th>
                <th>Consultas</th>
                <th>Fuente</th>
                <th>Registró</th>
              </tr>
            </thead>
            <tbody>
              {datos.mediciones.map((m) => (
                <tr key={m.id}>
                  <td>{m.fecha}</td>
                  <td>{FASES[m.fase]}</td>
                  <td>{m.comensales}</td>
                  <td>{soles(m.importeCentimos)}</td>
                  <td>{(m.tiempoPedidoSegundos / 60).toFixed(1)} min</td>
                  <td>{m.consultasMozo}</td>
                  <td>{m.fuente}</td>
                  <td>{m.registradoPor}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
