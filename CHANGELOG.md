# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); versionado semántico.

## [Sin publicar]

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
- 94 pruebas de backend (casos críticos de alergias y de IA) y pruebas de textos es/en.
