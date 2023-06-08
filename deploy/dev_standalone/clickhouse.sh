apt update
apt install -y curl
curl https://clickhouse.com/ | sh
./clickhouse server 
# use ./clickhouse client to interact with server on the same host
# server is serving client on port 9000
# server is available to connenctor on port 8123