# Manual de operación de MaidoTouch

Para el personal de Maido (sala, cocina, barra y encargado de carta) y para quien administra el
sistema. MaidoTouch complementa al mozo: **si algo falla, se vuelve al pedido tradicional y el
servicio sigue.**

---

## 1. Reglas que todos deben conocer

1. **La app nunca decide sobre alergias con inteligencia artificial.** Solo muestra como aptos los
   platos que cocina validó como compatibles con la restricción del comensal.
2. **Ante la duda, no se recomienda.** Si un plato no tiene información suficiente para una
   restricción, la app no lo sugiere y le pide al comensal que consulte al mozo.
3. **Un llamado por «restricción alimentaria» se confirma siempre con cocina** antes de recomendar
   o servir el plato.
4. **Un pedido enviado no se cancela desde la app.** Los cambios los gestiona el mozo.
5. **La app no guarda datos personales del comensal**: ni nombres, ni teléfonos, ni correos.

---

## 2. Back-office

Se entra desde `https://<dominio>/admin` con usuario y clave personales. No compartas tu clave. Al
terminar el turno, pulsa **Salir**.

| Rol | Pantallas |
|---|---|
| Cocina | Comandas · cocina, Disponibilidad (platos), Validación de carta |
| Barra | Comandas · barra, Disponibilidad (bebidas) |
| Sala | Llamados (incluye la alerta de comandas sin recibir) |
| Administración | Todas, más Puestos NFC/QR, Cuentas del personal, Métricas y Piloto |

---

## 3. Antes de cada servicio (encargado de carta, cocina y barra)

1. Entrar a **Disponibilidad**.
2. **Desactivar** todo producto que no se vaya a ofrecer hoy, indicando el motivo («se agotó», «sin
   pescado del día»). **Activar** lo que vuelve a la carta.
3. Comprobar que no haya productos con la marca **«Sin validar»** que deberían ofrecerse: esos
   platos no aparecen en la carta hasta que cocina los valide (sección 6).

Durante el servicio, si algo se agota, desactívalo de inmediato: deja de mostrarse y de recomendarse
al instante. Cada cambio queda registrado con tu usuario, la hora y el motivo.

---

## 4. Cocina y barra: comandas

La pantalla **Comandas** se actualiza sola cada pocos segundos. Cada tarjeta indica el puesto (mesa
o barra), los productos y el tiempo transcurrido.

1. Al ver una comanda nueva (borde rojo), pulsa **Recibir**. Esto le confirma al comensal que su
   pedido llegó.
2. Avanza con **En preparación → Lista → Entregada**.

Si la pantalla se apaga o pierde conexión, las comandas **no se pierden**: quedan pendientes y
aparecen al volver. Si pasan más de 2 minutos sin recibirse, sala ve una alerta y puede llevar el
pedido a mano.

---

## 5. Sala: llamados

La pantalla **Llamados** muestra los pedidos de atención en orden de llegada:

| Motivo | Qué hacer |
|---|---|
| Pide atención | Acercarse al puesto. |
| Consulta por restricción alimentaria | El comensal tiene una restricción que el sistema no pudo validar para un plato. **Consultar con cocina** antes de recomendarlo o servirlo. |
| Problema con el asistente | Atender el pedido de forma tradicional. |

Pulsa **Atendido** al terminar. Si arriba aparece **«Comandas sin recibir»**, revisa la pantalla de
cocina o barra o lleva el pedido a mano.

---

## 6. Validación de la carta (cocina y Responsable de Datos)

Ningún plato aparece en la carta ni se recomienda hasta que **cocina lo valida**.

1. El Responsable de Datos carga o actualiza el catálogo (sección 9.4). Todo plato nuevo o modificado
   queda **Pendiente**.
2. En **Validación de carta**, abre el producto y revisa con la receta en mano:
   - textos en español e inglés;
   - ingredientes y alérgenos (contenido, trazas y vía ingrediente);
   - la evaluación de cada restricción (compatible / incompatible / desconocido) y su evidencia;
   - la lista **«Sin evaluación para…»**: para esas restricciones el plato no se recomienda y se
     deriva al mozo.
3. Si todo es correcto, pulsa **Validar producto**.

Cualquier cambio posterior en la receta, los alérgenos, los textos o el precio **anula la
validación automáticamente** y el plato deja de recomendarse hasta revalidarlo.

### Regla especial: dato de alérgeno mal registrado (Plan §8.3)

1. **Primero** desactiva el plato en Disponibilidad.
2. Registra una *Solicitud de cambio* de tipo «Datos críticos» en GitHub.
3. El Responsable de Datos corrige el dato con cocina; Calidad revisa.
4. Cocina revalida el plato y recién entonces se vuelve a activar.

---

## 7. Plan de contingencia (RNF03)

| Situación | Qué hacer |
|---|---|
| Un tag NFC no responde | El comensal escanea el QR que está al lado del tag. |
| Un puesto da problemas | Administración lo **desactiva** en *Puestos NFC/QR*: ese puesto vuelve al pedido tradicional sin afectar a los demás. |
| El asistente de IA no responde | No hay que hacer nada: la app sugiere platos con reglas propias en menos de un segundo. |
| La app o el servidor no funcionan | Pedido tradicional con el mozo en todas las mesas. Avisar al Programador-Tester. |
| No llegan comandas a cocina o barra | Revisar la conexión de la pantalla; sala lleva los pedidos pendientes a mano. |
| Dato de alérgeno incorrecto | Sección 6, regla especial. |

---

## 8. Administración

### 8.1 Puestos NFC/QR

- **Crear puesto**: código (por ejemplo `M12` o `B3`) y tipo. Se generan dos enlaces: uno para
  programar el **tag NFC** y otro para el **QR de respaldo**.
- Programar el tag NFC con el enlace de NFC (registro de tipo URL) con una app de escritura NFC.
- **Imprimir QR de los puestos activos** genera tarjetas bilingües para colocar junto a cada tag.
- **Desactivar puesto** sirve para el despliegue por etapas (Plan §7.1) y ante cualquier falla.

### 8.2 Cuentas del personal

Crea una cuenta por persona con un identificador de usuario, no con su nombre (por ejemplo
`cocina-01`). Clave mínima de 10 caracteres. Al irse alguien del equipo, **desactiva** su cuenta: se
cierran sus sesiones de inmediato. **Cambiar clave** también cierra las sesiones abiertas.

### 8.3 Piloto y Go/No-Go

- **Criterios de Go/No-Go**: pulsa *Revisar ahora* antes de la reunión de Go/No-Go. Toda fila en
  FALLA bloquea el paso a producción; completa además la lista «Verificar a mano».
- **Reporte diario**: resumen del día para enviar al Director de Proyecto durante el piloto (errores,
  latencia del asistente, comandas, derivaciones).
- **Mediciones del piloto**: registra la línea base antes del lanzamiento y luego cada servicio
  medido del piloto (comensales, importe, tiempo medio de pedido, consultas al mozo y fuente del
  dato). La tabla compara las fases por comensal.

---

## 9. Operación técnica (Programador-Tester)

Los comandos se ejecutan en el servidor como el usuario `maidotouch`, con las variables de
`/etc/maidotouch/maidotouch.env`.

### 9.1 Preparar un servidor (una vez)

```bash
sudo ops/preparar-servidor.sh maidotouch.example   # Java, SQLite, Caddy (HTTPS), systemd y respaldos
sudo nano /etc/maidotouch/maidotouch.env           # completar ANTHROPIC_API_KEY y demás valores
```

### 9.2 Desplegar

El pipeline despliega solo: **staging** al integrar en `develop`; **producción** al crear la etiqueta
`vX.Y.Z` y aprobarla en GitHub. A mano:

```bash
scripts/empaquetar.sh                                   # en una máquina de desarrollo
scp build/paquete/maidotouch-<versión>.tar.gz servidor:/tmp/
sudo /opt/maidotouch/ops/desplegar.sh /tmp/maidotouch-<versión>.tar.gz
```

`desplegar.sh` **no se ejecuta en horario de servicio** (12:00–16:30 y 19:00–23:30); respalda la
base, instala la versión junto a la anterior y comprueba que la versión nueva responda. Si no
responde, **vuelve sola a la anterior**. Solo en una emergencia autorizada por el Director:
`FORZAR=1`.

### 9.3 Revertir (objetivo: menos de 30 minutos)

```bash
sudo /opt/maidotouch/ops/revertir.sh                    # código anterior, conserva los datos
sudo /opt/maidotouch/ops/revertir.sh --restaurar-base   # además restaura el último respaldo
```

Restaurar la base descarta lo registrado después del respaldo: solo si la versión nueva dañó los
datos y con autorización del Director.

### 9.4 Catálogo, cuentas, verificación y reportes

```bash
maidotouch importar-catalogo catalogo.json cocina-01   # el JSON lo prepara el Responsable de Datos
maidotouch crear-personal admin-01 administrador       # primera cuenta de administración
maidotouch verificar                                    # Go/No-Go; código de salida 1 si hay FALLA
maidotouch reporte-diario 2026-11-12                    # resumen del día
```

### 9.5 Respaldos y registros

- Respaldo automático diario a las 05:00 (timer `maidotouch-respaldo`), y antes de cada despliegue y
  migración. Se conservan 30 días en `/opt/maidotouch/respaldos`.
- Registros del servicio: `journalctl -u maidotouch --since today`.
- Estado: `curl https://<dominio>/api/salud` (indica versión y si la IA está disponible).

### 9.6 Medir la latencia (QA)

Desde una computadora conectada a 4G y contra **staging**:

```bash
MAIDOTOUCH_CLAVE=... python3 scripts/medir_latencia.py https://staging.example <token-puesto> \
    --repeticiones 30 --cocina cocina-01 --barra barra-01
```

Compara el P95 de activación, carta, asistente y llegada de la comanda con el presupuesto del diseño.
