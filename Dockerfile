# Stage 1: Build the application
FROM eclipse-temurin:21-jdk-alpine AS build

# Set working directory
WORKDIR /app

# Copy gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle .

# Download dependencies (cached layer)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Copy source code
COPY src src

# Build the application
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Run the application
FROM eclipse-temurin:21-jre-alpine

# Set working directory
WORKDIR /app

# Create non-root user for security and install su-exec for user switching
RUN addgroup -S spring && adduser -S spring -G spring && apk add --no-cache su-exec

# Copy the built JAR and keystore from build stage
COPY --from=build /app/build/libs/kite-trading-1.0.0.jar app.jar
COPY keystore.p12 keystore.p12

# Create logs directory
RUN mkdir -p /app/logs && chown spring:spring app.jar keystore.p12

# Expose port
EXPOSE 443

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -q --spider http://localhost:443/actuator/health || exit 1

# Ensure logs directory is writable, then run as spring user
ENTRYPOINT ["/bin/sh", "-c", "mkdir -p /app/logs && chmod 777 /app/logs && exec su-exec spring java -jar app.jar"]
