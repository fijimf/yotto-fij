FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src src
RUN ./mvnw package -DskipTests -B

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build /app/target/deepfij-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
# --enable-native-access: the ONNX runtime loads a JNI library; JDK 24+ warns per
# module without this grant (JEP 472). Harmless, but keeps the startup log clean.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
