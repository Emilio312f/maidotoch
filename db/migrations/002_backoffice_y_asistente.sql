-- MaidoTouch: credenciales y sesiones del personal (back-office) y registro de consultas al asistente.
-- No modifica ni debilita la migracion 001. Fechas UTC.
PRAGMA foreign_keys = ON;
BEGIN;

-- Clave del personal: solo hash PBKDF2 con sal, nunca la clave en claro.
CREATE TABLE personal_credenciales (
 personal_id INTEGER PRIMARY KEY REFERENCES personal(id),
 hash TEXT NOT NULL,
 sal TEXT NOT NULL,
 iteraciones INTEGER NOT NULL CHECK (iteraciones >= 100000),
 actualizado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- Sesiones del back-office: se guarda el hash del token, no el token.
CREATE TABLE personal_sesiones (
 token_hash TEXT PRIMARY KEY NOT NULL,
 personal_id INTEGER NOT NULL REFERENCES personal(id),
 creada_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 expira_en TEXT NOT NULL,
 CHECK (expira_en > creada_en)
);
CREATE INDEX ix_personal_sesiones_personal ON personal_sesiones(personal_id);

-- Una fila por consulta al asistente: motor usado y latencia (RNF04, RP05).
-- No se guarda el texto de la consulta, que podria contener datos personales (RNF02).
CREATE TABLE consultas_asistente (
 id INTEGER PRIMARY KEY,
 sesion_id TEXT NOT NULL REFERENCES sesiones(id),
 motor TEXT NOT NULL CHECK (motor IN ('ia','fallback','ninguno')),
 latencia_ms INTEGER NOT NULL CHECK (latencia_ms >= 0),
 recomendaciones INTEGER NOT NULL CHECK (recomendaciones >= 0),
 fecha TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX ix_consultas_sesion ON consultas_asistente(sesion_id, motor);

-- recomendaciones.motor solo admite los dos motores previstos.
CREATE TRIGGER recomendacion_motor_valido BEFORE INSERT ON recomendaciones
WHEN NEW.motor NOT IN ('ia','fallback')
BEGIN SELECT RAISE(ABORT,'Motor de recomendacion no valido'); END;

PRAGMA user_version = 2;
COMMIT;
