KAFKA_HEAP_OPTS="-Xms512m -Xmx1g" kafka-producer-perf-test --topic github --num-records 200000 --print-metrics --throughput 1000 --payload-file /github_msgs.ndjson --producer-props bootstrap.servers=localhost:9092
