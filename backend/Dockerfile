FROM eclipse-temurin:17
WORKDIR /workspace
EXPOSE 8080
ARG JAR_FILE=build/libs/tyler-bot-0.0.1-SNAPSHOT.jar
ADD ${JAR_FILE} app.jar
ADD src/main/resources/application.yaml /workspace/application.yaml
ENTRYPOINT [ "java", "-jar", "app.jar", "-Dspring.profiles.active=prod", "-Dspring.config.location=/workspace/application.yml"]