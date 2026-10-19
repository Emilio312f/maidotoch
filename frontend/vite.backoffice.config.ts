import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Web-app del back-office, publicada en /admin por el API Gateway (dist/backoffice).
export default defineConfig({
  root: "backoffice",
  base: "/admin/",
  plugins: [react()],
  build: { outDir: "../dist/backoffice", emptyOutDir: true },
  server: {
    port: 5174,
    host: true,
    fs: { allow: [".."] },
    proxy: { "/api": "http://localhost:8080", "/fotos": "http://localhost:8080" },
  },
});
