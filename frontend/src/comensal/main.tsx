import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import "../comun/estilos.css";
import { Activacion } from "./Activacion";
import { Comensal } from "./Comensal";

// Web-app del comensal: se despliega aparte del back-office y no lo incluye (activación ≤ 2.5 s en 4G).
createRoot(document.getElementById("raiz")!).render(
  <StrictMode>
    <BrowserRouter>
      <Routes>
        {/* El tag NFC y el QR de cada puesto abren /m/<token>. */}
        <Route path="/m/:token" element={<Activacion />} />
        <Route path="/*" element={<Comensal />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  </StrictMode>,
);
