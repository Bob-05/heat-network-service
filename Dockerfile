# ---------- Этап сборки ----------
FROM maven:3.8.6-openjdk-11 AS build
WORKDIR /app

# Сначала копируем только pom.xml — так зависимости закешируются
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Затем исходники
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Этап запуска ----------
FROM eclipse-temurin:11-jre
WORKDIR /app

# Копируем собранный jar
COPY --from=build /app/target/heat-network-service-0.0.1-SNAPSHOT.jar app.jar

# Папки для загруженных файлов и результатов
RUN mkdir -p /app/uploads /app/results

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]