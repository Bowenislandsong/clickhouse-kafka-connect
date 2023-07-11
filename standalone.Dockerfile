# build container to build using gradle
FROM gradle:7.4.2-jdk17 AS BUILDER
WORKDIR /usr/app/
COPY . . 
RUN ./gradlew build -x test

# Package stage
# FROM amazoncorretto:11.0.19
FROM debian:latest
ENV ARTIFACT_NAME=*.jar
ENV APP_HOME=/usr/app/
ENV KAFKA_V=kafka_2.13-3.4.0

WORKDIR $APP_HOME
COPY --from=BUILDER ${APP_HOME}build/libs/$ARTIFACT_NAME .
COPY --from=BUILDER ${APP_HOME}deploy/dev_standalone/resources/*.properties .
RUN apt update && apt install -y curl openjdk-17-jdk openjdk-17-jre && curl https://dlcdn.apache.org/kafka/3.4.0/${KAFKA_V}.tgz -o ${KAFKA_V} && mkdir kafka && tar -xzf ${KAFKA_V}
EXPOSE 8123 8083
ENTRYPOINT exec ${APP_HOME}${KAFKA_V}/bin/connect-standalone.sh $APP_HOME/connect-standalone.properties $APP_HOME/connect-console-sink.properties
