import { textoVisible } from "./textos";

/**
 * Fotos ilustrativas para la demo, con los nombres de la carta ficticia. No son fotos de los platos
 * de Maido: el origen y la licencia de cada una están en comensal/public/demo/CREDITOS.txt.
 * Se usan solo cuando el producto no tiene foto propia.
 */
const fotosDemo: Record<string, string> = {
  "Ceviche": "/demo/ceviche.jpg",
  "Tiradito": "/demo/tiradito.jpg",
  "Ensalada de quinua": "/demo/ensalada-quinua.jpg",
  "Arroz salteado con langostinos": "/demo/arroz-langostinos.jpg",
  "Empanaditas de cerdo": "/demo/empanadas.jpg",
  "Postre de chocolate": "/demo/postre-chocolate.jpg",
  "Refresco de maracuyá": "/demo/refresco-maracuya.jpg",
  "Té verde": "/demo/te-verde.jpg",
  "Arroz chaufa": "/demo/arroz-langostinos.jpg",
  "Gyozas": "/demo/empanadas.jpg",
  "Volcán de chocolate": "/demo/postre-chocolate.jpg",
  "Chilcano de maracuyá": "/demo/refresco-maracuya.jpg",
};

/** Las claves más largas primero: «Arroz chaufa de mariscos» usa «Arroz chaufa», no otra más corta. */
const claves = Object.keys(fotosDemo).sort((a, b) => b.length - a.length);

/** Foto por nombre exacto o por el comienzo del nombre («Tiradito de pulpo» → «Tiradito»). */
export function fotoDemo(nombre: string): string | undefined {
  const visible = textoVisible(nombre);
  const clave = claves.find((c) => visible === c || visible.startsWith(`${c} `));
  return clave ? fotosDemo[clave] : undefined;
}
