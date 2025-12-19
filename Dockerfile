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

# Проверяем что JAR создан и находим его имя
RUN echo "=== Checking target directory ===" && \
    ls -la /app/target/ && \
    echo "=== Looking for JAR files ===" && \
    find /app/target -name "*.jar" -type f && \
    JAR_FILE=$(find /app/target -name "*jar-with-dependencies.jar" -type f | head -1) && \
    if [ -z "$JAR_FILE" ]; then \
        echo "ERROR: jar-with-dependencies.jar not found!"; \
        echo "Available JAR files:"; \
        find /app/target -name "*.jar" -type f; \
        exit 1; \
    else \
        echo "✓ Found JAR: $JAR_FILE"; \
        cp "$JAR_FILE" /app/target/app.jar; \
    fi

FROM eclipse-temurin:17-jre
WORKDIR /app

# Копируем JAR файл (используем app.jar созданный в build stage)
COPY --from=build /app/target/app.jar app.jar

# Проверяем что JAR файл существует
RUN if [ ! -f app.jar ]; then \
        echo "ERROR: app.jar not found!"; \
        exit 1; \
    fi && \
    echo "✓ JAR file ready: $(ls -lh app.jar)"

# Создаем директорию data
RUN mkdir -p ./data

CMD ["java", "-Xmx512m", "-Xms256m", "-jar", "app.jar"]

