# build container to build using gradle
FROM gradle:7.4.2-jdk17 AS BUILDER
WORKDIR /usr/app/
COPY . . 
RUN ./gradlew build -x test

# Package stage
FROM amazoncorretto:11.0.19
ENV ARTIFACT_NAME=*.jar
ENV APP_HOME=/usr/app/

WORKDIR $APP_HOME
COPY --from=BUILDER $APP_HOME/build/libs/$ARTIFACT_NAME .
    
EXPOSE 8080
ENTRYPOINT exec java -jar ${ARTIFACT_NAME}