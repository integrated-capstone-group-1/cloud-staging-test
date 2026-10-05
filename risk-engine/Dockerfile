FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY risk-engine/target/risk-engine-*.jar app.jar
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "app.jar"]
