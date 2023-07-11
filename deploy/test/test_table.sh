docker cp ./deploy/test/create_table.sh clickhouse:/create_table.sh
docker exec clickhouse bash -c "/create_table.sh"
sleep 5
docker cp ./deploy/test/github_msgs.ndjson broker:github_msgs.ndjson
docker cp ./deploy/test/produce_msg.sh broker:/produce_msg.sh
docker exec broker bash -c "/produce_msg.sh"
