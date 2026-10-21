# MaidoTouch

Web-app de autopedido para la zona a la carta y de barra de Maido. El comensal acerca su celular al
tag NFC de la mesa (o lee el QR de respaldo), declara gustos y restricciones, recibe sugerencias
explicadas y envía su pedido a cocina y barra. El sistema complementa al mozo; no lo reemplaza.

Las reglas del proyecto están en [CLAUDE.md](CLAUDE.md) y los documentos fuente en [docs/](docs/).
La arquitectura es de servicios independientes según el
[Diseño Arquitectónico v1.1](docs/diseno-arquitectonico-v1.1.md) (pendiente de aprobación del sponsor).

## Arquitectura en una línea

Web-app → **API Gateway** (:8080) → Sesión, Catálogo, **Seguridad Alimentaria**, Recomendación,
Adaptador de IA, Pedido, Atención, Trazabilidad, Piloto y Bus de eventos (:8101–8110), cada uno en
su proceso, con su token de servicio, sobre una base SQLite compartida que conserva todos los
triggers de seguridad alimentaria.

## Requisitos

- JDK 21 (Gradle descarga Kotlin y las dependencias).
- Node.js 20 o superior para el frontend.

## Puesta en marcha local

```bash
# 1. Web-apps compiladas (el gateway las entrega en / y /admin).
cd frontend && npm install && npm run build && cd ..

# 2. Los once procesos con datos FICTICIOS: genera los tokens en .env.servicios, migra la base,
#    levanta los servicios y carga cuentas demo, catálogo de prueba validado y tres puestos.
scripts/desarrollo.sh demo
#    Imprime la clave de admin-demo, cocina-demo, barra-demo y sala-demo y los enlaces /m/<token>.
#    Registros de cada servicio en build/desarrollo/<servicio>.log. Ctrl+C detiene todo.

# 3. (Opcional) Frontend con recarga en caliente; /api va al gateway.
cd frontend && npm run dev               # comensal en :5173
cd frontend && npm run dev:backoffice    # back-office en :5174/admin/
```

- Comensal: `http://localhost:8080/m/<token>` (o `:5173/m/<token>` en modo desarrollo).
- Back-office: `http://localhost:8080/admin/`.
- Sin `ANTHROPIC_API_KEY` el Adaptador de IA responde «no configurado» y el asistente usa el
  ranking por reglas (fallback). Si la clave está en `.env`, el script se la entrega solo al Adaptador.

Para probar desde un celular en la misma red, usa la IP de la computadora en lugar de `localhost`
y define `MAIDOTOUCH_URL_PUBLICA` con esa dirección antes de crear los puestos.

## Pruebas

```bash
./gradlew test             # todos los servicios: motor de restricciones, asistente, pedido, gateway, TLS interno
cd frontend && npm test    # textos del comensal en español e inglés
cd frontend && npm run build
```

Los casos críticos de alergias están en `tests/dominio/` y deben pasar al 100 %. Ninguna prueba de
seguridad alimentaria se omite ni se debilita. `tests/soporte/Sistema.kt` conecta los servicios en
un mismo proceso por sus interfaces; `SistemaHttp.kt` levanta los once procesos de verdad y las
pruebas entran por el API Gateway.

## Comandos de operación

```bash
./gradlew installDist     # build/<servicio>/install/maidotouch-<servicio>/bin/maidotouch-<servicio>
H=build/herramientas/install/maidotouch-herramientas/bin/maidotouch-herramientas
$H migrar                                 # respalda el archivo SQLite y aplica migraciones (antes de levantar)
$H tokens                                 # tokens de servicio nuevos (MAIDOTOUCH_TOKEN_*)
$H crear-personal cocina-01 cocina        # roles: administrador, cocina, barra, sala
$H cambiar-clave cocina-01
$H importar-catalogo catalogo.json cocina-01
$H verificar                              # criterios de Go/No-Go; sale con código 1 si hay FALLA
$H reporte-diario [AAAA-MM-DD]            # monitoreo diario del piloto
```

Salvo `migrar` y `tokens`, los comandos hablan con los servicios en ejecución usando
`MAIDOTOUCH_URL_*` y `MAIDOTOUCH_TOKEN_*`.

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

Un solo servidor con un proceso por servicio, con [ops/](ops/):

| Archivo | Uso |
|---|---|
| `preparar-servidor.sh` | Instalación única: Java, SQLite, Caddy con HTTPS, tokens de servicio, certificado del TLS interno, systemd y respaldo diario |
| `desplegar.sh` | Todos los servicios o solo los indicados: rechaza el horario de servicio, respalda, migra, cambia servicio por servicio, verifica la versión en `/api/salud` y revierte solo si falla |
| `revertir.sh` | Vuelve a la versión anterior de todos o de un servicio; `--restaurar-base` restaura además el último respaldo |
| `respaldar.sh` | Respaldo consistente de SQLite en caliente (30 días) |
| `maidotouch@.service`, `maidotouch.target`, `Caddyfile` | Un proceso por servicio con reinicio automático, y proxy HTTPS al gateway |

`scripts/empaquetar.sh` arma el paquete y `scripts/medir_latencia.py` mide el P95 contra el
presupuesto del diseño.

## Despliegue en Coolify (staging)

El [Dockerfile](Dockerfile) arma una sola imagen con los once servicios y las dos web-apps. Dentro del
contenedor cada servicio es un proceso aparte; solo se expone el API Gateway (8080). El HTTPS y el
dominio los pone el proxy de Coolify.

1. En Coolify: *New Resource → Public/Private Repository*, rama a desplegar, build pack **Dockerfile**.
2. *Ports Exposes*: `8080`. *Domains*: el dominio de staging.
3. *Persistent Storage*: un volumen montado en `/datos` (base SQLite, fotos y tokens).
4. Variables de entorno:

| Variable | Valor |
|---|---|
| `MAIDOTOUCH_URL_PUBLICA` | `https://<dominio-de-staging>` (enlaces de los tags NFC y QR) |
| `MAIDOTOUCH_DEMO` | `piloto` para cargar el catálogo piloto, cuentas demo y los puestos M1, M2 y B1 |
| `MAIDOTOUCH_CLAVE` | Clave de las cuentas demo (mínimo 10 caracteres) |
| `MAIDOTOUCH_IA_PROVEEDOR` | `servidor-casa` para el servidor de IA del equipo, o `claude` (por defecto) |
| `MAIDOTOUCH_IA_SERVIDOR_URL` | Con `servidor-casa`: URL HTTPS del servidor, terminada en `/v1` |
| `MAIDOTOUCH_IA_SERVIDOR_CLAVE` | Con `servidor-casa`: clave del servidor (marcarla como secreta en Coolify) |
| `MAIDOTOUCH_IA_SERVIDOR_MODELO` | Con `servidor-casa`: modelo, por ejemplo `qwen3-4b` |
| `ANTHROPIC_API_KEY` | Con `claude`; sin ella el asistente usa el ranking por reglas |

Las variables del proveedor de IA (dirección y claves) solo las recibe el proceso del Adaptador de IA.

Los tokens de servicio se generan solos en el primer arranque (`/datos/tokens.env`). El contenedor
tiene healthcheck sobre `/api/salud` y se detiene si cualquier servicio cae, para que Coolify lo
reinicie. Memoria: unos 1.1 GB; conviene un servidor de 2 GB o más.

Probarlo en local:

```bash
docker build -t maidotouch .
docker run -p 8080:8080 -v maidotouch-datos:/datos -e MAIDOTOUCH_DEMO=piloto \
  -e MAIDOTOUCH_CLAVE=una-clave-larga -e MAIDOTOUCH_URL_PUBLICA=http://localhost:8080 maidotouch
```

## Estructura

```
backend/
  comun/                  SQLite, servidor y cliente HTTP internos (token y TLS), contratos entre servicios
  gateway/                API Gateway: enrutamiento, autenticación del personal, límite de solicitudes, web-apps
  sesion/                 sesión por puesto, perfil, cuentas del personal, puestos NFC/QR
  catalogo/               carta, disponibilidad, validación, fotos, importador (parte de catálogo)
  seguridad-alimentaria/  Motor de Restricciones, filtro de disponibilidad, alérgenos (sin IA)
  recomendacion/          orquestador, ranking por reglas, explicaciones
  adaptador-ia/           única frontera con el proveedor (timeout + circuit breaker)
  pedido/  atencion/  trazabilidad/  piloto/  bus/
  herramientas/           comandos de operación
db/migrations/            001 (no editar), 002, ...
frontend/                 comensal/ (/, /m/<token>) y backoffice/ (/admin), código común en src/comun
tests/                    dominio/ (casos críticos), integracion/ y soporte/
```
