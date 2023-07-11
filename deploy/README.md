# Deploy Example
We deploy Kafka, connector, and Clickhouse locally via docker-compose. 
We show dataflow with Kafka performance test pushing 200K msgs via connector to Clickhouse.
We present both stand alone mode and distributed mode with single instances of each container.
Connector docker image is found at [connector.Dockerfile](../connector.Dockerfile) which builds the connector from source code with applied changes. 
Build the connector image and use it in [docker-compose.yml](../docker-compose.yml), replace the image in `connector`.

## Standalone

The standalone dev environment creates instances in a single container including kafka, connector and clickhouse. No zookeeper is needed in ther case for clickhouse. 

## Distributed

This distributed mode uses [docker-compose.yml](../docker-compose.yml) to bring up individual containers and creates a sample connector instance to push msgs under `github` topic. 
A table name 'github' is created for testing purposes. This table needs to be created before connector comes up. (this logic might need to be fixed in connector.)
- Build the connector image (e.g.: `docker build -f connector.Dockerfile -t connector:latest .`)
- docker-compose up

## Test Dataflow

Use Kafka to create 200K msgs under topic `github` and push them via connector to clickhouse in table `github`. 
- Build the connector image (e.g.: `docker build -f connector.Dockerfile -t connector:latest .`)
- docker-compose up
- wait till connector is ready (e.g.: `docker exec -it connector curl localhost:8083/connectors/kafka-clickhouse/status` returns ```{"name":"kafka-clickhouse","connector":{"state":"RUNNING","worker_id":"connector:8083"},"tasks":[{"id":0,"state":"RUNNING","worker_id":"connector:8083"}],"type":"sink"```)
- ./deploy/test/live-ingestion.sh
- Check the number of lines ingested in table `docker exec -it clickhouse /bin/clickhouse-client -q "select count() from github;"` (should return a live number <= 200k)
