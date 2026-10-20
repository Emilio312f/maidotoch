import { describe, expect, it } from "vitest";
import { fotoDemo } from "./fotosDemo";

describe("fotoDemo", () => {
  it("asigna la foto por nombre aunque el texto traiga la marca de prueba", () => {
    expect(fotoDemo("Ceviche de prueba [FICTICIO]")).toBe("/demo/ceviche.jpg");
    expect(fotoDemo("Refresco de maracuyá de prueba [FICTICIO]")).toBe("/demo/refresco-maracuya.jpg");
  });

  it("devuelve undefined para un producto sin foto de demo", () => {
    expect(fotoDemo("Plato nuevo")).toBeUndefined();
  });
});
