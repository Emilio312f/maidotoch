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
};

export function fotoDemo(nombre: string): string | undefined {
  return fotosDemo[textoVisible(nombre)];
}
