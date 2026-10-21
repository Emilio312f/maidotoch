#!/usr/bin/env bash
# Arranque del contenedor de MaidoTouch: tokens, migraciones, los once servicios y, si se pide, los
# datos de demostración. Si un servicio termina, el contenedor se detiene para que Coolify lo reinicie.
#
# Variables propias del contenedor:
#   MAIDOTOUCH_DEMO=piloto|ficticio   carga el catálogo (catalogo/<valor>.json), cuentas demo y puestos M1, M2, B1
#   MAIDOTOUCH_CLAVE                  clave de las cuentas demo (si falta, se genera una vez en /datos/clave-demo)
set -euo pipefail
cd /app

H=/app/herramientas/bin/maidotouch-herramientas
servicios=(sesion seguridad-alimentaria catalogo adaptador-ia trazabilidad recomendacion pedido atencion piloto bus gateway)

# Tokens de servicio: se generan una vez y quedan en el volumen. Los definidos en Coolify tienen prioridad.
tokens=/datos/tokens.env
if [ ! -f "$tokens" ]; then
  (umask 077 && "$H" tokens > "$tokens")
  echo "Tokens de servicio generados en $tokens"
fi
while IFS='=' read -r nombre valor; do
  [ -n "$nombre" ] && [ -z "${!nombre:-}" ] && export "$nombre=$valor"
done < "$tokens"

"$H" migrar

pids=()
detener() {
  kill "${pids[@]}" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap 'detener; exit 0' TERM INT

for s in "${servicios[@]}"; do
  if [ "$s" = "adaptador-ia" ]; then
    # Solo el Servicio Adaptador de IA recibe las claves del proveedor.
    env -u MAIDOTOUCH_CLAVE "/app/servicios/$s/bin/maidotouch-$s" > >(sed -u "s/^/[$s] /") 2>&1 &
  else
    env -u ANTHROPIC_API_KEY -u MAIDOTOUCH_IA_SERVIDOR_CLAVE -u MAIDOTOUCH_IA_SERVIDOR_URL -u MAIDOTOUCH_CLAVE \
      "/app/servicios/$s/bin/maidotouch-$s" > >(sed -u "s/^/[$s] /") 2>&1 &
  fi
  pids+=($!)
done

if [ -n "${MAIDOTOUCH_DEMO:-}" ]; then
  for _ in $(seq 1 90); do
    curl -fsS http://127.0.0.1:8080/api/salud >/dev/null 2>&1 && break
    sleep 2
  done
  if [ -z "${MAIDOTOUCH_CLAVE:-}" ]; then
    [ -f /datos/clave-demo ] || (umask 077 && head -c 18 /dev/urandom | base64 | tr -d '/+=' > /datos/clave-demo)
    MAIDOTOUCH_CLAVE="$(cat /datos/clave-demo)"
  fi
  MAIDOTOUCH_CLAVE="$MAIDOTOUCH_CLAVE" "$H" demo "/app/catalogo/${MAIDOTOUCH_DEMO}.json" \
    || echo "No se cargaron los datos de demostración (ver el mensaje anterior)"
fi

# Si cualquier servicio termina, se detiene todo: Coolify reinicia el contenedor.
set +e
wait -n "${pids[@]}"
echo "Un servicio terminó; se detiene el contenedor para que se reinicie"
detener
exit 1
