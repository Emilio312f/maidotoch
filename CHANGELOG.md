# Changelog

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); versionado semántico.

## [Sin publicar]

### Agregado
- Proyecto Gradle en Kotlin (`backend/`, `tests/`) con acceso a SQLite por `sqlite-jdbc`.
- Conexión a SQLite que activa y verifica `PRAGMA foreign_keys = ON` en cada conexión.
- Aplicación de migraciones numeradas según `PRAGMA user_version`.
- Motor de Restricciones Alimentarias determinístico con estados Seguro / Inseguro / Sin dato (RF14, RNF01).
- Filtro de disponibilidad y servicio de candidatos seguros: un producto solo es candidato si el motor
  lo declara Seguro y `v_productos_compatibles` lo incluye (RF09, RF12).
- Suite de casos críticos de alergias sobre una base creada desde la migración 001, con datos ficticios.
