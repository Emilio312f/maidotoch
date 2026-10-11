#!/usr/bin/env bash
# Respaldo consistente de la base SQLite (en caliente, con la API de respaldo de SQLite).
# Lo usan desplegar.sh y el timer diario maidotouch-respaldo. Conserva 30 días.
set -euo pipefail

base="${MAIDOTOUCH_HOME:-/opt/maidotouch}"
db="${MAIDOTOUCH_DB_PATH:-$base/datos/maidotouch.db}"
destino="$base/respaldos"
mkdir -p "$destino"

if [ ! -f "$db" ]; then
  echo "Sin base en $db: nada que respaldar"
  exit 0
fi

archivo="$destino/maidotouch-$(date -u +%Y%m%dT%H%M%SZ).db"
sqlite3 "$db" ".backup '$archivo'"
# El respaldo queda como un único archivo autocontenido (sin -wal ni -shm).
sqlite3 "$archivo" "PRAGMA journal_mode = DELETE;" >/dev/null
sqlite3 "$archivo" "PRAGMA integrity_check;" | grep -qx ok || { echo "Respaldo corrupto: $archivo" >&2; exit 1; }
rm -f "$archivo-wal" "$archivo-shm"
echo "Respaldo: $archivo"

# Fotos de la carta: no están en la base, se respaldan aparte.
fotos="${MAIDOTOUCH_FOTOS_DIR:-$base/datos/fotos}"
if [ -d "$fotos" ]; then
  tar -czf "$destino/fotos-$(date -u +%Y%m%dT%H%M%SZ).tar.gz" -C "$(dirname "$fotos")" "$(basename "$fotos")"
fi

find "$destino" \( -name 'maidotouch-*.db' -o -name 'fotos-*.tar.gz' \) -mtime +30 -delete
