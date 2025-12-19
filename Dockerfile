FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY accountant-bot/pom.xml ./accountant-bot/
RUN mvn dependency:go-offline -B
COPY accountant-bot/src ./accountant-bot/src
RUN mvn clean package -DskipTests -pl accountant-bot -am

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/accountant-bot/target/accountant-bot-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar
COPY accountant-bot/data ./data
CMD ["java", "-Xmx512m", "-Xms256m", "-jar", "app.jar"]

