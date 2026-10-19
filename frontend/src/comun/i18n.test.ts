import { describe, expect, it } from "vitest";
import { textoError, textos, traducir } from "./i18n";

describe("textos del comensal", () => {
  it("existen en español e inglés con las mismas claves", () => {
    expect(Object.keys(textos.en).sort()).toEqual(Object.keys(textos.es).sort());
  });

  it("ningún texto está vacío", () => {
    for (const idioma of ["es", "en"] as const) {
      for (const [clave, texto] of Object.entries(textos[idioma])) {
        expect(texto.trim(), `${idioma}:${clave}`).not.toBe("");
      }
    }
  });

  it("los marcadores coinciden entre idiomas", () => {
    const marcadores = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort();
    for (const clave of Object.keys(textos.es) as (keyof typeof textos.es)[]) {
      expect(marcadores(textos.en[clave]), clave).toEqual(marcadores(textos.es[clave]));
    }
  });

  it("reemplaza valores y traduce errores conocidos y desconocidos", () => {
    expect(traducir("en", "asistente.sinConfirmar", { n: 2 })).toContain("2 available dishes");
    expect(textoError("es", "puesto_inactivo")).toContain("no está habilitado");
    expect(textoError("en", "codigo_inexistente")).toBe(textos.en["error.generico"]);
  });
});
