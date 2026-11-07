# Build context: backend; build jars with JDK 27 + ./mvnw package before docker build.
FROM eclipse-temurin:27-jre
WORKDIR /opt/quest
RUN groupadd --system quest && useradd --system --gid quest --no-create-home quest
COPY --chown=quest:quest api-java/target/api-java-0.1.0.jar /opt/quest/app.jar
USER quest:quest
ENV SPRING_PROFILES_ACTIVE=deployment
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/quest/app.jar"]
