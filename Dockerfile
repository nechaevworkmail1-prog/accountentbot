FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Копируем родительский pom.xml (создаем его в родительской директории)
COPY parent-pom.xml ../pom.xml

# Копируем pom.xml проекта
COPY pom.xml .

# Копируем исходный код
COPY src ./src

# Собираем проект
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre
WORKDIR /app

# Копируем собранный JAR
COPY --from=build /app/target/accountant-bot-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar

# Создаем директорию data
RUN mkdir -p ./data

CMD ["java", "-Xmx512m", "-Xms256m", "-jar", "app.jar"]

