FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY parent-pom.xml ../pom.xml
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -B
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
COPY --from=build /app/target/app.jar app.jar
RUN if [ ! -f app.jar ]; then \
        echo "ERROR: app.jar not found!"; \
        exit 1; \
    fi && \
    echo "✓ JAR file ready: $(ls -lh app.jar)"
RUN mkdir -p ./data
CMD ["java", "-Xmx512m", "-Xms256m", "-jar", "app.jar"]

