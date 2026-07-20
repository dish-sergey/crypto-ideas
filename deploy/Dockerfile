# syntax=docker/dockerfile:1

# ---- build: собираем fat-jar в Liberica JDK 25 ----
# Toolchain проекта (build.gradle): Java 25, vendor BellSoft — этот образ ему и
# соответствует, Gradle детектит JDK контейнера как toolchain.
FROM bellsoft/liberica-openjdk-debian:25 AS build
WORKDIR /src

# Слой прогрева: качаем Gradle-дистрибутив и плагины один раз, кэшируется пока
# не менялись build-файлы (сборка зависимостей — на шаге bootJar ниже).
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon help >/dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

# ---- runtime: только JRE, без Gradle и исходников ----
FROM bellsoft/liberica-openjre-debian:25 AS runtime
WORKDIR /app

# Непривилегированный пользователь; /app/data — том с БД (наружу, см. compose)
RUN useradd -u 1000 -m app && mkdir -p /app/data && chown -R app:app /app
COPY --from=build /src/build/libs/*.jar /app/app.jar
USER app

# Относительный data/crypto.db из application.properties → /app/data/crypto.db
VOLUME ["/app/data"]

# Без аргументов = режим планировщика + WS-ликвидации (CliMode).
# Разовые команды: docker compose run --rm app --collect=all
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
