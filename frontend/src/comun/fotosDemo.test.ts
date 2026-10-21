import { describe, expect, it } from "vitest";
import { fotoDemo } from "./fotosDemo";

describe("fotoDemo", () => {
  it("asigna la foto por nombre aunque el texto traiga la marca de prueba", () => {
    expect(fotoDemo("Ceviche de prueba [FICTICIO]")).toBe("/demo/ceviche.jpg");
    expect(fotoDemo("Refresco de maracuyá de prueba [FICTICIO]")).toBe("/demo/refresco-maracuya.jpg");
  });

  it("asigna la foto por el comienzo del nombre", () => {
    expect(fotoDemo("Ceviche nikkei")).toBe("/demo/ceviche.jpg");
    expect(fotoDemo("Tiradito de pulpo al olivo")).toBe("/demo/tiradito.jpg");
    expect(fotoDemo("Arroz chaufa de mariscos")).toBe("/demo/arroz-langostinos.jpg");
    expect(fotoDemo("Cevichería")).toBeUndefined();
  });

  it("devuelve undefined para un producto sin foto de demo", () => {
    expect(fotoDemo("Plato nuevo")).toBeUndefined();
  });
});
