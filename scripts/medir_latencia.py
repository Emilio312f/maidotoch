#!/usr/bin/env python3
"""Mide la latencia P95 de MaidoTouch contra el presupuesto del Diseño Arquitectónico (§9.2).

Correr desde una computadora conectada a una red 4G (por ejemplo, compartiendo datos de un celular)
contra staging, con un puesto y cuentas de prueba:

    MAIDOTOUCH_COCINA_CLAVE=... python3 scripts/medir_latencia.py https://staging.example <token-puesto> \
        --repeticiones 30 --cocina cocina-01 --barra barra-01

Crea sesiones, consultas y pedidos reales en la base: usar staging, nunca producción.
Sale con código 1 si alguna operación supera su meta.
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

METAS_MS = {
    "Activación NFC/QR y carga inicial": 2500,
    "Catálogo con disponibilidad": 1500,
    "Respuesta completa del asistente": 3000,
    "Pedido confirmado hasta cocina/barra": 2000,
}


def pedir(base, metodo, ruta, cuerpo=None, cabeceras=None, crudo=False):
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    req = urllib.request.Request(base + ruta, data=datos, method=metodo, headers=dict(cabeceras or {}))
    if datos is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        contenido = r.read()
        return contenido if crudo else (json.loads(contenido) if contenido else None)


def p95(valores):
    ordenados = sorted(valores)
    return ordenados[max(0, -(-len(ordenados) * 95 // 100) - 1)]


def cronometrar(funcion):
    inicio = time.perf_counter()
    resultado = funcion()
    return (time.perf_counter() - inicio) * 1000, resultado


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("base", help="URL base, p. ej. https://staging.example")
    parser.add_argument("token", help="token de un puesto de prueba (lo que va después de /m/)")
    parser.add_argument("--repeticiones", type=int, default=20)
    parser.add_argument("--consulta", default="algo fresco para compartir")
    parser.add_argument("--cocina", help="usuario de cocina para medir la llegada de la comanda")
    parser.add_argument("--barra", help="usuario de barra para medir la llegada de la comanda")
    args = parser.parse_args()
    base = args.base.rstrip("/")

    tokens_personal = {}
    for destino, usuario in (("cocina", args.cocina), ("barra", args.barra)):
        if usuario:
            clave = os.environ.get(f"MAIDOTOUCH_{destino.upper()}_CLAVE") or os.environ.get("MAIDOTOUCH_CLAVE")
            if not clave:
                sys.exit(f"Define MAIDOTOUCH_{destino.upper()}_CLAVE para medir comandas de {destino}")
            tokens_personal[destino] = pedir(base, "POST", "/api/backoffice/login", {"identificador": usuario, "clave": clave})["token"]

    tiempos = {nombre: [] for nombre in METAS_MS}
    motores = {}

    for i in range(args.repeticiones):
        # Activación: página inicial y sus recursos + creación de la sesión.
        def activar():
            html = pedir(base, "GET", f"/m/{args.token}", crudo=True).decode()
            for recurso in re.findall(r'(?:src|href)="(/assets/[^"]+)"', html):
                pedir(base, "GET", recurso, crudo=True)
            return pedir(base, "POST", "/api/sesiones", {"token": args.token, "idioma": "es"})["sesionId"]

        ms, sesion = cronometrar(activar)
        tiempos["Activación NFC/QR y carga inicial"].append(ms)
        cab = {"X-Sesion": sesion}
        pedir(base, "PUT", "/api/sesion/perfil", {"restricciones": [], "preferencias": []}, cab)

        ms, carta = cronometrar(lambda: pedir(base, "GET", "/api/sesion/carta", cabeceras=cab))
        tiempos["Catálogo con disponibilidad"].append(ms)

        ms, respuesta = cronometrar(lambda: pedir(base, "POST", "/api/sesion/asistente", {"consulta": args.consulta}, cab))
        tiempos["Respuesta completa del asistente"].append(ms)
        motores[respuesta.get("motor")] = motores.get(respuesta.get("motor"), 0) + 1

        if tokens_personal:
            pedible = next(
                (p["producto"] for p in carta["productos"]
                 if p.get("aptitud") == "apto" and p["producto"]["disponible"] and p["producto"]["destino"] in tokens_personal),
                None,
            )
            if pedible:
                pedir(base, "POST", "/api/sesion/pedido/items", {"productoId": pedible["id"]}, cab)
                destino = pedible["destino"]
                bo = {"Authorization": "Bearer " + tokens_personal[destino]}
                inicio = time.perf_counter()
                pedido = pedir(base, "POST", "/api/sesion/pedido/confirmar", {"confirmo": True}, cab)
                while time.perf_counter() - inicio < 10:
                    comandas = pedir(base, "GET", f"/api/backoffice/comandas?destino={destino}", cabeceras=bo)
                    comanda = next((c for c in comandas if c["pedidoId"] == pedido["id"]), None)
                    if comanda:
                        tiempos["Pedido confirmado hasta cocina/barra"].append((time.perf_counter() - inicio) * 1000)
                        # Se recibe para no dejar la comanda pendiente en la pantalla de staging.
                        pedir(base, "POST", f"/api/backoffice/comandas/{comanda['id']}/estado", {"estado": "recibida"}, bo)
                        break
                    time.sleep(0.1)
        pedir(base, "POST", "/api/sesion/cerrar", cabeceras=cab)
        print(f"  repetición {i + 1}/{args.repeticiones}", file=sys.stderr)

    print(f"\nLatencia P95 en {args.repeticiones} repeticiones contra {base}")
    print(f"Motor del asistente: {motores}  (para medir con IA, el proveedor debe estar configurado)\n")
    fallas = 0
    for nombre, meta in METAS_MS.items():
        valores = tiempos[nombre]
        if not valores:
            print(f"  {'—':>8}   ≤ {meta:>5} ms   SIN DATOS  {nombre}")
            continue
        valor = p95(valores)
        cumple = valor <= meta
        fallas += 0 if cumple else 1
        print(f"  {valor:>8.0f} ms ≤ {meta:>5} ms   {'OK   ' if cumple else 'FALLA'}  {nombre}")
    sys.exit(1 if fallas else 0)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as e:
        sys.exit(f"Error HTTP {e.code} en {e.url}: {e.read().decode()[:300]}")
