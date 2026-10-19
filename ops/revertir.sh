#!/usr/bin/env bash
# Reversión rápida a la versión anterior (objetivo: menos de 30 minutos, Plan §7.2).
#
#   ops/revertir.sh                          # todos los servicios vuelven a su versión anterior
#   ops/revertir.sh pedido                   # solo el Servicio de Pedido
#   ops/revertir.sh --restaurar-base         # además restaura el último respaldo de la base
#
# Restaurar la base descarta lo registrado después del respaldo (pedidos, recomendaciones):
# hacerlo solo si la versión nueva dañó los datos, con autorización del Director de Proyecto.
set -euo pipefail

base="${MAIDOTOUCH_HOME:-/opt/maidotouch}"
db="${MAIDOTOUCH_DB_PATH:-$base/datos/maidotouch.db}"
gateway="${MAIDOTOUCH_URL_GATEWAY:-http://127.0.0.1:8080}"
todos=(sesion seguridad-alimentaria catalogo adaptador-ia trazabilidad recomendacion pedido atencion piloto bus gateway)

restaurar=0
servicios=()
for a in "$@"; do
  if [ "$a" = "--restaurar-base" ]; then restaurar=1; else servicios+=("$a"); fi
done
[ "${#servicios[@]}" -eq 0 ] && servicios=("${todos[@]}")

if [ "$restaurar" = "1" ]; then
  respaldo="$(ls -1t "$base"/respaldos/*.db 2>/dev/null | head -1)"
  [ -z "$respaldo" ] && { echo "No hay respaldos en $base/respaldos" >&2; exit 1; }
  # Con la base compartida, restaurarla exige detener todos los servicios.
  systemctl stop maidotouch.target
  cp "$db" "$base/respaldos/antes-de-restaurar-$(date -u +%Y%m%dT%H%M%SZ).db"
  rm -f "$db-wal" "$db-shm"
  cp "$respaldo" "$db"
  chown "${MAIDOTOUCH_USUARIO:-maidotouch}:" "$db" 2>/dev/null || true
  echo "Base restaurada desde $respaldo"
fi

for s in "${servicios[@]}"; do
  anterior="$(readlink "$base/servicios/$s.anterior" 2>/dev/null || true)"
  if [ -z "$anterior" ] || [ ! -d "$anterior" ]; then
    echo "Sin versión anterior registrada para $s; se deja como está" >&2
    continue
  fi
  fallida="$(readlink "$base/servicios/$s")"
  ln -sfn "$anterior" "$base/servicios/$s"
  ln -sfn "$fallida" "$base/servicios/$s.fallida"
  if [ "$s" = "gateway" ] && [ -e "$base/web.anterior" ]; then
    ln -sfn "$(readlink "$base/web.anterior")" "$base/web"
  fi
  systemctl restart "maidotouch@$s"
  echo "$s → $(cat "$anterior/VERSION" 2>/dev/null || basename "$anterior")"
done
[ "$restaurar" = "1" ] && systemctl start maidotouch.target

for _ in $(seq 1 "${MAIDOTOUCH_ESPERA_INTENTOS:-45}"); do
  if curl -fsS "$gateway/api/salud" 2>/dev/null | grep -q '"estado":"ok"'; then
    echo "OK: en línea ($(curl -fsS "$gateway/api/salud"))"
    exit 0
  fi
  sleep 2
done
echo "Los servicios no responden: atender con el flujo tradicional del mozo y revisar journalctl -u 'maidotouch@*'" >&2
exit 1
