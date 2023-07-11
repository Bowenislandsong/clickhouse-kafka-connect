FROM debian:latest AS DEV_new
WORKDIR /clickhouse-kafka-connector
COPY . ${WORKDIR}
CMD export DIR=/clickhouse-kafka-connector/deploy/dev_standalone && ${DIR}/clickhouse.sh & ${DIR}/kafka.sh & ${DIR}/connector.sh &
