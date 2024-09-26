FROM eclipse-temurin:21
WORKDIR /app
EXPOSE 8080
ARG JAR_FILE=build/libs/peep-bot-0.0.1-SNAPSHOT.jar
ADD ${JAR_FILE} app.jar
ADD src/main/resources/* /app/
ENTRYPOINT [ "java", "-jar", "app.jar", "-Dspring.profiles.active=prod"]