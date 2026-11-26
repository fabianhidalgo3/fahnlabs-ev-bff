# syntax=docker/dockerfile:1.7
#
# Production image of the BFF (Spring Boot) serving the compiled Angular front.
# Build from the repository root (the context must include fahnlabs-ev-web/ and fahnlabs-ev-bff/):
#   docker build -f fahnlabs-ev-bff/Dockerfile -t emob/bff .
# Run (configuration by environment variables; no secrets are baked into the image):
#   docker run -p 9000:9000 -e MOTOR_URL=http://host.docker.internal:9200 -e MOTOR_TOKEN=... emob/bff

# ---- 1. Front: npm ci + production build -> /front/dist/front/browser -------------------------------
FROM node:24-bookworm-slim AS front
WORKDIR /front
COPY fahnlabs-ev-web/package.json fahnlabs-ev-web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY fahnlabs-ev-web/ ./
RUN npx ng build --configuration production

# ---- 2. BFF: Maven package -> /bff/target/bff-*.jar ----------------------------------------------------
FROM eclipse-temurin:25-jdk AS bff
WORKDIR /bff
COPY fahnlabs-ev-bff/.mvn/ .mvn/
COPY fahnlabs-ev-bff/mvnw fahnlabs-ev-bff/pom.xml ./
# mvnw may arrive with CRLF line endings from a Windows checkout
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline || true
COPY fahnlabs-ev-bff/src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package \
    && cp target/bff-*.jar /bff/app.jar

# ---- 3. Runtime ----------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=bff --chown=app:app /bff/app.jar /app/app.jar
COPY --from=front --chown=app:app /front/dist/front/browser/ /app/front/

ENV PORT=9000 \
    FRONT_DIR=/app/front \
    MOTOR_URL=http://host.docker.internal:9200 \
    OCPP_PUBLIC_PORT=9100 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=25 -XX:+ExitOnOutOfMemoryError"

USER app
EXPOSE 9000
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
