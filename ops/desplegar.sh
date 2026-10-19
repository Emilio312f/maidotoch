#!/usr/bin/env bash
# Despliegue en el servidor (staging o producción). Se ejecuta en el servidor como root.
#
#   ops/desplegar.sh /tmp/maidotouch-v1.1.0.tar.gz                 # todos los servicios
#   ops/desplegar.sh /tmp/maidotouch-v1.1.1.tar.gz pedido gateway  # solo esos servicios
#
# Pasos (Plan §7.2 · Diseño v1.1): verifica que no sea horario de servicio, respalda la base, instala
# la versión junto a las anteriores, aplica las migraciones y cambia servicio por servicio, comprobando
# en /api/salud que cada uno responda con la versión nueva. Si alguno no responde, vuelve solo a
# la versión anterior de los servicios cambiados. Para emergencias autorizadas por el Director: FORZAR=1.
set -euo pipefail

paquete="${1:?Uso: desplegar.sh <paquete.tar.gz> [servicio ...]}"
shift
base="${MAIDOTOUCH_HOME:-/opt/maidotouch}"
gateway="${MAIDOTOUCH_URL_GATEWAY:-http://127.0.0.1:8080}"
# Ventanas de servicio de Maido en hora de Lima (HHMM-HHMM, separadas por espacio).
# Definida y vacía desactiva el control (solo staging).
ventanas="${MAIDOTOUCH_VENTANAS_SERVICIO-1200-1630 1900-2330}"
todos=(sesion seguridad-alimentaria catalogo adaptador-ia trazabilidad recomendacion pedido atencion piloto bus gateway)
if [ "$#" -gt 0 ]; then servicios=("$@"); else servicios=("${todos[@]}"); fi

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

for s in "${servicios[@]}"; do
  [[ " ${todos[*]} " == *" $s "* ]] || { echo "Servicio desconocido: $s" >&2; exit 1; }
done

mkdir -p "$base/releases" "$base/datos" "$base/respaldos" "$base/servicios"
version="$(tar -tzf "$paquete" | head -1 | cut -d/ -f1)"
destino="$base/releases/$version"

echo "== Respaldo de la base"
"$(dirname "$0")/respaldar.sh"

if [ ! -d "$destino" ]; then
  echo "== Instalando $version"
  tar -xzf "$paquete" -C "$base/releases"
  chown -R "${MAIDOTOUCH_USUARIO:-maidotouch}:" "$destino" 2>/dev/null || true
fi
esperada="$(cat "$destino/VERSION")"

# Herramientas, migraciones, scripts y web-apps siguen siempre a la versión más nueva instalada.
ln -sfn "$destino/herramientas" "$base/herramientas"
ln -sfn "$destino/migraciones" "$base/migraciones"
cp "$destino"/ops/*.sh "$base/ops/" 2>/dev/null || true

echo "== Migraciones (las migraciones nuevas deben ser compatibles con la versión anterior de cada servicio)"
sudo -u "${MAIDOTOUCH_USUARIO:-maidotouch}" env MAIDOTOUCH_DB_PATH="$base/datos/maidotouch.db" MAIDOTOUCH_MIGRACIONES_DIR="$base/migraciones" \
  "$base/herramientas/bin/maidotouch-herramientas" migrar

if [[ " ${servicios[*]} " == *" gateway "* ]]; then
  [ -e "$base/web" ] && ln -sfn "$(readlink "$base/web")" "$base/web.anterior"
  ln -sfn "$destino/web" "$base/web"
fi

version_en_linea() {
  # Versión con la que responde el servicio $1 según /api/salud del gateway.
  curl -fsS "$gateway/api/salud" 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d['version'] if '$1' == 'gateway' else d['servicios'].get('$1', ''))" 2>/dev/null || true
}

cambiados=()
for s in "${servicios[@]}"; do
  echo "== $s → $esperada"
  actual="$base/servicios/$s"
  [ -e "$actual" ] && ln -sfn "$(readlink "$actual")" "$base/servicios/$s.anterior"
  ln -sfn "$destino/servicios/$s" "$actual"
  cambiados+=("$s")
  systemctl restart "maidotouch@$s"
  listo=0
  for _ in $(seq 1 "${MAIDOTOUCH_ESPERA_INTENTOS:-45}"); do
    if [ "$(version_en_linea "$s")" = "$esperada" ]; then listo=1; break; fi
    sleep 2
  done
  if [ "$listo" != "1" ]; then
    echo "El servicio $s no respondió con $esperada. Volviendo a la versión anterior de: ${cambiados[*]}" >&2
    "$(dirname "$0")/revertir.sh" "${cambiados[@]}"
    exit 1
  fi
done

echo "OK: $esperada en línea para ${servicios[*]}"
curl -fsS "$gateway/api/salud" && echo
# Se conservan las últimas 5 versiones que ningún servicio usa.
en_uso="$(for e in "$base"/servicios/*; do readlink "$e"; done | xargs -n1 dirname | xargs -n1 dirname | sort -u)"
ls -1dt "$base"/releases/* | tail -n +6 | while read -r r; do
  grep -qx "$r" <<<"$en_uso" || rm -rf "$r"
done
