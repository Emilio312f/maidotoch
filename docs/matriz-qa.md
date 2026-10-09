# Matriz de QA de MaidoTouch

Base para el QA integral (Plan §4, fase 6) y la evidencia del Go/No-Go. Cada requisito del SRS con su
verificación automática (corre en CI en cada pull request) y la verificación manual pendiente en
staging o en el local.

Pruebas automáticas: `./gradlew test` (backend) y `cd frontend && npm test` (textos es/en).

## Requisitos funcionales

| ID | Verificación automática | Verificación manual |
|---|---|---|
| RF01 | `OperacionTest`: puesto desactivado no abre sesiones; token inexistente rechazado. `ApiTest`: activación por token | Leer cada tag NFC y cada QR con Android e iOS |
| RF02 | `OperacionTest` (carta por sesión); `ImportadorCatalogoTest`: producto sin validar no aparece | Fotos y descripciones reales cargadas (`verificar` avisa productos sin foto) |
| RF03 | `OrquestadorRecomendacionTest`, `RankingPorReglasTest` | Interpretación ≥ 90 % con consultas reales y proveedor de IA |
| RF04 | `i18n.test.ts`: mismas claves es/en; explicación en inglés (`OrquestadorRecomendacionTest`) | Revisión de textos por un hablante nativo |
| RF05 | `PedidoTest`, `OperacionTest`: una comanda por destino, recepción, entrega | Flujo aprobado por cocina y barra en el ensayo general |
| RF06 | `OperacionTest`: llamado sin duplicados, atención por sala | Botón visible en todas las pantallas del celular |
| RF07 | `ApiTest`: perfil antes de recomendar | Perfil completado en menos de 30 s por usuarios de prueba |
| RF08 | `OperacionTest`: historial con quién y motivo, caché invalidada, roles | Encargado de carta cambia disponibilidad antes del servicio |
| RF09 | `CandidatosSegurosTest`, `OrquestadorRecomendacionTest`: la IA solo recibe la lista segura | — |
| RF10 | `PedidoTest`: sin `confirmo` no se envía; `ApiTest` | Diálogo de confirmación en el celular |
| RF11 | `PedidoTest`: agregar, cambiar, quitar; bloqueado tras enviar | — |
| RF12 | `OperacionTest`: desactivar se refleja de inmediato; `PedidoTest`: ítem agotado bloquea la confirmación | — |
| RF13 | `PedidoTest`: aceptada, modificada, rechazada; `OrquestadorRecomendacionTest`: mostrada | Métricas de aceptación en el back-office |
| RF14 | `CandidatosSegurosTest` (sin dato), `OrquestadorRecomendacionTest` (derivación), `OperacionTest` (aptitud «consultar») | Sala atiende una derivación con cocina |

## Requisitos no funcionales

| ID | Verificación automática | Verificación manual |
|---|---|---|
| RNF01 | Id inventado descartado; explicaciones del dominio; mutaciones detectadas | Revisión de 50 respuestas reales con IA: 0 % de información inventada |
| RNF02 | DTO sin sesión ni datos sensibles; cierre descarta el perfil; sesiones vencidas | Revisión de la base de staging: sin datos personales |
| RNF03 | Timeout y error de IA → fallback; `ops/desplegar.sh` revierte solo | Ensayo de contingencia: puesto desactivado, servidor detenido |
| RNF04 | Timeout por defecto de 5 s | `scripts/medir_latencia.py` en 4G: asistente ≤ 3 s P95 |
| RNF05 | — | `systemd` con reinicio automático; monitoreo diario del piloto |
| RNF06 | — | Prueba en celulares pequeños y grandes (Android e iOS) |
| RNF07 | Claves con PBKDF2, tokens con hash, identificadores sin nombres | — |
| RNF08 | QR y NFC generan el mismo enlace por puesto | Tarjetas QR impresas junto a cada tag |
| RNF09 | `consultas_asistente`, `recomendaciones` y eventos inmutables | Reporte diario y métricas del piloto |

## Casos críticos de alergias (deben pasar al 100 %)

`tests/dominio/restricciones/` y `tests/dominio/recomendacion/`: alérgeno contenido, en trazas y vía
ingrediente; sin evaluación; evaluación desconocida o incompatible; dieta sin compatible; producto no
disponible, inactivo o no validado; producto modificado tras validar; recomendación con revisión vieja
rechazada por la base; id inventado por la IA; timeout de la IA; sin candidatos; DTO sin datos
sensibles; comandas solo al confirmar.

El CI falla si alguna prueba se omite.

## Criterios de Go/No-Go (Plan §5)

| Criterio | Cómo se verifica |
|---|---|
| 0 defectos críticos abiertos | Tablero de GitHub (plantilla «Defecto», severidad crítica) |
| 100 % de casos críticos de alergias | CI en verde sobre la versión candidata |
| Catálogo piloto 100 % validado | `maidotouch verificar` |
| NFC/QR en ≥ 99 % de los puestos | `maidotouch verificar` + prueba física de cada tag |
| Latencia del asistente ≤ 3 s P95 | `scripts/medir_latencia.py` en 4G |
| Fallback sin IA probado | Repetir el recorrido en staging sin `ANTHROPIC_API_KEY` |
| 100 % del personal clave capacitado | Registro de capacitación con este manual |
