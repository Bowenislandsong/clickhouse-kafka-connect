# build container to build using gradle
FROM gradle:7.4.2-jdk17 AS BUILDER
WORKDIR /usr/app/
COPY . . 
RUN ./gradlew build -x test

FROM confluentinc/cp-kafka-connect:7.1.0-1-ubi8
ENV ARTIFACT_NAME=*.jar
ENV APP_HOME=/usr/app/

COPY --from=BUILDER ${APP_HOME}build/libs/$ARTIFACT_NAME /usr/share/connectors/

ENV CONNECT_PLUGIN_PATH: "/usr/share/java,/usr/share/connectors"
