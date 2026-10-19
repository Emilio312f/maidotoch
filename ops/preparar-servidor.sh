#!/usr/bin/env bash
# Preparación única de un servidor Debian/Ubuntu para staging o producción (Plan §5 · Diseño v1.1).
# Ejecutar como root:  ops/preparar-servidor.sh maidotouch.example
#
# Crea el usuario y los directorios, los tokens de servicio, el certificado del TLS interno entre
# servicios y las unidades systemd (una por servicio, agrupadas en maidotouch.target).
set -euo pipefail

dominio="${1:?Uso: preparar-servidor.sh <dominio>}"
raiz="$(cd "$(dirname "$0")/.." && pwd)"
etc=/etc/maidotouch
servicios=(sesion seguridad-alimentaria catalogo adaptador-ia trazabilidad recomendacion pedido atencion piloto bus)

apt-get update
apt-get install -y openjdk-21-jre-headless sqlite3 curl caddy python3

id maidotouch >/dev/null 2>&1 || useradd --system --home /opt/maidotouch --shell /usr/sbin/nologin maidotouch
mkdir -p /opt/maidotouch/{releases,datos,respaldos,ops,servicios} "$etc"
cp "$raiz"/ops/*.sh /opt/maidotouch/ops/
chmod 750 /opt/maidotouch/ops/*.sh
chown -R maidotouch: /opt/maidotouch/datos /opt/maidotouch/respaldos

# TLS entre servicios (§9): certificado propio para 127.0.0.1, solo lo usan los servicios internos.
if [ ! -f "$etc/tls-interno.p12" ]; then
  clave_tls="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
  keytool -genkeypair -alias maidotouch -keyalg EC -groupname secp256r1 -validity 3650 \
    -dname "CN=maidotouch-interno" -ext "SAN=ip:127.0.0.1,dns:localhost" \
    -storetype PKCS12 -keystore "$etc/tls-interno.p12" -storepass "$clave_tls" -keypass "$clave_tls" >/dev/null
  echo "MAIDOTOUCH_TLS_CLAVE=$clave_tls" > "$etc/.tls-clave"
fi

if [ ! -f "$etc/comun.env" ]; then
  {
    echo "# Generado por preparar-servidor.sh. Solo root y maidotouch lo leen."
    grep -E '^MAIDOTOUCH_(URL_PUBLICA|SESION_HORAS|PERSONAL_SESION_HORAS|IA_LIMITE_CONSULTAS|RECOMENDACIONES_MAX|IA_TIMEOUT_MS|LIMITE_IP_MINUTO|LIMITE_SESION_MINUTO)=' "$raiz/.env.example" \
      | sed "s#^MAIDOTOUCH_URL_PUBLICA=.*#MAIDOTOUCH_URL_PUBLICA=https://$dominio#"
    echo "MAIDOTOUCH_TLS_ALMACEN=$etc/tls-interno.p12"
    cat "$etc/.tls-clave"
    # El gateway queda en HTTP local detrás de Caddy; los demás servicios hablan entre sí por TLS.
    echo "MAIDOTOUCH_URL_GATEWAY=http://127.0.0.1:8080"
    puerto=8101
    for s in "${servicios[@]}"; do
      echo "MAIDOTOUCH_URL_$(echo "$s" | tr 'a-z-' 'A-Z_')=https://127.0.0.1:$puerto"
      puerto=$((puerto + 1))
    done
    for s in gateway "${servicios[@]}"; do
      echo "MAIDOTOUCH_TOKEN_$(echo "$s" | tr 'a-z-' 'A-Z_')=$(head -c 32 /dev/urandom | base64 | tr -d '/+=')"
    done
  } > "$etc/comun.env"
  echo "Creado $etc/comun.env con tokens de servicio nuevos."
fi
if [ ! -f "$etc/adaptador-ia.env" ]; then
  printf '# Solo el Servicio Adaptador de IA conoce la clave del proveedor.\nANTHROPIC_API_KEY=\nMAIDOTOUCH_IA_MODELO=claude-opus-5-5\n' > "$etc/adaptador-ia.env"
  echo "Completa ANTHROPIC_API_KEY en $etc/adaptador-ia.env."
fi
chown root:maidotouch "$etc"/*.env "$etc/tls-interno.p12"
chmod 640 "$etc"/*.env "$etc/tls-interno.p12"
rm -f "$etc/.tls-clave"

cp "$raiz"/ops/maidotouch@.service "$raiz"/ops/maidotouch.target "$raiz"/ops/maidotouch-respaldo.service "$raiz"/ops/maidotouch-respaldo.timer /etc/systemd/system/
sed "s/maidotouch.example/$dominio/" "$raiz/ops/Caddyfile" > /etc/caddy/Caddyfile
systemctl daemon-reload
systemctl enable maidotouch.target maidotouch-respaldo.timer
systemctl start maidotouch-respaldo.timer
systemctl reload caddy

echo "Servidor listo. Siguiente paso: ops/desplegar.sh <paquete.tar.gz> y crear la primera cuenta con"
echo "  sudo -u maidotouch env \$(grep -v '^#' $etc/comun.env | xargs) /opt/maidotouch/herramientas/bin/maidotouch-herramientas crear-personal admin-01 administrador"
