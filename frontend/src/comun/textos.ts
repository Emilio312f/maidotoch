/**
 * Texto que ve el comensal: quita las marcas de los datos de prueba del catálogo (nombres,
 * categorías y descripciones). Solo cambia lo que se muestra; la base de datos no se modifica.
 */
export function textoVisible(texto: string): string {
  return texto
    .replace(/\[(FICTICIO|FICTITIOUS)\]/gi, "")
    .replace(/[^.]*\bficticio\b[^.]*\.?/gi, "")
    .replace(/\s+de prueba\b/gi, "")
    .replace(/\s+/g, " ")
    .trim();
}
