resources/zookeeper-server-start.sh resources/zookeeper.properties &
sleep 3
resources/kafka-server-start.sh resources/server.properties

# kafka is producing to port 9092