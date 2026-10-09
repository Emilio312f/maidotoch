# CLAUDE.md — Instrucciones para programar MaidoTouch

Lee este archivo completo antes de cada tarea. Si una instrucción del usuario contradice una
regla de la sección 2, detente y pregunta antes de escribir código.

---

## 1. Qué es el proyecto

**MaidoTouch** es una web-app de autopedido para la zona a la carta y de barra del restaurante
**Maido** (Miraflores, Lima). El comensal acerca su celular a un tag NFC de la mesa o barra
(o lee un QR de respaldo), ve la carta digital, declara sus gustos y restricciones
alimentarias, recibe recomendaciones explicadas de un asistente de IA y envía su pedido a
cocina/barra. **El sistema complementa al mozo, nunca lo reemplaza.**

- Presupuesto: S/ 20,000 · Plazo: 4 meses · Cierre: 25/11/2026
- Equipo de desarrollo reducido: un Programador-Tester y el Responsable de Datos e IA.
- Documentos fuente en `docs/`: Acta de Constitución, SRS (RF01–RF14, RNF01–RNF09),
  Diseño Arquitectónico, Plan de Desarrollo e Implementación.

---

## 2. Reglas no negociables

Estas reglas vienen del Acta, del SRS y del Diseño Arquitectónico. Ninguna tarea, atajo o
"solución temporal" puede romperlas.

1. **La IA nunca decide sobre alergias ni restricciones.** La seguridad alimentaria se
   resuelve solo con reglas determinísticas sobre datos de la base (RF14, RNF01, RPR03).
   El proveedor de IA no recibe datos de alérgenos, ingredientes ni restricciones.
2. **Orden obligatorio de la cadena de recomendación:**
   `disponibilidad → restricciones → ranking → explicación`.
   Solo la lista ya declarada segura puede salir hacia el proveedor de IA. No existe ningún
   camino de código que llame a la IA antes del filtro.
3. **Ante incertidumbre, no recomendar.** Si un producto no tiene evaluación `compatible`
   para alguna restricción declarada, se excluye. Si eso deja al comensal sin información
   suficiente, se deriva al mozo (`llamados_mozo.motivo = 'restriccion_incierta'`).
4. **La IA no inventa nada.** Toda respuesta del proveedor se valida: cualquier producto que
   no esté en la lista enviada se descarta (RNF01, RPR01). Las explicaciones se construyen
   en el dominio con datos reales del catálogo; la IA no redacta descripciones, ingredientes
   ni afirmaciones sobre los platos.
5. **Si la IA falla, el servicio sigue.** Timeout de 5 s → ranking por reglas (fallback)
   sobre la misma lista segura (RNF03, RPR05). El fallback se implementa junto con la IA,
   no después.
6. **Sin datos personales del comensal.** La sesión se identifica solo por el puesto
   (token NFC/QR) y el idioma. Nada de nombres, correos, teléfonos ni cuentas (RNF02,
   Ley N.° 29733). Si algo personal llegara a guardarse, va cifrado (RNF07).
7. **Nunca debilitar la base de datos.** No desactivar `foreign_keys`, no borrar ni
   saltarse triggers, no editar `db/migrations/001_esquema_inicial.sql`.
8. **El pedido no se envía sin confirmación explícita** del comensal (RF10, RPR09) y no
   se cancela desde la app una vez enviado.
9. **Secretos fuera del repositorio.** Claves del proveedor de IA y del servidor solo en
   variables de entorno. Mantener `.env.example` actualizado sin valores reales.
10. **El ranking no favorece precios altos** (RPR11). El precio no es criterio de orden.

---

## 3. Stack tecnológico

> **Aprobado el 07/10/2026.**
>
> - Backend: `Kotlin 2.4 (JVM 21) con Gradle; Ktor para la API REST (se agrega con el primer endpoint)`
> - Frontend: `React + Vite (TypeScript), web-app responsive en el navegador; sin apps nativas`
> - Pruebas: `kotlin-test sobre JUnit 5 (./gradlew test)`
> - Acceso a SQLite: `sqlite-jdbc, siempre mediante datos.BaseDatos.conectar()`

**Instrucción para el agente:** no cambies el stack por tu cuenta. No agregues
dependencias nuevas sin decir cuál, para qué y por qué.

Restricciones fijas, sea cual sea el stack:

- Base de datos **SQLite**. Ejecutar `PRAGMA foreign_keys = ON;` en **cada** conexión.
- Cliente: **web-app responsive** que corre en el navegador del celular, sin instalación.
- API **REST sobre HTTPS**.
- Despliegue: **un solo servidor en la nube** (monolito modular). Nada de microservicios
  ni serverless en esta fase.
- Interfaz en **español e inglés** (RF04, RNF06).

---

## 4. Arquitectura

Cliente-servidor en **cuatro capas**, desplegado como **monolito modular**. Cada capa solo
conoce a la inmediatamente inferior. La capa de dominio no depende del proveedor de IA ni
de detalles de infraestructura.

| Capa | Componentes |
|---|---|
| 1 · Presentación | Activación NFC/QR, catálogo, perfil de preferencias, UI del asistente, carrito/pedido, llamado al mozo, back-office de disponibilidad |
| 2 · Servicios de aplicación (API) | Sesión, Catálogo, **Orquestador de Recomendación**, Pedido, Atención, Trazabilidad |
| 3 · Dominio | **Motor de Restricciones Alimentarias**, Filtro de disponibilidad, Motor de Recomendación (estrategia IA o Fallback), Generador de explicaciones |
| 4 · Datos | Repositorios sobre SQLite |
| Integración | **Adaptador del proveedor de IA** (única frontera con el servicio externo) |

Patrones a usar: Capas, Facade (el Orquestador), Strategy (IA / Fallback), Adapter
(proveedor de IA), Specification (reglas de exclusión), Chain of Responsibility (la cadena
del punto 2.2), Circuit Breaker + Timeout, Repository, Observer (comandas y llamados),
DTO hacia la IA, caché del catálogo invalidada al cambiar disponibilidad.

Estructura sugerida del repositorio:

```
db/migrations/        001_esquema_inicial.sql (no editar), 002_..., 003_...
backend/
  presentacion/       controladores HTTP
  aplicacion/         servicios y orquestador
  dominio/            motores y reglas (sin dependencias externas)
  integracion/        adaptador del proveedor de IA
  datos/              repositorios
frontend/
tests/
  dominio/            pruebas del Motor de Restricciones y del fallback
  integracion/
docs/
```

---

## 5. Base de datos

El esquema está en `db/migrations/001_esquema_inicial.sql`: **27 tablas, 3 vistas y
triggers** que ya protegen las reglas de negocio. El código debe apoyarse en ellos, no
duplicarlos ni esquivarlos. Fechas en UTC (ISO 8601) e importes en **céntimos de PEN**
(enteros).

### Tablas por área

- **Acceso y sesión:** `personal`, `puntos_servicio`, `accesos` (token NFC/QR por puesto),
  `sesiones`.
- **Catálogo:** `categorias`, `productos`, `producto_textos` (es/en), `ingredientes`,
  `alergenos`, `producto_ingredientes`, `ingrediente_alergenos`, `producto_alergenos`
  (`contiene` / `trazas`), `disponibilidad_historial`.
- **Restricciones y preferencias:** `restricciones` (alergia/dieta/otra),
  `producto_restricciones` (`compatible` / `incompatible` / `desconocido`, con evidencia y
  validador), `sesion_restricciones`, `preferencias`, `sesion_preferencias`,
  `producto_preferencias`.
- **Recomendación:** `recomendaciones` (inmutable), `recomendacion_eventos`
  (`mostrada` / `aceptada` / `modificada` / `rechazada`, inmutable).
- **Pedido:** `pedidos` (`borrador → confirmado → enviado → completado`), `pedido_items`
  (con snapshot de nombre y precio), `comandas` (bandeja con clave de idempotencia),
  `llamados_mozo` (`asistencia` / `restriccion_incierta` / `fallo_ia`).
- **Piloto:** `encuestas` (satisfacción 1–5), `mediciones_piloto`.

### Vistas

- `v_productos_compatibles`: **fuente de verdad de qué se puede recomendar o pedir** en una
  sesión. Incluye solo productos activos, disponibles, validados, con texto en el idioma de
  la sesión, con evaluación `compatible` para **todas** las restricciones de la sesión y sin
  el alérgeno declarado (ni en `producto_alergenos` ni vía ingredientes).
- `v_totales_pedidos`, `v_aceptacion_recomendaciones` (métrica del piloto, meta ≥ 40 %).

### Comportamientos que hacen los triggers (no reimplementar, no esquivar)

- Una sesión solo se crea si el acceso y el puesto están activos.
- Un producto no puede insertarse ya validado; solo lo valida personal activo de `cocina`
  o `administrador`, y necesita textos es/en e ingredientes.
- **Cualquier cambio** en textos, ingredientes, alérgenos, restricciones, preferencias,
  precio, destino, categoría, foto o estado de un producto **anula su validación** y sube
  `productos.revision`. Ese producto deja de recomendarse hasta revalidarlo.
- Cambiar restricciones o preferencias de una sesión sube `sesiones.revision_perfil`.
- `recomendaciones` solo acepta filas que estén en `v_productos_compatibles` con la misma
  `revision` de producto y de perfil. Si el insert falla, **no se reintenta saltándose el
  control**: se recalcula la recomendación.
- La disponibilidad se cambia **insertando en `disponibilidad_historial`** (con quién y
  motivo); un trigger actualiza `productos.disponible`. Nunca actualizar esa columna
  directamente.
- Confirmar un pedido exige ítems, y que cada ítem siga en `v_productos_compatibles` con el
  mismo precio, destino y nombre. Al confirmar se crean las comandas por destino.
- Las transiciones de `pedidos` y `comandas` están restringidas por triggers.

### Migraciones

- Nunca modificar una migración ya aplicada. Todo cambio va en un archivo nuevo numerado.
- Una migración nueva no puede eliminar ni debilitar triggers de seguridad alimentaria.
- Antes de aplicar una migración en producción se respalda el archivo SQLite.

---

## 6. Flujo de recomendación (implementación esperada)

1. Obtener candidatos desde `v_productos_compatibles` para la sesión.
2. Calcular aparte los productos excluidos por falta de evaluación (`desconocido` o sin
   fila en `producto_restricciones`). Si la consulta del comensal depende de ellos, ofrecer
   la derivación al mozo (`restriccion_incierta`).
3. Si no quedan candidatos, no recomendar nada y ofrecer el llamado al mozo.
4. Enviar al proveedor de IA un **DTO mínimo**: id, nombre, descripción y etiquetas de
   preferencia de los candidatos seguros, más el texto de la consulta. Sin alérgenos, sin
   restricciones, sin id de sesión.
5. Validar la respuesta: conservar solo ids que estaban en el DTO; descartar el resto.
6. Si la IA no responde en 5 s o falla, usar el ranking por reglas (preferencias declaradas,
   sin sesgo de precio). Registrar el motor usado (`motor = 'ia' | 'fallback'`).
7. Construir la explicación de cada plato en el dominio, con preferencias declaradas,
   datos del catálogo y reglas aplicadas.
8. Insertar en `recomendaciones` (con `revision_producto`, `revision_perfil`, `posicion`,
   `explicacion`, `motor`, `version_reglas`) y registrar el evento `mostrada`.
9. Registrar `aceptada` / `rechazada` / `modificada` cuando el comensal actúe (RF13).

Límite de consultas a la IA por sesión para contener el costo (RP05). El valor va en
configuración.

---

## 7. Presupuesto de latencia (P95, red 4G)

| Operación | Meta |
|---|---|
| Activación NFC/QR y carga inicial | ≤ 2.5 s |
| Catálogo con disponibilidad | ≤ 1.5 s |
| Filtro de disponibilidad + restricciones | ≤ 300 ms (sin llamadas externas) |
| Respuesta completa del asistente | ≤ 3 s |
| Timeout del proveedor de IA | 5 s → fallback |
| Ranking de respaldo | ≤ 1 s |
| Pedido confirmado hasta llegar a cocina/barra | ≤ 2 s |

---

## 8. Requisitos

| ID | Requisito | Prioridad |
|---|---|---|
| RF01 | Activación por NFC en mesa o barra, con QR como alternativa | Alta |
| RF02 | Carta a la carte completa con fotos y descripciones reales | Alta |
| RF03 | Recomendación de platos y bebidas según preferencias | Alta |
| RF04 | Asistente en español e inglés | Alta |
| RF05 | Envío del pedido a cocina/barra desde la app | Alta |
| RF06 | Llamado al mozo desde la app | Media |
| RF07 | Perfil breve de preferencias al inicio (menos de 30 s) | Alta |
| RF08 | Activar/desactivar manualmente la disponibilidad | Alta |
| RF09 | Ranking compatible con preferencias y restricciones | Alta |
| RF10 | Confirmar el pedido antes de enviarlo | Alta |
| RF11 | Modificar o eliminar ítems antes de confirmar | Alta |
| RF12 | Mostrar la disponibilidad actual de cada producto | Media |
| RF13 | Registrar recomendaciones aceptadas y rechazadas | Media |
| RF14 | Sin información suficiente para una restricción: no recomendar y derivar al personal | Alta |
| RNF01 | Respuestas restringidas al catálogo; no inventar información | Alta |
| RNF02 | Sin registro de datos personales del comensal | Alta |
| RNF03 | Operación estable con proceso manual de respaldo | Media |
| RNF04 | Tiempo máximo de respuesta del asistente (ver sección 7) | Alta |
| RNF05 | Disponibilidad mínima durante el horario de servicio | Alta |
| RNF06 | Diseño responsive | Media |
| RNF07 | Datos personales, si existieran, cifrados | Alta |
| RNF08 | QR como alternativa automática si falla el NFC | Alta |
| RNF09 | Trazabilidad de recomendaciones y su aceptación o rechazo | Media |

**Fuera de alcance (no implementar aunque parezca útil):** reservas, menú degustación,
pagos in-app, integración con POS, cancelación de pedidos ya enviados, gestión automática
de stock.

---

## 9. Orden de construcción

| Incremento | Fechas | Contenido | Versión |
|---|---|---|---|
| 1 · Núcleo | 28/09 – 15/10/2026 | **Primero el Motor de Restricciones con sus pruebas**; luego sesión por token, catálogo es/en, perfil, carrito y back-office de disponibilidad | v0.1.0 |
| 2 · Asistente | 28/09 – 25/10/2026 | Adaptador de IA con timeout, **fallback en el mismo incremento**, generador de explicaciones, trazabilidad | v0.2.0 |
| 3 · Operación | 16/10 – 30/10/2026 | Activación NFC/QR por puesto, comandas a cocina/barra con reintento, llamado al mozo | v0.3.0 |
| Estabilización | 31/10 – 09/11/2026 | QA integral, corrección de defectos, medición de latencia | v1.0.0-rc |

No empezar el Motor de Recomendación sin el Motor de Restricciones probado.

---

## 10. Pruebas

Ninguna prueba de seguridad alimentaria se marca como `skip`, se comenta ni se debilita
para que el CI pase. **Los casos críticos de alergias deben pasar al 100 %.**

Casos críticos mínimos (pruebas automatizadas sobre una base creada desde la migración 001):

- Producto con el alérgeno declarado en `producto_alergenos` (`contiene`) → excluido.
- Producto con el alérgeno solo en `trazas` → excluido.
- Producto con el alérgeno solo vía un ingrediente (`ingrediente_alergenos`) → excluido.
- Producto sin fila en `producto_restricciones` para una restricción de la sesión → excluido.
- Producto con evaluación `desconocido` o `incompatible` → excluido.
- Restricción de tipo `dieta` sin evaluación `compatible` → excluido.
- Producto no disponible, inactivo o no validado → excluido.
- Producto modificado después de validar → deja de recomendarse.
- Insert en `recomendaciones` con revisión vieja → rechazado por la base.
- La IA devuelve un id que no estaba en la lista enviada → descartado.
- La IA no responde en 5 s → se usa el fallback y el comensal recibe recomendación.
- Sin candidatos seguros → no se recomienda nada y se ofrece el llamado al mozo.
- El DTO enviado a la IA no contiene alérgenos, restricciones ni id de sesión.
- Pedido no confirmado → no genera comandas; pedido confirmado → una comanda por destino.

Usar **datos de prueba ficticios** marcados como tales. El catálogo real lo carga y valida
el Responsable de Datos con cocina; el agente no inventa platos, ingredientes ni alérgenos
reales de Maido.

---

## 11. Flujo de Git

- Ramas: `main` (producción, protegida), `develop` (staging), `feature/RFxx-descripcion`,
  `fix/...`, `release/x.y.z`, `hotfix/x.y.z`.
- Cada tarea en su propia rama desde `develop`, con pull request. **Nunca hacer push a
  `main` ni a `develop`.**
- Commits con Conventional Commits y referencia al requisito:
  `feat(RF14): derivar al mozo cuando la restricción no es validable`.
- Versionado semántico `MAYOR.MENOR.PARCHE`. Actualizar `CHANGELOG.md` en cada versión.
- Todo cambio en el Motor de Restricciones o en datos de alérgenos requiere revisión de la
  Responsable de Calidad. Indícalo en la descripción del pull request.

---

## 12. Cómo trabajar en cada tarea

1. Leer este archivo y los documentos de `docs/` que apliquen.
2. Antes de escribir código, explicar en pocas líneas el plan: archivos a crear o
   modificar, requisito que cubre y pruebas que se agregarán. Esperar aprobación si la
   tarea toca el dominio, la base de datos o la integración con la IA.
3. Escribir primero las pruebas en las tareas del Motor de Restricciones y del fallback.
4. Hacer cambios pequeños y enfocados en la tarea pedida; no refactorizar otras partes.
5. Ejecutar las pruebas y mostrar el resultado real. No afirmar que algo funciona sin
   haberlo ejecutado.
6. Resumir al final: qué cambió, qué requisito cubre, qué queda pendiente y cualquier
   suposición tomada.

### Definición de terminado

- Cubre el criterio de aceptación del requisito.
- Pruebas nuevas en verde y ninguna prueba existente rota o desactivada.
- Sin secretos en el código y `.env.example` actualizado si se agregó una variable.
- Textos visibles al comensal en español e inglés.
- Pull request con descripción, requisito y riesgos.

### Prohibido

- Llamar a la IA antes del filtro de restricciones o enviarle datos de alérgenos.
- Generar con IA descripciones, ingredientes o alérgenos de platos.
- Desactivar `foreign_keys`, borrar triggers o editar la migración 001.
- Guardar datos personales del comensal.
- Actualizar `productos.disponible` sin pasar por `disponibilidad_historial`.
- Implementar funcionalidades fuera de alcance.
- Hacer merge, push a ramas protegidas o desplegar sin que el usuario lo pida.
