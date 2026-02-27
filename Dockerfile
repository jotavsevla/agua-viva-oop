FROM maven:3.9.9-eclipse-temurin-25 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY . .
RUN mvn -B -DskipTests -DincludeScope=runtime dependency:copy-dependencies compile

FROM eclipse-temurin:25-jdk

WORKDIR /app

COPY --from=build /workspace/target/classes /app/classes
COPY --from=build /workspace/target/dependency /app/dependency

EXPOSE 8082

ENTRYPOINT ["java", "-cp", "/app/classes:/app/dependency/*", "com.aguaviva.App", "api"]
