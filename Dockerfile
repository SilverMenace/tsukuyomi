# ---- dashboard stage: build the React app into static files ----
FROM node:24-alpine AS dashboard
WORKDIR /src/dashboard
COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY dashboard/ ./
RUN npm run build

# ---- build stage: compile and package the backend ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src/backend
# Copy the build definition first so dependency downloads are cached
# as a layer and only re-run when pom.xml changes.
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY backend/src src
# Spring Boot serves anything in resources/static, so the dashboard ships
# inside the same jar as the API.
COPY --from=dashboard /src/dashboard/dist src/main/resources/static
RUN ./mvnw -B -q -DskipTests package

# ---- run stage: small image with only a Java runtime and the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 tsukuyomi
COPY --from=build /src/backend/target/backend-*.jar app.jar
USER tsukuyomi
# Tuned for a 512 MB / fractional-CPU container:
#   MaxRAMPercentage  heap may use half of the container's memory
#   UseSerialGC       lowest-overhead collector for a small heap and one core
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
