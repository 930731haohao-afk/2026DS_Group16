# Optional packaging; requires a Docker environment to verify/build.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src/main/java src/main/java
COPY scripts/build.sh scripts/build.sh
RUN sh scripts/build.sh

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/classes build/classes
COPY web web
COPY data data
ENV APP_HOST=0.0.0.0 APP_PORT=8080 APP_ALLOWED_HOSTS=localhost,127.0.0.1
USER 10001:10001
EXPOSE 8080
CMD ["java", "-cp", "build/classes", "edu.nccu.ai.App"]
