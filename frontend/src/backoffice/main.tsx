import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "../comun/estilos.css";
import { Backoffice } from "./Backoffice";

// Web-app del back-office (/admin): disponibilidad, validación, comandas, llamados y piloto.
createRoot(document.getElementById("raiz")!).render(
  <StrictMode>
    <Backoffice />
  </StrictMode>,
);
