-- MaidoTouch: esquema inicial SQLite. Fechas UTC; importes en centimos PEN.
-- Ejecutar sobre una base NUEVA. Activar foreign_keys en cada conexion.
PRAGMA foreign_keys = ON;
BEGIN;

CREATE TABLE personal (
 id INTEGER PRIMARY KEY,
 identificador TEXT NOT NULL UNIQUE,
 rol TEXT NOT NULL CHECK (rol IN ('administrador','cocina','barra','sala')),
 activo INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0,1))
);
CREATE TABLE puntos_servicio (
 id INTEGER PRIMARY KEY,
 codigo TEXT NOT NULL UNIQUE,
 tipo TEXT NOT NULL CHECK (tipo IN ('mesa','barra')),
 activo INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0,1))
);
CREATE TABLE accesos (
 id INTEGER PRIMARY KEY,
 punto_id INTEGER NOT NULL REFERENCES puntos_servicio(id),
 canal TEXT NOT NULL CHECK (canal IN ('nfc','qr')),
 token TEXT NOT NULL UNIQUE,
 activo INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0,1))
);
CREATE TABLE sesiones (
 id TEXT PRIMARY KEY NOT NULL,
 acceso_id INTEGER NOT NULL REFERENCES accesos(id),
 idioma TEXT NOT NULL DEFAULT 'es' CHECK (idioma IN ('es','en')),
 inicio TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 cierre TEXT,
 perfil_completo INTEGER NOT NULL DEFAULT 0 CHECK (perfil_completo IN (0,1)),
 revision_perfil INTEGER NOT NULL DEFAULT 1 CHECK (revision_perfil > 0),
 CHECK (cierre IS NULL OR cierre >= inicio)
);
CREATE TABLE categorias (
 id INTEGER PRIMARY KEY,
 codigo TEXT NOT NULL UNIQUE,
 nombre_es TEXT NOT NULL,
 nombre_en TEXT NOT NULL,
 orden INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE productos (
 id INTEGER PRIMARY KEY,
 codigo TEXT NOT NULL UNIQUE,
 categoria_id INTEGER NOT NULL REFERENCES categorias(id),
 tipo TEXT NOT NULL CHECK (tipo IN ('plato','bebida')),
 destino TEXT NOT NULL CHECK (destino IN ('cocina','barra')),
 precio_centimos INTEGER NOT NULL CHECK (precio_centimos >= 0),
 foto_url TEXT,
 activo INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0,1)),
 disponible INTEGER NOT NULL DEFAULT 0 CHECK (disponible IN (0,1)),
 revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
 validado_por INTEGER REFERENCES personal(id),
 validado_en TEXT,
 CHECK ((validado_por IS NULL) = (validado_en IS NULL))
);
CREATE TABLE producto_textos (
 producto_id INTEGER NOT NULL REFERENCES productos(id),
 idioma TEXT NOT NULL CHECK (idioma IN ('es','en')),
 nombre TEXT NOT NULL CHECK (length(trim(nombre)) > 0),
 descripcion TEXT NOT NULL CHECK (length(trim(descripcion)) > 0),
 historia TEXT,
 PRIMARY KEY (producto_id,idioma)
);
CREATE TABLE ingredientes (
 id INTEGER PRIMARY KEY,
 codigo TEXT NOT NULL UNIQUE,
 nombre_es TEXT NOT NULL,
 nombre_en TEXT NOT NULL
);
CREATE TABLE alergenos (
 id INTEGER PRIMARY KEY,
 codigo TEXT NOT NULL UNIQUE,
 nombre_es TEXT NOT NULL,
 nombre_en TEXT NOT NULL
);
CREATE TABLE producto_ingredientes (
 producto_id INTEGER NOT NULL REFERENCES productos(id),
 ingrediente_id INTEGER NOT NULL REFERENCES ingredientes(id),
 PRIMARY KEY (producto_id,ingrediente_id)
);
CREATE TABLE ingrediente_alergenos (
 ingrediente_id INTEGER NOT NULL REFERENCES ingredientes(id),
 alergeno_id INTEGER NOT NULL REFERENCES alergenos(id),
 PRIMARY KEY (ingrediente_id,alergeno_id)
);
CREATE TABLE producto_alergenos (
 producto_id INTEGER NOT NULL REFERENCES productos(id),
 alergeno_id INTEGER NOT NULL REFERENCES alergenos(id),
 origen TEXT NOT NULL CHECK (origen IN ('contiene','trazas')),
 PRIMARY KEY (producto_id,alergeno_id,origen)
);
CREATE TABLE restricciones (
 id INTEGER PRIMARY KEY,
 codigo TEXT NOT NULL UNIQUE,
 tipo TEXT NOT NULL CHECK (tipo IN ('alergia','dieta','otra')),
 nombre_es TEXT NOT NULL,
 nombre_en TEXT NOT NULL,
 alergeno_id INTEGER REFERENCES alergenos(id),
 CHECK ((tipo = 'alergia' AND alergeno_id IS NOT NULL) OR
        (tipo <> 'alergia' AND alergeno_id IS NULL))
);
-- Ausencia de una evaluacion = desconocido, nunca compatible por defecto.
CREATE TABLE producto_restricciones (
 producto_id INTEGER NOT NULL REFERENCES productos(id),
 restriccion_id INTEGER NOT NULL REFERENCES restricciones(id),
 resultado TEXT NOT NULL CHECK (resultado IN ('compatible','incompatible','desconocido')),
 evidencia TEXT NOT NULL CHECK (length(trim(evidencia)) > 0),
 validado_por INTEGER NOT NULL REFERENCES personal(id),
 validado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 PRIMARY KEY (producto_id,restriccion_id)
);
CREATE TABLE sesion_restricciones (
 sesion_id TEXT NOT NULL REFERENCES sesiones(id),
 restriccion_id INTEGER NOT NULL REFERENCES restricciones(id),
 PRIMARY KEY (sesion_id,restriccion_id)
);
CREATE TABLE preferencias (
 id INTEGER PRIMARY KEY,
 codigo TEXT NOT NULL UNIQUE,
 nombre_es TEXT NOT NULL,
 nombre_en TEXT NOT NULL
);
CREATE TABLE sesion_preferencias (
 sesion_id TEXT NOT NULL REFERENCES sesiones(id),
 preferencia_id INTEGER NOT NULL REFERENCES preferencias(id),
 PRIMARY KEY (sesion_id,preferencia_id)
);
CREATE TABLE producto_preferencias (
 producto_id INTEGER NOT NULL REFERENCES productos(id),
 preferencia_id INTEGER NOT NULL REFERENCES preferencias(id),
 PRIMARY KEY (producto_id,preferencia_id)
);
CREATE TABLE disponibilidad_historial (
 id INTEGER PRIMARY KEY,
 producto_id INTEGER NOT NULL REFERENCES productos(id),
 disponible INTEGER NOT NULL CHECK (disponible IN (0,1)),
 registrado_por INTEGER NOT NULL REFERENCES personal(id),
 fecha TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 motivo TEXT NOT NULL
);

CREATE VIEW v_productos_compatibles AS
 SELECT s.id AS sesion_id, p.id AS producto_id, p.revision,
        s.revision_perfil, p.precio_centimos, p.destino
 FROM sesiones s CROSS JOIN productos p
 WHERE s.cierre IS NULL AND s.perfil_completo = 1
 AND p.activo = 1 AND p.disponible = 1 AND p.validado_en IS NOT NULL
 AND EXISTS (SELECT 1 FROM producto_textos t WHERE t.producto_id=p.id AND t.idioma=s.idioma)
 AND NOT EXISTS (
  SELECT 1 FROM sesion_restricciones sr
  WHERE sr.sesion_id=s.id AND NOT EXISTS (
   SELECT 1 FROM producto_restricciones pr
   WHERE pr.producto_id=p.id AND pr.restriccion_id=sr.restriccion_id AND pr.resultado='compatible'))
 AND NOT EXISTS (
  SELECT 1 FROM sesion_restricciones sr JOIN restricciones r ON r.id=sr.restriccion_id
  WHERE sr.sesion_id=s.id AND r.alergeno_id IS NOT NULL AND (
   EXISTS (SELECT 1 FROM producto_alergenos pa WHERE pa.producto_id=p.id AND pa.alergeno_id=r.alergeno_id)
   OR EXISTS (SELECT 1 FROM producto_ingredientes pi JOIN ingrediente_alergenos ia
              ON ia.ingrediente_id=pi.ingrediente_id
              WHERE pi.producto_id=p.id AND ia.alergeno_id=r.alergeno_id)));

CREATE TABLE recomendaciones (
 id INTEGER PRIMARY KEY,
 sesion_id TEXT NOT NULL REFERENCES sesiones(id),
 producto_id INTEGER NOT NULL REFERENCES productos(id),
 revision_producto INTEGER NOT NULL,
 revision_perfil INTEGER NOT NULL,
 posicion INTEGER NOT NULL CHECK (posicion > 0),
 explicacion TEXT NOT NULL CHECK (length(trim(explicacion)) > 0),
 motor TEXT NOT NULL,
 version_reglas TEXT NOT NULL,
 fecha TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 UNIQUE(id,sesion_id,producto_id)
);
CREATE TABLE recomendacion_eventos (
 id INTEGER PRIMARY KEY,
 recomendacion_id INTEGER NOT NULL REFERENCES recomendaciones(id),
 accion TEXT NOT NULL CHECK (accion IN ('mostrada','aceptada','modificada','rechazada')),
 fecha TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE pedidos (
 id TEXT PRIMARY KEY NOT NULL,
 sesion_id TEXT NOT NULL REFERENCES sesiones(id),
 estado TEXT NOT NULL DEFAULT 'borrador' CHECK (estado IN ('borrador','confirmado','enviado','completado')),
 creado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 confirmado_en TEXT,
 UNIQUE(id,sesion_id),
 CHECK ((estado='borrador' AND confirmado_en IS NULL) OR (estado<>'borrador' AND confirmado_en IS NOT NULL))
);
CREATE TABLE pedido_items (
 id INTEGER PRIMARY KEY,
 pedido_id TEXT NOT NULL,
 sesion_id TEXT NOT NULL,
 producto_id INTEGER NOT NULL REFERENCES productos(id),
 cantidad INTEGER NOT NULL CHECK (cantidad > 0),
 precio_unitario_centimos INTEGER NOT NULL CHECK (precio_unitario_centimos >= 0),
 nombre_snapshot TEXT NOT NULL,
 destino TEXT NOT NULL CHECK (destino IN ('cocina','barra')),
 recomendacion_id INTEGER,
 FOREIGN KEY (pedido_id,sesion_id) REFERENCES pedidos(id,sesion_id),
 FOREIGN KEY (recomendacion_id,sesion_id,producto_id) REFERENCES recomendaciones(id,sesion_id,producto_id)
);
-- Bandeja transaccional: se genera al confirmar; el backend entrega y reintenta.
CREATE TABLE comandas (
 id INTEGER PRIMARY KEY,
 pedido_id TEXT NOT NULL REFERENCES pedidos(id),
 destino TEXT NOT NULL CHECK (destino IN ('cocina','barra')),
 clave_idempotencia TEXT NOT NULL UNIQUE,
 estado TEXT NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente','recibida','preparando','lista','entregada')),
 intentos INTEGER NOT NULL DEFAULT 0 CHECK (intentos >= 0),
 creada_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 recibida_en TEXT,
 UNIQUE(pedido_id,destino),
 CHECK ((estado='pendiente' AND recibida_en IS NULL) OR (estado<>'pendiente' AND recibida_en IS NOT NULL))
);
CREATE TABLE llamados_mozo (
 id INTEGER PRIMARY KEY,
 sesion_id TEXT NOT NULL REFERENCES sesiones(id),
 motivo TEXT NOT NULL CHECK (motivo IN ('asistencia','restriccion_incierta','fallo_ia')),
 producto_id INTEGER REFERENCES productos(id),
 estado TEXT NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente','atendido')),
 creado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 atendido_por INTEGER REFERENCES personal(id),
 atendido_en TEXT,
 CHECK ((estado='pendiente' AND atendido_por IS NULL AND atendido_en IS NULL) OR
        (estado='atendido' AND atendido_por IS NOT NULL AND atendido_en IS NOT NULL))
);
CREATE TABLE encuestas (
 sesion_id TEXT PRIMARY KEY NOT NULL REFERENCES sesiones(id),
 satisfaccion INTEGER NOT NULL CHECK (satisfaccion BETWEEN 1 AND 5),
 fecha TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE mediciones_piloto (
 id INTEGER PRIMARY KEY,
 fase TEXT NOT NULL CHECK (fase IN ('linea_base','piloto','post_lanzamiento')),
 fecha TEXT NOT NULL,
 comensales INTEGER NOT NULL CHECK (comensales > 0),
 importe_centimos INTEGER NOT NULL CHECK (importe_centimos >= 0),
 tiempo_pedido_segundos INTEGER NOT NULL CHECK (tiempo_pedido_segundos >= 0),
 consultas_mozo INTEGER NOT NULL CHECK (consultas_mozo >= 0),
 fuente TEXT NOT NULL,
 registrado_por INTEGER NOT NULL REFERENCES personal(id)
);

CREATE INDEX ix_sesiones_acceso ON sesiones(acceso_id);
CREATE INDEX ix_productos_categoria ON productos(categoria_id);
CREATE INDEX ix_recomendaciones_sesion ON recomendaciones(sesion_id,fecha);
CREATE INDEX ix_eventos_recomendacion ON recomendacion_eventos(recomendacion_id,accion);
CREATE INDEX ix_pedidos_sesion ON pedidos(sesion_id);
CREATE INDEX ix_items_pedido ON pedido_items(pedido_id);
CREATE INDEX ix_comandas_estado ON comandas(estado,destino);
CREATE INDEX ix_llamados_estado ON llamados_mozo(estado,creado_en);

CREATE TRIGGER sesion_acceso_activo BEFORE INSERT ON sesiones
BEGIN
 SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM accesos a JOIN puntos_servicio p ON p.id=a.punto_id
 WHERE a.id=NEW.acceso_id AND a.activo=1 AND p.activo=1)
 THEN RAISE(ABORT,'Acceso o punto de servicio inactivo') END;
END;
CREATE TRIGGER validar_catalogo BEFORE UPDATE OF validado_en,validado_por ON productos
WHEN NEW.validado_en IS NOT NULL
BEGIN
 SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM personal WHERE id=NEW.validado_por AND activo=1 AND rol IN ('cocina','administrador'))
 THEN RAISE(ABORT,'Validacion de catalogo requiere cocina o administrador activo') END;
 SELECT CASE WHEN (SELECT count(*) FROM producto_textos WHERE producto_id=NEW.id) <> 2
 OR NOT EXISTS (SELECT 1 FROM producto_ingredientes WHERE producto_id=NEW.id)
 THEN RAISE(ABORT,'Catalogo requiere textos es/en e ingredientes antes de validar') END;
END;
CREATE TRIGGER impedir_producto_prevalidado BEFORE INSERT ON productos WHEN NEW.validado_en IS NOT NULL
BEGIN SELECT RAISE(ABORT,'Cargar datos y despues validar el producto'); END;
CREATE TRIGGER actualizar_disponibilidad AFTER INSERT ON disponibilidad_historial
BEGIN UPDATE productos SET disponible=NEW.disponible WHERE id=NEW.producto_id; END;
CREATE TRIGGER recomendacion_segura BEFORE INSERT ON recomendaciones
BEGIN
 SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM v_productos_compatibles
 WHERE sesion_id=NEW.sesion_id AND producto_id=NEW.producto_id
 AND revision=NEW.revision_producto AND revision_perfil=NEW.revision_perfil)
 THEN RAISE(ABORT,'Recomendacion bloqueada: catalogo, perfil o restricciones no validos') END;
END;
CREATE TRIGGER recomendacion_inmutable BEFORE UPDATE ON recomendaciones
BEGIN SELECT RAISE(ABORT,'Recomendacion historica inmutable'); END;
CREATE TRIGGER recomendacion_no_borrar BEFORE DELETE ON recomendaciones
BEGIN SELECT RAISE(ABORT,'Recomendacion historica inmutable'); END;
CREATE TRIGGER evento_inmutable BEFORE UPDATE ON recomendacion_eventos
BEGIN SELECT RAISE(ABORT,'Evento historico inmutable'); END;
CREATE TRIGGER evento_no_borrar BEFORE DELETE ON recomendacion_eventos
BEGIN SELECT RAISE(ABORT,'Evento historico inmutable'); END;

CREATE TRIGGER pedido_confirmar BEFORE UPDATE OF estado ON pedidos
WHEN OLD.estado='borrador' AND NEW.estado='confirmado'
BEGIN
 SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM pedido_items WHERE pedido_id=OLD.id)
 THEN RAISE(ABORT,'Pedido vacio') END;
 SELECT CASE WHEN EXISTS (SELECT 1 FROM pedido_items i WHERE i.pedido_id=OLD.id AND NOT EXISTS (
 SELECT 1 FROM v_productos_compatibles c JOIN producto_textos t ON t.producto_id=c.producto_id
 JOIN sesiones s ON s.id=c.sesion_id AND s.idioma=t.idioma
 WHERE c.sesion_id=NEW.sesion_id AND c.producto_id=i.producto_id
 AND c.precio_centimos=i.precio_unitario_centimos AND c.destino=i.destino AND t.nombre=i.nombre_snapshot))
 THEN RAISE(ABORT,'Revisar disponibilidad, restricciones, nombre y precio antes de confirmar') END;
END;
CREATE TRIGGER pedido_transicion BEFORE UPDATE ON pedidos
BEGIN
 SELECT CASE WHEN NEW.id<>OLD.id OR NEW.sesion_id<>OLD.sesion_id OR NEW.creado_en<>OLD.creado_en
 OR (OLD.estado<>'borrador' AND NEW.confirmado_en IS NOT OLD.confirmado_en)
 OR NOT (NEW.estado=OLD.estado OR (OLD.estado='borrador' AND NEW.estado='confirmado')
 OR (OLD.estado='confirmado' AND NEW.estado='enviado') OR (OLD.estado='enviado' AND NEW.estado='completado'))
 THEN RAISE(ABORT,'Transicion o modificacion de pedido no permitida') END;
 SELECT CASE WHEN NEW.estado='enviado' AND EXISTS (SELECT 1 FROM comandas WHERE pedido_id=OLD.id AND estado='pendiente')
 THEN RAISE(ABORT,'Falta recepcion de comandas') END;
 SELECT CASE WHEN NEW.estado='completado' AND EXISTS (SELECT 1 FROM comandas WHERE pedido_id=OLD.id AND estado<>'entregada')
 THEN RAISE(ABORT,'Falta entrega de comandas') END;
END;
CREATE TRIGGER pedido_nuevo BEFORE INSERT ON pedidos WHEN NEW.estado<>'borrador'
BEGIN SELECT RAISE(ABORT,'Todo pedido comienza en borrador'); END;
CREATE TRIGGER pedido_no_borrar BEFORE DELETE ON pedidos WHEN OLD.estado<>'borrador'
BEGIN SELECT RAISE(ABORT,'No se puede eliminar un pedido confirmado'); END;
CREATE TRIGGER crear_comandas AFTER UPDATE OF estado ON pedidos
WHEN OLD.estado='borrador' AND NEW.estado='confirmado'
BEGIN
 INSERT INTO comandas(pedido_id,destino,clave_idempotencia)
 SELECT NEW.id,destino,NEW.id || ':' || destino FROM pedido_items WHERE pedido_id=NEW.id GROUP BY destino;
END;
CREATE TRIGGER item_insertar BEFORE INSERT ON pedido_items
WHEN COALESCE((SELECT estado FROM pedidos WHERE id=NEW.pedido_id),'')<>'borrador'
BEGIN SELECT RAISE(ABORT,'Solo se agregan items a borradores'); END;
CREATE TRIGGER item_modificar BEFORE UPDATE ON pedido_items
WHEN COALESCE((SELECT estado FROM pedidos WHERE id=OLD.pedido_id),'')<>'borrador'
 OR COALESCE((SELECT estado FROM pedidos WHERE id=NEW.pedido_id),'')<>'borrador'
BEGIN SELECT RAISE(ABORT,'Solo se modifican items de borradores'); END;
CREATE TRIGGER item_eliminar BEFORE DELETE ON pedido_items
WHEN (SELECT estado FROM pedidos WHERE id=OLD.pedido_id)<>'borrador'
BEGIN SELECT RAISE(ABORT,'Solo se eliminan items de borradores'); END;
CREATE TRIGGER comanda_no_borrar BEFORE DELETE ON comandas
BEGIN SELECT RAISE(ABORT,'Comanda historica inmutable'); END;
CREATE TRIGGER comanda_transicion BEFORE UPDATE ON comandas
BEGIN
 SELECT CASE WHEN NEW.id<>OLD.id OR NEW.pedido_id<>OLD.pedido_id OR NEW.destino<>OLD.destino
 OR NEW.clave_idempotencia<>OLD.clave_idempotencia OR NEW.creada_en<>OLD.creada_en
 OR NOT (NEW.estado=OLD.estado OR (OLD.estado='pendiente' AND NEW.estado='recibida')
 OR (OLD.estado='recibida' AND NEW.estado='preparando') OR (OLD.estado='preparando' AND NEW.estado='lista')
 OR (OLD.estado='lista' AND NEW.estado='entregada'))
 THEN RAISE(ABORT,'Transicion de comanda no permitida') END;
END;

CREATE VIEW v_totales_pedidos AS
 SELECT p.id, p.sesion_id,p.estado,p.confirmado_en,
 COALESCE(sum(i.cantidad*i.precio_unitario_centimos),0) AS total_centimos
 FROM pedidos p LEFT JOIN pedido_items i ON i.pedido_id=p.id GROUP BY p.id;
CREATE VIEW v_aceptacion_recomendaciones AS
 SELECT count(*) AS recomendaciones,
 COALESCE(sum(EXISTS(SELECT 1 FROM recomendacion_eventos e WHERE e.recomendacion_id=r.id AND e.accion='mostrada')),0) AS mostradas,
 COALESCE(sum(EXISTS(SELECT 1 FROM recomendacion_eventos e WHERE e.recomendacion_id=r.id AND e.accion='aceptada')),0) AS aceptadas,
 COALESCE(sum(EXISTS(SELECT 1 FROM recomendacion_eventos e WHERE e.recomendacion_id=r.id AND e.accion='rechazada')),0) AS rechazadas
 FROM recomendaciones r;

-- Invalidar aprobacion al cambiar datos que sustentan las recomendaciones.
CREATE TRIGGER invalidar_producto_textos_insert AFTER INSERT ON producto_textos
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (NEW.producto_id); END;
CREATE TRIGGER invalidar_producto_textos_update AFTER UPDATE ON producto_textos
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (OLD.producto_id,NEW.producto_id); END;
CREATE TRIGGER invalidar_producto_textos_delete AFTER DELETE ON producto_textos
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (OLD.producto_id); END;
CREATE TRIGGER invalidar_producto_ingredientes_insert AFTER INSERT ON producto_ingredientes
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (NEW.producto_id); END;
CREATE TRIGGER invalidar_producto_ingredientes_update AFTER UPDATE ON producto_ingredientes
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (OLD.producto_id,NEW.producto_id); END;
CREATE TRIGGER invalidar_producto_ingredientes_delete AFTER DELETE ON producto_ingredientes
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (OLD.producto_id); END;
CREATE TRIGGER invalidar_producto_alergenos_insert AFTER INSERT ON producto_alergenos
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (NEW.producto_id); END;
CREATE TRIGGER invalidar_producto_alergenos_update AFTER UPDATE ON producto_alergenos
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (OLD.producto_id,NEW.producto_id); END;
CREATE TRIGGER invalidar_producto_alergenos_delete AFTER DELETE ON producto_alergenos
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (OLD.producto_id); END;
CREATE TRIGGER invalidar_producto_restricciones_insert AFTER INSERT ON producto_restricciones
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (NEW.producto_id); END;
CREATE TRIGGER invalidar_producto_restricciones_update AFTER UPDATE ON producto_restricciones
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (OLD.producto_id,NEW.producto_id); END;
CREATE TRIGGER invalidar_producto_restricciones_delete AFTER DELETE ON producto_restricciones
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (OLD.producto_id); END;
CREATE TRIGGER invalidar_producto_preferencias_insert AFTER INSERT ON producto_preferencias
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (NEW.producto_id); END;
CREATE TRIGGER invalidar_producto_preferencias_update AFTER UPDATE ON producto_preferencias
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (OLD.producto_id,NEW.producto_id); END;
CREATE TRIGGER invalidar_producto_preferencias_delete AFTER DELETE ON producto_preferencias
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id IN (OLD.producto_id); END;
CREATE TRIGGER revisar_sesion_restricciones_insert AFTER INSERT ON sesion_restricciones
BEGIN UPDATE sesiones SET revision_perfil=revision_perfil+1 WHERE id IN (NEW.sesion_id); END;
CREATE TRIGGER revisar_sesion_restricciones_update AFTER UPDATE ON sesion_restricciones
BEGIN UPDATE sesiones SET revision_perfil=revision_perfil+1 WHERE id IN (OLD.sesion_id,NEW.sesion_id); END;
CREATE TRIGGER revisar_sesion_restricciones_delete AFTER DELETE ON sesion_restricciones
BEGIN UPDATE sesiones SET revision_perfil=revision_perfil+1 WHERE id IN (OLD.sesion_id); END;
CREATE TRIGGER revisar_sesion_preferencias_insert AFTER INSERT ON sesion_preferencias
BEGIN UPDATE sesiones SET revision_perfil=revision_perfil+1 WHERE id IN (NEW.sesion_id); END;
CREATE TRIGGER revisar_sesion_preferencias_update AFTER UPDATE ON sesion_preferencias
BEGIN UPDATE sesiones SET revision_perfil=revision_perfil+1 WHERE id IN (OLD.sesion_id,NEW.sesion_id); END;
CREATE TRIGGER revisar_sesion_preferencias_delete AFTER DELETE ON sesion_preferencias
BEGIN UPDATE sesiones SET revision_perfil=revision_perfil+1 WHERE id IN (OLD.sesion_id); END;
CREATE TRIGGER invalidar_ingrediente_alergenos_insert AFTER INSERT ON ingrediente_alergenos
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1
WHERE id IN (SELECT producto_id FROM producto_ingredientes WHERE ingrediente_id IN (NEW.ingrediente_id)); END;
CREATE TRIGGER invalidar_ingrediente_alergenos_update AFTER UPDATE ON ingrediente_alergenos
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1
WHERE id IN (SELECT producto_id FROM producto_ingredientes WHERE ingrediente_id IN (OLD.ingrediente_id,NEW.ingrediente_id)); END;
CREATE TRIGGER invalidar_ingrediente_alergenos_delete AFTER DELETE ON ingrediente_alergenos
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1
WHERE id IN (SELECT producto_id FROM producto_ingredientes WHERE ingrediente_id IN (OLD.ingrediente_id)); END;
CREATE TRIGGER invalidar_datos_producto AFTER UPDATE OF categoria_id,tipo,destino,precio_centimos,foto_url,activo ON productos
BEGIN UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1 WHERE id=NEW.id; END;
CREATE TRIGGER revisar_perfil AFTER UPDATE OF idioma,perfil_completo ON sesiones
BEGIN UPDATE sesiones SET revision_perfil=revision_perfil+1 WHERE id=NEW.id; END;
CREATE TRIGGER invalidar_restriccion AFTER UPDATE ON restricciones
BEGIN
 UPDATE productos SET validado_en=NULL,validado_por=NULL,revision=revision+1
 WHERE id IN (SELECT producto_id FROM producto_restricciones WHERE restriccion_id=NEW.id);
 DELETE FROM producto_restricciones WHERE restriccion_id=NEW.id;
 UPDATE sesiones SET revision_perfil=revision_perfil+1 WHERE id IN
 (SELECT sesion_id FROM sesion_restricciones WHERE restriccion_id=NEW.id);
END;
PRAGMA user_version = 1;
COMMIT;
