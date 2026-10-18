import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// En desarrollo la API corre en el backend Kotlin (puerto 8080); en producción el mismo
// servidor entrega la API y esta web-app compilada (frontend/dist).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
    proxy: { "/api": "http://localhost:8080", "/fotos": "http://localhost:8080" },
  },
});
