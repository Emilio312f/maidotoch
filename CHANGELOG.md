# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); versionado semántico.

## [Sin publicar]

### Cambiado
- Arquitectura de servicios independientes según el Diseño Arquitectónico v1.1 (pendiente de
  aprobación del sponsor). Pasa de un monolito modular a once procesos: API Gateway, Sesión,
  Catálogo, Seguridad Alimentaria, Recomendación, Adaptador de IA, Pedido, Atención,
  Trazabilidad, Piloto y Bus de eventos. Cada uno es un módulo Gradle con despliegue propio
  (`maidotouch@<servicio>`).
- Una sola base SQLite con todos los triggers de la migración 001: cada servicio escribe solo en
  sus tablas. Es la excepción a «una base por servicio» por la regla 2.7 (ver
  docs/diseno-arquitectonico-v1.1.md).
- Web-app dividida en dos aplicaciones: comensal (`/`) y back-office (`/admin`).
- Comandos de operación en `maidotouch-herramientas`; despliegue y reversión por servicio.

### Agregado (v1.1)
- Token de servicio en cada llamada interna; el Gateway no expone `/interno` y descarta la
  identidad del personal que venga del cliente (RPR09).
- TLS entre servicios con certificado interno generado al preparar el servidor (RNF07).
- Límite de solicitudes por IP y por sesión en el Gateway, y `/api/salud` con la versión de cada servicio.
- Bus de eventos con Server-Sent Events: las pantallas de cocina, barra y sala reciben comandas y
  llamados al instante, con la consulta periódica como respaldo (RF05, RF06, RNF03).
- `scripts/desarrollo.sh` levanta los once procesos en desarrollo.
- 109 pruebas de backend: las anteriores, más pruebas del Adaptador de IA, el Gateway, el bus y el TLS interno.

### Agregado
- Motor de Restricciones Alimentarias determinístico (Seguro / Inseguro / Sin dato). Un producto es
  candidato solo si el motor lo declara Seguro y `v_productos_compatibles` lo incluye (RF09, RF12, RF14, RNF01).
- Orquestador de recomendación: disponibilidad → restricciones → ranking → explicación. Adaptador del
  proveedor de IA con DTO mínimo, validación de ids, timeout de 5 s, circuit breaker, límite de
  consultas por sesión y ranking por reglas como fallback (RF03, RF09, RNF03, RNF04).
- Explicaciones construidas en el dominio con preferencias declaradas, catálogo y restricciones (RNF01).
- Sesión por token NFC/QR por puesto, sin datos personales; perfil breve; cierre que descarta el
  perfil y cierre automático de sesiones abandonadas (RF01, RF07, RNF02).
- Carta es/en con disponibilidad y aptitud por perfil (RF02, RF04, RF12).
- Carrito, confirmación explícita y comandas por destino con recepción e intentos (RF05, RF10, RF11).
- Llamado al mozo y derivación por restricción no validable (RF06, RF14).
- Trazabilidad de recomendaciones mostradas, aceptadas, modificadas y rechazadas (RF13, RNF09).
- Back-office autenticado (PBKDF2, tokens con hash): disponibilidad con historial, validación de la
  carta por cocina, puestos NFC/QR activables por etapa, comandas, llamados y métricas del piloto (RF08).
- Migración 002: credenciales y sesiones del personal, registro de consultas al asistente y control
  de `recomendaciones.motor`.
- Importador del catálogo y catálogo FICTICIO para desarrollo y pruebas; comandos de operación.
- Web-app responsive del comensal y back-office (React + Vite).
- Administración de cuentas del personal desde el back-office.
- Alerta para sala de comandas sin recepción tras 2 minutos (respaldo manual, RNF03).
- Mediciones de línea base y piloto, reporte diario y verificación de Go/No-Go (API, back-office y consola).
- Hoja imprimible con el QR de respaldo de cada puesto.
- `/api/salud` informa la versión desplegada.
- Integración continua y despliegue en GitHub Actions; scripts de empaquetado, despliegue con
  reversión automática, reversión manual, respaldos, servicio systemd y Caddy con HTTPS.
- Script de medición de latencia P95; manual de operación; matriz de QA; plantillas de solicitud de
  cambio, defecto y pull request.
- Pruebas de backend (casos críticos de alergias y de IA) y pruebas de textos es/en.
