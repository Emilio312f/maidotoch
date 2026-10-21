import { existsSync } from "node:fs";
import type { Plugin } from "vite";

// Los index.html piden módulos de src/ con ruta absoluta («/src/...»). Vite resuelve esas rutas desde la
// carpeta raíz de cada app y devolvería el index.html; esto las lleva a frontend/. Una ruta real del
// disco que empieza por /src/ (el proyecto vive en /src dentro de la imagen Docker) se deja como está.
export function entradasDesdeSrc(): Plugin {
  return {
    name: "entradas-desde-src",
    enforce: "pre",
    resolveId(id) {
      if (id.startsWith("/src/") && !existsSync(id)) return new URL(`.${id}`, import.meta.url).pathname;
      return null;
    },
  };
}
