import { StrictMode, Suspense, lazy } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import "./estilos.css";
import { Activacion } from "./comensal/Activacion";
import { Comensal } from "./comensal/Comensal";

// El back-office se descarga aparte: el celular del comensal no lo necesita (activación ≤ 2.5 s en 4G).
const Backoffice = lazy(() => import("./backoffice/Backoffice").then((m) => ({ default: m.Backoffice })));

createRoot(document.getElementById("raiz")!).render(
  <StrictMode>
    <BrowserRouter>
      <Routes>
        {/* El tag NFC y el QR de cada puesto abren /m/<token>. */}
        <Route path="/m/:token" element={<Activacion />} />
        <Route
          path="/admin/*"
          element={
            <Suspense fallback={<p className="cargando">Cargando…</p>}>
              <Backoffice />
            </Suspense>
          }
        />
        <Route path="/*" element={<Comensal />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  </StrictMode>,
);
