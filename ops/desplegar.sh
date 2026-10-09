#!/usr/bin/env bash
# Despliegue en el servidor (staging o producción). Se ejecuta en el servidor como root.
#
#   ops/desplegar.sh /tmp/maidotouch-v1.0.0.tar.gz
#
# Pasos (Plan §7.2): verifica que no sea horario de servicio, respalda la base, instala la versión
# nueva junto a la anterior, reinicia y comprueba /api/salud. Si la versión nueva no responde,
# vuelve sola a la anterior. Para emergencias autorizadas por el Director: FORZAR=1.
set -euo pipefail

paquete="${1:?Uso: desplegar.sh <paquete.tar.gz>}"
base="${MAIDOTOUCH_HOME:-/opt/maidotouch}"
servicio="${MAIDOTOUCH_SERVICIO:-maidotouch}"
puerto="${MAIDOTOUCH_PUERTO:-8080}"
# Ventanas de servicio de Maido en hora de Lima (HHMM-HHMM, separadas por espacio).
# Definida y vacía desactiva el control (solo staging).
ventanas="${MAIDOTOUCH_VENTANAS_SERVICIO-1200-1630 1900-2330}"

en_servicio() {
  local ahora inicio fin
  ahora="$(TZ=America/Lima date +%H%M)"
  for v in $ventanas; do
    inicio="${v%-*}"; fin="${v#*-}"
    if [ "$ahora" -ge "$inicio" ] && [ "$ahora" -lt "$fin" ]; then return 0; fi
  done
  return 1
}

if en_servicio && [ "${FORZAR:-0}" != "1" ]; then
  echo "Horario de servicio: no se despliega durante almuerzo ni cena (Plan §7.2). Usa FORZAR=1 solo en emergencias." >&2
  exit 1
fi

mkdir -p "$base/releases" "$base/datos" "$base/respaldos"
version="$(tar -tzf "$paquete" | head -1 | cut -d/ -f1)"
destino="$base/releases/$version"
if [ -e "$destino" ]; then
  echo "La versión $version ya está instalada en $destino" >&2
  exit 1
fi

echo "== Respaldo de la base"
"$(dirname "$0")/respaldar.sh"

echo "== Instalando $version"
tar -xzf "$paquete" -C "$base/releases"
anterior="$(readlink "$base/actual" 2>/dev/null || true)"
[ -n "$anterior" ] && ln -sfn "$anterior" "$base/anterior"
ln -sfn "$destino" "$base/actual"
chown -R "${MAIDOTOUCH_USUARIO:-maidotouch}:" "$destino" 2>/dev/null || true

echo "== Reiniciando $servicio"
systemctl restart "$servicio"

esperada="$(cat "$destino/VERSION")"
for _ in $(seq 1 "${MAIDOTOUCH_ESPERA_INTENTOS:-45}"); do
  # Solo cuenta si responde la versión recién instalada, no un proceso anterior que siga vivo.
  if curl -fsS "http://127.0.0.1:$puerto/api/salud" 2>/dev/null | grep -q "\"version\":\"$esperada\""; then
    echo "OK: $version en línea ($(curl -fsS "http://127.0.0.1:$puerto/api/salud"))"
    # Se conservan las últimas 5 versiones.
    ls -1dt "$base"/releases/* | tail -n +6 | xargs -r rm -rf
    exit 0
  fi
  sleep 2
done

echo "La versión $version no respondió. Volviendo a la anterior." >&2
if [ -n "$anterior" ]; then
  "$(dirname "$0")/revertir.sh"
fi
exit 1
