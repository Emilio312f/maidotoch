import { describe, expect, it } from "vitest";
import { textoVisible } from "./textos";

describe("textoVisible", () => {
  it("quita la marca de los nombres y categorías", () => {
    expect(textoVisible("Entradas [FICTICIO]")).toBe("Entradas");
    expect(textoVisible("Starters [FICTITIOUS]")).toBe("Starters");
    expect(textoVisible("Ceviche de prueba [FICTICIO]")).toBe("Ceviche");
  });

  it("quita las frases de prueba de las descripciones", () => {
    expect(textoVisible("Pescado blanco marinado en limón. Plato ficticio de prueba.")).toBe("Pescado blanco marinado en limón.");
  });

  it("deja intacto el texto sin marca", () => {
    expect(textoVisible("Refresco de maracuyá")).toBe("Refresco de maracuyá");
  });
});
