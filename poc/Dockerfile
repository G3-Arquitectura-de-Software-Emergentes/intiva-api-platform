FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/intiva-poc-0.1.0.jar app.jar
USER 10001
ENTRYPOINT ["java","-jar","app.jar"]
