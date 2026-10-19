import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Web-app del comensal. En desarrollo la API corre detrás del API Gateway (puerto 8080); en
// producción el gateway entrega dist/comensal en la raíz del sitio.
export default defineConfig({
  root: "comensal",
  base: "/",
  plugins: [react()],
  build: { outDir: "../dist/comensal", emptyOutDir: true },
  server: {
    port: 5173,
    host: true,
    fs: { allow: [".."] },
    proxy: { "/api": "http://localhost:8080", "/fotos": "http://localhost:8080" },
  },
});
