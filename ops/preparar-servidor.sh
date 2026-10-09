#!/usr/bin/env bash
# Preparación única de un servidor Debian/Ubuntu para staging o producción (Plan §5).
# Ejecutar como root:  ops/preparar-servidor.sh maidotouch.example
set -euo pipefail

dominio="${1:?Uso: preparar-servidor.sh <dominio>}"
raiz="$(cd "$(dirname "$0")/.." && pwd)"

apt-get update
apt-get install -y openjdk-21-jre-headless sqlite3 curl caddy

id maidotouch >/dev/null 2>&1 || useradd --system --home /opt/maidotouch --shell /usr/sbin/nologin maidotouch
mkdir -p /opt/maidotouch/{releases,datos,respaldos,ops} /etc/maidotouch
cp "$raiz"/ops/*.sh /opt/maidotouch/ops/
chmod 750 /opt/maidotouch/ops/*.sh
chown -R maidotouch: /opt/maidotouch/datos /opt/maidotouch/respaldos

if [ ! -f /etc/maidotouch/maidotouch.env ]; then
  cp "$raiz/.env.example" /etc/maidotouch/maidotouch.env
  sed -i "s#^MAIDOTOUCH_URL_PUBLICA=.*#MAIDOTOUCH_URL_PUBLICA=https://$dominio#" /etc/maidotouch/maidotouch.env
  echo "Completa /etc/maidotouch/maidotouch.env (ANTHROPIC_API_KEY, etc.)."
fi
chown root:maidotouch /etc/maidotouch/maidotouch.env
chmod 640 /etc/maidotouch/maidotouch.env

cp "$raiz"/ops/maidotouch.service "$raiz"/ops/maidotouch-respaldo.service "$raiz"/ops/maidotouch-respaldo.timer /etc/systemd/system/
sed "s/maidotouch.example/$dominio/" "$raiz/ops/Caddyfile" > /etc/caddy/Caddyfile
systemctl daemon-reload
systemctl enable maidotouch maidotouch-respaldo.timer
systemctl start maidotouch-respaldo.timer
systemctl reload caddy

echo "Servidor listo. Siguiente paso: ops/desplegar.sh <paquete.tar.gz> y crear las cuentas con"
echo "  sudo -u maidotouch env \$(grep -v '^#' /etc/maidotouch/maidotouch.env | xargs) MAIDOTOUCH_DB_PATH=/opt/maidotouch/datos/maidotouch.db /opt/maidotouch/actual/bin/maidotouch crear-personal admin-01 administrador"
