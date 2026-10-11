FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY orchestrator-service/target/orchestrator-service-*.jar app.jar
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "app.jar"]

