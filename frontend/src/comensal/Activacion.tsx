import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ErrorApi, iniciarSesion } from "../comun/api";
import { textoError, traducir } from "../comun/i18n";
import { guardarSesion, idiomaDelNavegador, leerSesion } from "./contexto";

/** Entrada desde el tag NFC o el QR del puesto (RF01 · RNF08): ambos abren /m/<token>. */
export function Activacion() {
  const { token = "" } = useParams();
  const navegar = useNavigate();
  const idioma = idiomaDelNavegador();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let vigente = true;
    // Si este celular ya tiene una sesión abierta en el mismo puesto, el servidor la retoma.
    iniciarSesion(token, idioma, leerSesion())
      .then(({ sesionId }) => {
        if (!vigente) return;
        guardarSesion(sesionId);
        // Se reemplaza la URL para que el token no quede en el historial del navegador.
        navegar("/carta", { replace: true });
      })
      .catch((e) => vigente && setError(textoError(idioma, e instanceof ErrorApi ? e.codigo : "generico")));
    return () => {
      vigente = false;
    };
  }, [token, idioma, navegar]);

  return (
    <div className="centro">
      <h1>{traducir(idioma, "app.nombre")}</h1>
      {error ? (
        <>
          <p className="aviso alerta">{error}</p>
          <p className="suave">{traducir(idioma, "app.mozoSiempre")}</p>
        </>
      ) : (
        <p className="cargando">{traducir(idioma, "activacion.cargando")}</p>
      )}
    </div>
  );
}
