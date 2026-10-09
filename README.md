# MaidoTouch

Web-app de autopedido para la zona a la carta y de barra de Maido. El comensal acerca su celular al
tag NFC de la mesa (o lee el QR de respaldo), declara gustos y restricciones, recibe sugerencias
explicadas y envía su pedido a cocina y barra. El sistema complementa al mozo; no lo reemplaza.

Las reglas del proyecto están en [CLAUDE.md](CLAUDE.md) y los documentos fuente en [docs/](docs/).

## Requisitos

- JDK 21 (Gradle descarga Kotlin y las dependencias).
- Node.js 20 o superior para el frontend.

## Puesta en marcha local

```bash
# 1. Datos FICTICIOS de desarrollo: cuentas demo, catálogo de prueba validado y tres puestos.
MAIDOTOUCH_DB_PATH=dev.db ./gradlew run --args=demo
#    Imprime la clave de las cuentas admin-demo, cocina-demo, barra-demo y sala-demo,
#    y los enlaces /m/<token> de cada puesto.

# 2. Backend (API en :8080).
MAIDOTOUCH_DB_PATH=dev.db ./gradlew run

# 3. Frontend con recarga en caliente (:5173, redirige /api al backend).
cd frontend && npm install && npm run dev
```

- Comensal: abre uno de los enlaces `http://localhost:5173/m/<token>` impresos por `demo`.
- Back-office: `http://localhost:5173/admin`.
- Sin `ANTHROPIC_API_KEY` el asistente funciona con el ranking por reglas (fallback).

Para probar desde un celular en la misma red, usa la IP de la computadora en lugar de `localhost`
y define `MAIDOTOUCH_URL_PUBLICA` con esa dirección antes de crear los puestos.

## Pruebas

```bash
./gradlew test             # backend: motor de restricciones, asistente, pedido, API
cd frontend && npm test    # textos del comensal en español e inglés
cd frontend && npm run build
```

Los casos críticos de alergias están en `tests/dominio/` y deben pasar al 100 %. Ninguna prueba de
seguridad alimentaria se omite ni se debilita.

## Comandos de operación

```bash
./gradlew installDist                      # genera build/install/maidotouch/bin/maidotouch
maidotouch servir                          # aplica migraciones pendientes y levanta API + web-app
maidotouch migrar                          # respalda el archivo SQLite y aplica migraciones
maidotouch crear-personal cocina-01 cocina # roles: administrador, cocina, barra, sala
maidotouch cambiar-clave cocina-01
maidotouch importar-catalogo catalogo.json cocina-01
maidotouch verificar                       # criterios de Go/No-Go; sale con código 1 si hay FALLA
maidotouch reporte-diario [AAAA-MM-DD]     # monitoreo diario del piloto
```

El uso diario del sistema por sala, cocina, barra y administración está en
[docs/manual-operacion.md](docs/manual-operacion.md), y la verificación de cada requisito en
[docs/matriz-qa.md](docs/matriz-qa.md).

Las claves se leen de `MAIDOTOUCH_CLAVE` o se piden por consola. Usa identificadores de usuario
(`cocina-01`), no nombres de personas.

### Carga del catálogo

El Responsable de Datos prepara un JSON con el formato de
[db/catalogo/ficticio.json](db/catalogo/ficticio.json): categorías, alérgenos, ingredientes,
restricciones, preferencias y productos con textos es/en, ingredientes, alérgenos y la evaluación de
cada restricción con su evidencia. El importador:

- solo escribe lo que cambió; cada cambio en un producto anula su validación;
- nunca valida productos ni cambia su disponibilidad.

Después, cocina revisa y valida cada producto en *Back-office → Validación de carta*, y lo activa en
*Disponibilidad*. Un producto sin validar no aparece en la carta, no se recomienda y no se puede pedir.

## Integración continua y despliegue

- [.github/workflows/ci.yml](.github/workflows/ci.yml): pruebas del backend y del frontend en cada
  pull request; falla si alguna prueba se omite. En cada push arma el paquete desplegable.
- [.github/workflows/desplegar.yml](.github/workflows/desplegar.yml): `develop` → staging
  automático; etiqueta `vX.Y.Z` → producción, con aprobación en el entorno `produccion`. Necesita los
  secretos `SSH_HOST`, `SSH_USUARIO` y `SSH_CLAVE` en cada entorno de GitHub.
- Ramas `main` y `develop` protegidas en GitHub (Settings → Branches): pull request obligatorio,
  CI en verde y una revisión.

Un solo servidor (monolito modular) con [ops/](ops/):

| Archivo | Uso |
|---|---|
| `preparar-servidor.sh` | Instalación única: Java, SQLite, Caddy con HTTPS automático, systemd y respaldo diario |
| `desplegar.sh` | Rechaza el horario de servicio, respalda la base, instala, verifica la versión y revierte solo si falla |
| `revertir.sh` | Vuelve a la versión anterior; `--restaurar-base` restaura además el último respaldo |
| `respaldar.sh` | Respaldo consistente de SQLite en caliente (30 días) |
| `maidotouch.service`, `Caddyfile` | Servicio con reinicio automático y proxy HTTPS |

`scripts/empaquetar.sh` arma el paquete y `scripts/medir_latencia.py` mide el P95 contra el
presupuesto del diseño.

## Estructura

```
backend/
  presentacion/   API REST (Ktor) y composición de servicios
  aplicacion/     sesión, catálogo, orquestador de recomendación, pedido, atención, back-office
  dominio/        motor de restricciones, filtro de disponibilidad, ranking por reglas, explicaciones
  integracion/    adaptador del proveedor de IA (timeout + circuit breaker)
  datos/          repositorios sobre SQLite
db/migrations/    001 (no editar), 002, ...
frontend/         web-app del comensal (/m/<token>) y back-office (/admin)
tests/            dominio/ (casos críticos) e integracion/
```
