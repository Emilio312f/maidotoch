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
```

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

## Despliegue

Un solo servidor (monolito modular). El backend sirve la API y la web-app compilada:

```bash
cd frontend && npm ci && npm run build && cd ..
./gradlew installDist
# Variables de .env.example en el entorno del servicio; luego:
build/install/maidotouch/bin/maidotouch servir
```

El backend escucha HTTP en `MAIDOTOUCH_PUERTO`. HTTPS lo termina un proxy inverso en el mismo
servidor, por ejemplo Caddy, que obtiene el certificado automáticamente:

```
maidotouch.example {
    reverse_proxy localhost:8080
}
```

Antes de cada despliegue: respaldar el archivo SQLite y conservar la versión anterior etiquetada.
Desplegar solo fuera del horario de servicio (Plan §7.2).

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
