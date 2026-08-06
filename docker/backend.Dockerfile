FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY backend ./backend
WORKDIR /workspace/backend
RUN mvn -pl app -am -DskipTests package

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=build /workspace/backend/app/target/app-0.0.1-SNAPSHOT.jar app.jar

ENV SERVER_PORT=8081 \
    ATTACHMENT_ROOT=/app/data/attachments

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
