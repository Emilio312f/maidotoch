# MaidoTouch en un contenedor (Coolify, staging). Los once servicios del Diseño v1.1 corren como
# procesos separados dentro del contenedor; solo se expone el API Gateway (8080). El HTTPS y el
# dominio los pone el proxy de Coolify.
#
#   docker build -t maidotouch .
#   docker run -p 8080:8080 -v maidotouch-datos:/datos -e MAIDOTOUCH_URL_PUBLICA=https://staging.example maidotouch

# ---- 1. Web-apps (comensal y back-office) ----
FROM node:22-bookworm-slim AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. Servicios (Kotlin) ----
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY backend/ backend/
COPY tests/build.gradle.kts tests/build.gradle.kts
RUN ./gradlew --no-daemon -q installDist

# ---- 3. Imagen final ----
FROM eclipse-temurin:21-jre
ARG VERSION=staging
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --home /app --shell /usr/sbin/nologin maidotouch \
    && mkdir -p /datos && chown maidotouch: /datos

WORKDIR /app
COPY --from=backend /src/build/gateway/install/maidotouch-gateway servicios/gateway
COPY --from=backend /src/build/sesion/install/maidotouch-sesion servicios/sesion
COPY --from=backend /src/build/catalogo/install/maidotouch-catalogo servicios/catalogo
COPY --from=backend /src/build/seguridad-alimentaria/install/maidotouch-seguridad-alimentaria servicios/seguridad-alimentaria
COPY --from=backend /src/build/recomendacion/install/maidotouch-recomendacion servicios/recomendacion
COPY --from=backend /src/build/adaptador-ia/install/maidotouch-adaptador-ia servicios/adaptador-ia
COPY --from=backend /src/build/pedido/install/maidotouch-pedido servicios/pedido
COPY --from=backend /src/build/atencion/install/maidotouch-atencion servicios/atencion
COPY --from=backend /src/build/trazabilidad/install/maidotouch-trazabilidad servicios/trazabilidad
COPY --from=backend /src/build/piloto/install/maidotouch-piloto servicios/piloto
COPY --from=backend /src/build/bus/install/maidotouch-bus servicios/bus
COPY --from=backend /src/build/herramientas/install/maidotouch-herramientas herramientas
COPY --from=web /src/frontend/dist/comensal web/comensal
COPY --from=web /src/frontend/dist/backoffice web/backoffice
COPY db/migrations migraciones
COPY db/catalogo catalogo
COPY ops/docker/iniciar.sh iniciar.sh
RUN echo "$VERSION" > VERSION && chmod 755 iniciar.sh

ENV MAIDOTOUCH_DB_PATH=/datos/maidotouch.db \
    MAIDOTOUCH_FOTOS_DIR=/datos/fotos \
    MAIDOTOUCH_MIGRACIONES_DIR=/app/migraciones \
    MAIDOTOUCH_WEB_COMENSAL_DIR=/app/web/comensal \
    MAIDOTOUCH_WEB_BACKOFFICE_DIR=/app/web/backoffice \
    MAIDOTOUCH_URL_GATEWAY=http://0.0.0.0:8080 \
    JAVA_OPTS="-Xms32m -Xmx192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.awt.headless=true"

USER maidotouch
VOLUME /datos
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/salud || exit 1
ENTRYPOINT ["/app/iniciar.sh"]
