# ---- Stage 1: build the app ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Copy only the build files first, so downloaded libraries get cached
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline

# Now copy the code and build the runnable .jar file
COPY src src
RUN ./mvnw package -DskipTests

# ---- Stage 2: run the app ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]