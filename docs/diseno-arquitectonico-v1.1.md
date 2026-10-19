# Diseño Arquitectónico del Software — MaidoTouch · v1.1

| | |
|---|---|
| Proyecto | MaidoTouch: Sistema de Autopedido NFC con Asistente Gastronómico Inteligente |
| Versión | 1.1 (borrador de revisión). Reemplaza la sección 4 y las que dependen de ella de la v1.0 del 21/09/2026 |
| Estado | **Pendiente de aprobación del sponsor**: cambia el estilo aprobado y el presupuesto de infraestructura |
| Fecha | 19 de octubre de 2026 |

La v1.0 adoptó un monolito modular. Esta versión adopta **servicios independientes**: backend y
frontend se dividen en servicios con responsabilidad y despliegue propios. Las reglas de seguridad
alimentaria no cambian: la cadena disponibilidad → restricciones → ranking → explicación sigue
siendo obligatoria y el proveedor de IA sigue sin ver alérgenos.

La sección «Notas de implementación» al final registra cómo quedó construido y en qué se aparta
del borrador, con el motivo de cada diferencia.

## 1. Objetivos del diseño

- Aislar la validación de restricciones alimentarias en un servicio propio, determinista y
  auditable, que se ejecute siempre antes de cualquier llamada al proveedor de IA (RF14 · RNF01).
- Desacoplar el proveedor externo de IA detrás de un servicio adaptador con timeout y respaldo
  (RNF03 · RPR05).
- Recomendar solo productos disponibles, en catálogo y declarados seguros (RF09 · RF12).
- Probar, desplegar y escalar cada servicio por separado.
- Cerrar el presupuesto de latencia del asistente (RNF04): ≤ 3 s en P95 con 4G, incluidos los saltos.
- Registrar la trazabilidad de cada recomendación y su aceptación o rechazo (RF13 · RNF09).

## 2. Estilo arquitectónico

Web-app responsive (React) sin instalación → **API Gateway** (REST sobre HTTPS) → servicios. Cada
servicio tiene su código y su despliegue. Comunicación por HTTP interno cuando se necesita
respuesta, y por **bus de eventos** cuando no (comandas y llamados al mozo).

Reglas de dependencia:

- Ningún servicio lee ni escribe los datos de otro salvo por su API o por eventos (ver excepciones
  en Notas de implementación).
- El Servicio de Recomendación invoca al Servicio de Seguridad Alimentaria antes que al Adaptador de IA.
- Solo el Servicio Adaptador de IA llama al proveedor externo.

| Estilo | Decisión |
|---|---|
| Monolito modular (v1.0) | Reemplazado en v1.1. Sigue siendo la alternativa de bajo costo (§8). |
| Servicios independientes (v1.1) | **Adoptado**, sujeto a aprobación del sponsor y a la revisión de costo. |
| Aplicación móvil nativa | Descartado: obliga a instalar una app antes de pedir (RF01). |
| Totalmente serverless | Descartado: el arranque en frío afecta RNF04. |

## 3. Servicios

| Capa | Servicio | Responsabilidad | Puerto local |
|---|---|---|---|
| Cliente | Web-app comensal (`/`, `/m/<token>`) | Activación NFC/QR, carta, perfil, asistente, carrito, llamado al mozo | — |
| Cliente | Web-app back-office (`/admin`) | Disponibilidad, validación, comandas, llamados, piloto | — |
| Borde | API Gateway | Entrada única, enrutamiento, límite por IP y por sesión, autenticación del personal | 8080 |
| Aplicación | Sesión | Sesión por puesto e idioma; perfil solo mientras dura la sesión (RNF02) | 8101 |
| Aplicación | Catálogo | Carta con disponibilidad; caché invalidada al cambiar disponibilidad (RF02, RF08, RF12) | 8102 |
| Dominio | Seguridad Alimentaria | Motor de Restricciones: Seguro / Inseguro / Sin dato. Sin IA (RF14, RPR03) | 8103 |
| Dominio | Recomendación | Orquestador de la cadena y fallback (RF03, RF09, RNF03) | 8104 |
| Integración | Adaptador de IA | DTO mínimo, timeout 5 s, circuit breaker, validación de ids (RNF01, RPR01, RPR05) | 8105 |
| Aplicación | Pedido | Carrito, confirmación explícita y comandas por destino (RF05, RF10, RF11) | 8106 |
| Aplicación | Atención | Llamados al mozo y derivaciones por restricción no validable (RF06, RF14) | 8107 |
| Aplicación | Trazabilidad | Mostrada, aceptada, rechazada, modificada; sin datos personales (RF13, RNF09) | 8108 |
| Aplicación | Piloto | Línea base, reporte diario, encuestas y criterios de Go/No-Go | 8109 |
| Infraestructura | Bus de eventos | Comandas, llamados y derivaciones para cocina, barra y sala | 8110 |

## 4. Flujo de una recomendación

1. El comensal activa la app; el Gateway enruta la sesión al Servicio de Sesión (RF01).
2. El perfil se guarda solo para la sesión (RF07, RNF02).
3. La consulta llega por el Gateway al Servicio de Recomendación.
4. Recomendación pide a Catálogo los productos activos y disponibles.
5. Recomendación envía esos candidatos a Seguridad Alimentaria, que aplica las reglas fijas y
   devuelve la lista segura y los productos sin dato.
6. Si la consulta menciona platos sin dato, se ofrece la derivación al mozo (RF14) y no se recomiendan.
7. Recomendación llama al Adaptador de IA solo con la lista segura, con timeout de 5 s.
8. Si el Adaptador no responde a tiempo, falla o el circuito está abierto, se ordena la misma
   lista con reglas (fallback, RNF03, RPR05).
9. La explicación se genera con datos reales del catálogo; la recomendación se guarda y se traza (RF13).
10. Aceptar, modificar o rechazar llega a Trazabilidad.
11. Al confirmar, Pedido valida cada ítem y publica la comanda en el bus; cocina y barra la reciben (RF05, RF10).

## 5. Patrones

API Gateway (Facade) · Chain of Responsibility (Catálogo → Seguridad → ranking → explicación) ·
Strategy (IA o reglas) · Adapter (Adaptador de IA) · Circuit Breaker + Timeout · Specification
(reglas de exclusión) · Observer / publicación-suscripción (bus) · DTO hacia la IA · Caché del catálogo.

## 6. Seguridad

| Decisión | Detalle | Requisito |
|---|---|---|
| Sin datos personales del comensal | La sesión se vincula al puesto y al idioma | RNF02 · Ley 29733 |
| TLS en el borde y entre servicios | Caddy en el borde; certificado interno entre servicios | RNF07 |
| Autenticación entre servicios | Cada servicio valida un token emitido para él | RPR09 |
| Restricciones fuera de la IA | Seguridad Alimentaria siempre antes de cualquier llamada externa | RF14 · RPR03 |
| Respuesta de la IA validada | Se descarta cualquier id que no estuviera en la lista enviada | RNF01 · RPR01 |
| Secretos | Claves y tokens solo en variables de entorno | CLAUDE.md §2.9 |
| Límite de consultas a la IA | Por sesión, en configuración, aplicado en Recomendación | RP05 |

## 7. Rendimiento

El presupuesto de la v1.0 se mantiene; cada salto entre servicios suma tiempo, por eso se mide
después de desplegar, no se supone. Activación ≤ 2.5 s · carta ≤ 1.5 s · disponibilidad +
restricciones ≤ 300 ms · asistente ≤ 3 s · corte a la IA 5 s · fallback ≤ 1 s · pedido hasta
cocina/barra ≤ 2 s (P95, 4G).

## 8. Impacto y riesgos del cambio

- **Costo:** cada servicio, el gateway y el bus requieren cómputo y monitoreo. El presupuesto de
  infraestructura de la v1.0 (S/ 1,500) debe recalcularse.
- **Plazo y equipo:** más esfuerzo de despliegue y monitoreo con un solo programador.
- **Latencia:** RNF04 debe volver a medirse con los saltos reales.
- **Coherencia de datos:** sin transacciones distribuidas, el pedido y la trazabilidad usan
  confirmación en pasos y reintentos.
- **Alternativa de bajo costo:** la v1.0 (monolito modular) mantiene las mismas fronteras de
  dominio con un solo despliegue. Sigue disponible en `main` hasta la integración de esta rama.

## Notas de implementación

Rama `feature/arquitectura-servicios`. Diferencias con el borrador, todas a favor de las reglas
no negociables de CLAUDE.md §2:

1. **Una sola base SQLite, no una por servicio.** La migración 001 protege la seguridad
   alimentaria con claves foráneas y triggers que cruzan áreas (`recomendaciones` solo acepta filas
   de `v_productos_compatibles`; confirmar un pedido revalida cada ítem; cambiar un ingrediente
   anula la validación). Separar las bases obligaría a quitarlos, y eso lo prohíbe la regla 2.7.
   En su lugar, **cada servicio escribe solo en sus tablas** (CLAUDE.md §4) y pide lo demás por la
   API del dueño. Hay dos excepciones de lectura:
   - Seguridad Alimentaria lee, en una sola transacción, `v_productos_compatibles` y los datos que
     la vista usa, para que las revisiones de producto y de perfil sean coherentes.
   - Piloto hace consultas de solo lectura para los reportes.
2. **Seguridad Alimentaria no confía en el perfil que le envían.** Recibe el id de sesión y, si
   viene, la lista de candidatos de Catálogo. Las restricciones declaradas las lee de la base en
   la misma instantánea que evalúa.
3. **La derivación al mozo se ofrece, no se crea sola** (CLAUDE.md §6.2–6.3). El comensal la
   confirma y el llamado llega al Servicio de Atención, que lo publica en el bus para sala.
4. **El límite de consultas con IA lo aplica Recomendación** (§6 del borrador). El Adaptador de IA
   no recibe el id de sesión, así que no podría contarlas.
5. **Las cuentas del personal viven en el Servicio de Sesión**, que el borrador no asignaba. El
   Gateway autentica al personal llamando a Sesión y pasa su identidad a cada servicio en
   `X-Personal`. Esa cabecera solo se acepta junto con el token del servicio, y el Gateway descarta
   la que venga de fuera.
6. **La importación del catálogo se divide en dos.** Catálogo carga productos y textos;
   Seguridad Alimentaria carga ingredientes, alérgenos y evaluaciones. No es una transacción única.
   Si la segunda parte falla, los productos tocados quedan sin validar (no se recomiendan ni se
   pueden pedir) hasta repetir la importación, que es idempotente.
7. **Bus de eventos en memoria con Server-Sent Events.** No guarda datos de negocio. La comanda
   sigue en la tabla `comandas`. Las pantallas consultan cada 1.5 s si el bus no está conectado y
   cada 10 s si lo está (respaldo, RNF03).
8. **El Adaptador de IA también descarta ids ajenos** y Recomendación los vuelve a validar.
   Recomendación corta a los 5 s aunque el Adaptador no responda.
9. **TLS interno:** certificado propio para 127.0.0.1 generado por `ops/preparar-servidor.sh`. El
   Gateway escucha en HTTP local detrás de Caddy, que termina el HTTPS público.
10. **Medición local** (sin red 4G, sin proveedor de IA): la respuesta completa del asistente por el
    Gateway tarda ~35 ms en caliente (5 saltos internos). Falta medir P95 en 4G con
    `scripts/medir_latencia.py` antes del Go/No-Go.
