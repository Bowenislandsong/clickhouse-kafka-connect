package com.clickhouse.kafka.connect.sink.db;

import com.clickhouse.client.*;
import com.clickhouse.client.config.ClickHouseClientOption;
import com.clickhouse.client.data.BinaryStreamUtils;
import com.clickhouse.kafka.connect.sink.ClickHouseSinkConfig;
import com.clickhouse.kafka.connect.sink.data.Data;
import com.clickhouse.kafka.connect.sink.data.Record;
import com.clickhouse.kafka.connect.sink.db.helper.ClickHouseHelperClient;
import com.clickhouse.kafka.connect.sink.db.mapping.Column;
import com.clickhouse.kafka.connect.sink.db.mapping.Table;
import com.clickhouse.kafka.connect.sink.db.mapping.Type;
import com.clickhouse.kafka.connect.util.Mask;

import com.clickhouse.kafka.connect.util.Utils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class ClickHouseWriter implements DBWriter{

    private static final Logger LOGGER = LoggerFactory.getLogger(ClickHouseWriter.class);

    private ClickHouseHelperClient chc = null;
    private ClickHouseSinkConfig csc = null;

    private Map<String, Table> mapping = null;

    private boolean isBinary = false;

    public ClickHouseWriter() {
        this.mapping = new HashMap<>();
    }

    @Override
    public boolean start(ClickHouseSinkConfig csc) {
        this.csc = csc;
        String hostname = csc.getHostname();
        int port = csc.getPort();
        String database = csc.getDatabase();
        String username = csc.getUsername();
        String password = csc.getPassword();
        boolean sslEnabled = csc.isSslEnabled();
        int timeout = csc.getTimeout();

        LOGGER.info(String.format("hostname: [%s] port [%d] database [%s] username [%s] password [%s] sslEnabled [%s] timeout [%d]", hostname, port, database, username, Mask.passwordMask(password), sslEnabled, timeout));

        chc = new ClickHouseHelperClient.ClickHouseClientBuilder(hostname, port)
                .setDatabase(database)
                .setUsername(username)
                .setPassword(password)
                .sslEnable(sslEnabled)
                .setTimeout(timeout)
                .setRetry(csc.getRetry())
                .build();

        if (!chc.ping()) {
            LOGGER.error("Unable to ping Clickhouse server.");
            return false;
        }

        LOGGER.info("Ping is successful.");

        List<Table> tableList = chc.extractTablesMapping();
        if (tableList.isEmpty()) {
            LOGGER.error("Did not find any tables in destination Please create before running.");
            return false;
        }

        for (Table table: tableList) {
            this.mapping.put(table.getName(), table);
        }
        return true;
    }

    @Override
    public void stop() {

    }

    public void setBinary(boolean binary) {
        isBinary = binary;
    }

    // TODO: we need to refactor that
    private String convertHelper(Object v) {
        if (v instanceof List) {
            String value = ((List<?>) v).stream().map( vv -> vv.toString()).collect(Collectors.joining(",","[","]"));
            return value;

        } else {
            return v.toString();
        }
    }
    private String convertWithStream(List<Object> values, String prefixChar, String suffixChar, String delimiterChar, String trimChar) {
        return values
                .stream().map (
                    v ->
                            trimChar + convertHelper(v) + trimChar
                )
                .collect(Collectors.joining(delimiterChar, prefixChar, suffixChar));
    }

    private String extractFields(List<Field> fields, String prefixChar, String suffixChar, String delimiterChar, String trimChar) {
        return fields.stream().map(v -> trimChar + v.name() + trimChar).collect(Collectors.joining(delimiterChar, prefixChar, suffixChar));
    }

    public ClickHouseNode getServer() {
        return chc.getServer();
    }

    @Override
    public void doInsert(List<Record> records) {
        if ( records.isEmpty() )
            return;

        try {
            Record first = records.get(0);
            String topic = first.getTopic();
            Table table = this.mapping.get(Utils.escapeTopicName(topic));

            switch (first.getSchemaType()) {
                case SCHEMA:
                    if (table.hasDefaults()) {
                        LOGGER.debug("Default value present, switching to JSON insert instead.");
                        doInsertJson(records);
                    } else {
                        doInsertRawBinary(records);
                    }
                    break;
                case SCHEMA_LESS:
                    doInsertJson(records);
                    break;
            }
        } catch (Exception e) {
            LOGGER.trace("Passing the exception to the exception handler.");
            Utils.handleException(e);
        }
    }

    private boolean validateDataSchema(Table table, Record record, boolean onlyFieldsName) {
        boolean validSchema = true;
        for (Column col : table.getColumns() ) {
            String colName = col.getName();
            Type type = col.getType();
            boolean isNullable = col.isNullable();
            if (!isNullable) {
                Data obj = record.getJsonMap().get(colName);
                if (obj == null) {
                    validSchema = false;
                    LOGGER.error(String.format("Table column name [%s] is not found in data record.", colName));
                }
                if (!onlyFieldsName) {
                    String colTypeName = type.name();
                    String dataTypeName = obj.getFieldType().getName().toUpperCase();
                    // TODO: make extra validation for Map/Array type
                    switch (colTypeName) {
                        case "Date":
                        case "Date32":
                            if ( dataTypeName.equals(Type.INT32) || dataTypeName.equals(Type.STRING) ) {
                                LOGGER.debug(String.format("Will try to convert from %s to %s", colTypeName, dataTypeName));
                            }
                            break;
                        case "DateTime":
                        case "DateTime64":
                            if ( dataTypeName.equals(Type.INT64) || dataTypeName.equals(Type.STRING) ) {
                                LOGGER.debug(String.format("Will try to convert from %s to %s", colTypeName, dataTypeName));
                            }
                            break;
                        case "UUID":
                            if ( dataTypeName.equals(Type.UUID) || dataTypeName.equals(Type.STRING) ) {
                                LOGGER.debug(String.format("Will try to convert from %s to %s", colTypeName, dataTypeName));
                            }
                            break;
                        default:
                            if (!colTypeName.equals(dataTypeName)) {
                                validSchema = false;
                                LOGGER.error(String.format("Table column name [%s] type [%s] is not matching data column type [%s]", col.getName(), colTypeName, dataTypeName));
                            }

                    }

                }
            }
        }
        return validSchema;
    }

    private void doWriteDates(Type type, ClickHousePipedOutputStream stream, Data value) throws IOException {
        // TODO: develop more specific tests to have better coverage
        if (value.getObject() == null) {
            BinaryStreamUtils.writeNull(stream);
            return;
        }
        boolean unsupported = false;
        switch (type) {
            case Date:
                if (value.getFieldType().equals(Schema.Type.INT32)) {
                    BinaryStreamUtils.writeUnsignedInt16(stream, ((Integer) value.getObject()).intValue());
                } else {
                    unsupported = true;
                }
                break;
            case Date32:
                if (value.getFieldType().equals(Schema.Type.INT32)) {
                    BinaryStreamUtils.writeInt32(stream, ((Integer) value.getObject()).intValue());
                } else {
                    unsupported = true;
                }
                break;
            case DateTime:
                if (value.getFieldType().equals(Schema.Type.INT64)) {
                    BinaryStreamUtils.writeUnsignedInt32(stream, ((Long) value.getObject()).longValue());
                } else {
                    unsupported = true;
                }
                break;
            case DateTime64:
                if (value.getFieldType().equals(Schema.Type.INT64)) {
                    BinaryStreamUtils.writeInt64(stream, ((Long) value.getObject()).longValue());
                } else {
                    unsupported = true;
                }
                break;
        }
        if (unsupported) {
            String msg = String.format("Not implemented conversion. from %s to %s", value.getFieldType(), type);
            LOGGER.error(msg);
            throw new DataException(msg);
        }
    }
    private void doWritePrimitive(Type type, ClickHousePipedOutputStream stream, Object value) throws IOException {
        if (value == null) {
            BinaryStreamUtils.writeNull(stream);
            return;
        }
        switch (type) {
            case INT8:
                BinaryStreamUtils.writeInt8(stream, (Byte) value);
                break;
            case INT16:
                BinaryStreamUtils.writeInt16(stream, (Short) value);
                break;
            case INT32:
                BinaryStreamUtils.writeInt32(stream, (Integer) value);
                break;
            case INT64:
                BinaryStreamUtils.writeInt64(stream, (Long) value);
                break;
            case UINT8:
                BinaryStreamUtils.writeUnsignedInt8(stream, (Byte) value);
                break;
            case UINT16:
                BinaryStreamUtils.writeUnsignedInt16(stream, (Short) value);
                break;
            case UINT32:
                BinaryStreamUtils.writeUnsignedInt32(stream, (Integer) value);
                break;
            case UINT64:
                BinaryStreamUtils.writeUnsignedInt64(stream, (Long) value);
                break;
            case FLOAT32:
                BinaryStreamUtils.writeFloat32(stream, (Float) value);
                break;
            case FLOAT64:
                BinaryStreamUtils.writeFloat64(stream, (Double) value);
                break;
            case BOOLEAN:
                BinaryStreamUtils.writeBoolean(stream, (Boolean) value);
                break;
            case STRING:
                BinaryStreamUtils.writeString(stream, ((String) value).getBytes());
                break;
            case UUID:
                BinaryStreamUtils.writeUuid(stream, UUID.fromString((String) value));
                break;
        }
    }

    private void doWriteCol(Record record, Column col, ClickHousePipedOutputStream stream) throws IOException {

            String name = col.getName();
            Type colType = col.getType();
            boolean filedExists = record.getJsonMap().containsKey(name);
            if (filedExists) {
                Data value = record.getJsonMap().get(name);
                // TODO: the mapping need to be more efficient
                // If column is nullable && the object is also null add the not null marker
                if (col.isNullable() && value.getObject() != null) {
                    BinaryStreamUtils.writeNonNull(stream);
                }
                if (col.isNullable() == false && value.getObject() == null) {
                    // this the situation when the col is not isNullable, but the data is null here we need to drop the records
                    throw new RuntimeException(("col.isNullable() is false and value is empty"));
                }
                switch (colType) {
                    case INT8:
                    case INT16:
                    case INT32:
                    case INT64:
                    case UINT8:
                    case UINT16:
                    case UINT32:
                    case UINT64:
                    case FLOAT32:
                    case FLOAT64:
                    case BOOLEAN:
                    case UUID:
                    case STRING:
                        doWritePrimitive(colType, stream, value.getObject());
                        break;
                    case Date:
                    case Date32:
                    case DateTime:
                    case DateTime64:
                        doWriteDates(colType, stream, value);
                        break;
                    case MAP:
                        Map<?,?> mapTmp = (Map<?,?>)value.getObject();
                        int mapSize = mapTmp.size();
                        BinaryStreamUtils.writeVarInt(stream, mapSize);
                        mapTmp.entrySet().forEach( v-> {
                            try {
                                doWritePrimitive(col.getMapKeyType(), stream, v.getKey());
                                doWritePrimitive(col.getMapValueType(), stream, v.getValue());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }

                        });
                        break;
                    case ARRAY:
                        List<?> arrObject = (List<?>)value.getObject();
                        int sizeArrObject = arrObject.size();
                        BinaryStreamUtils.writeVarInt(stream, sizeArrObject);
                        arrObject.forEach( v -> {
                            try {
                                doWritePrimitive(col.getSubType().getType(), stream, v);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                        break;
                }
            } else {
                if ( col.isNullable() ) {
                    // set null since there is no value
                    BinaryStreamUtils.writeNull(stream);
                } else {
                    // no filed and not nullable
                    LOGGER.error(String.format("Record is missing field %s", name));
                    throw new RuntimeException();
                }
            }

    }
    public void doInsertRawBinary(List<Record> records) throws IOException, ExecutionException, InterruptedException {
        long s1 = System.currentTimeMillis();

        if ( records.isEmpty() )
            return;
        int batchSize = records.size();

        Record first = records.get(0);
        String topic = first.getTopic();
        LOGGER.info(String.format("Number of records to insert %d to table name %s", batchSize, topic));
        Table table = this.mapping.get(Utils.escapeTopicName(topic));
        if (table == null) {
            //TODO to pick the correct exception here
            throw new RuntimeException(String.format("Table %s does not exists", topic));
        }

        if ( !validateDataSchema(table, first, false) )
            throw new RuntimeException();
        // Let's test first record
        // Do we have all elements from the table inside the record

        long s2 = System.currentTimeMillis();
        try (ClickHouseClient client = ClickHouseClient.newInstance(ClickHouseProtocol.HTTP)) {
            ClickHouseRequest.Mutation request = client.connect(chc.getServer())
            .write()
                    .table(table.getName())
                    .format(ClickHouseFormat.RowBinary)
                    // this is needed to get meaningful response summary
                    .set("insert_quorum", 2)
                    .set("send_progress_in_http_headers", 1);

            ClickHouseConfig config = request.getConfig();
            CompletableFuture<ClickHouseResponse> future;

            try (ClickHousePipedOutputStream stream = ClickHouseDataStreamFactory.getInstance()
                    .createPipedOutputStream(config, null)) {
                // start the worker thread which transfer data from the input into ClickHouse
                future = request.data(stream.getInputStream()).send();
                // write bytes into the piped stream
                for (Record record: records ) {
                    if (record.getSinkRecord().value() != null ) {
                        for (Column col : table.getColumns())
                            doWriteCol(record, col, stream);
                    }
                }
                // We need to close the stream before getting a response
                stream.close();
                ClickHouseResponseSummary summary;
                try (ClickHouseResponse response = future.get()) {
                    summary = response.getSummary();
                    long rows = summary.getWrittenRows();

                } catch (Exception e) {
                    LOGGER.debug("Reading results after closing stream to ensure insert happened failed.", e);
                    throw e;
                }
            } catch (Exception e) {
                LOGGER.trace("Exception", e);
                throw e;
            }
        } catch (Exception e) {
            LOGGER.trace("Exception", e);
            throw e;
        }

        long s3 = System.currentTimeMillis();
        LOGGER.info("batchSize {} data ms {} send {}", batchSize, s2 - s1, s3 - s2);

    }


    public void doInsertJson(List<Record> records) throws IOException, ExecutionException, InterruptedException {
        //https://devqa.io/how-to-convert-java-map-to-json/
        Gson gson = new Gson();
        long s1 = System.currentTimeMillis();
        long s2 = 0;
        long s3 = 0;

        if ( records.isEmpty() )
            return;
        int batchSize = records.size();

        Record first = records.get(0);
        String topic = first.getTopic();
        LOGGER.info(String.format("Number of records to insert %d to table name %s", batchSize, topic));
        Table table = this.mapping.get(Utils.escapeTopicName(topic));
        if (table == null) {
            //TODO to pick the correct exception here
            throw new RuntimeException(String.format("Table %s does not exists", topic));
        }

        // We don't validate the schema for JSON inserts.  ClickHouse will ignore unknown fields based on the
        // input_format_skip_unknown_fields setting, and missing fields will use ClickHouse defaults

        try (ClickHouseClient client = ClickHouseClient.newInstance(ClickHouseProtocol.HTTP)) {
            ClickHouseRequest.Mutation request = client.connect(chc.getServer())
                    .write()
                    .table(table.getName())
                    .format(ClickHouseFormat.JSONEachRow)
                    // this is needed to get meaningful response summary
                    .set("insert_quorum", 2)
                    .set("input_format_skip_unknown_fields", 1)
                    .set("send_progress_in_http_headers", 1);


            ClickHouseConfig config = request.getConfig();
            request.option(ClickHouseClientOption.WRITE_BUFFER_SIZE, 8192);

            CompletableFuture<ClickHouseResponse> future;

            try (ClickHousePipedOutputStream stream = ClickHouseDataStreamFactory.getInstance()
                    .createPipedOutputStream(config, null)) {
                // start the worker thread which transfer data from the input into ClickHouse
                future = request.data(stream.getInputStream()).send();
                // write bytes into the piped stream
                for (Record record: records ) {
                    if (record.getSinkRecord().value() != null ) {
                        Map<String, Object> data;
                        switch (record.getSchemaType()) {
                            case SCHEMA:
                                data = new HashMap<>(16);
                                Struct struct = (Struct) record.getSinkRecord().value();
                                for (Field field : struct.schema().fields()) {
                                    data.put(field.name(), struct.get(field));//Doesn't handle multi-level object depth
                                }
                                break;
                            default:
                                data = (Map<String, Object>) record.getSinkRecord().value();
                                break;
                        }
                        
                        java.lang.reflect.Type gsonType = new TypeToken<HashMap>() {}.getType();
                        String gsonString = gson.toJson(data, gsonType);
                        LOGGER.debug(String.format("topic [%s] partition [%d] offset [%d] payload '%s'",
                                record.getTopic(),
                                record.getRecordOffsetContainer().getPartition(),
                                record.getRecordOffsetContainer().getOffset(),
                                gsonString));
                        BinaryStreamUtils.writeBytes(stream, gsonString.getBytes(StandardCharsets.UTF_8));
                    } else {
                        LOGGER.warn(String.format("Getting empty record skip the insert topic[%s] offset[%d]", record.getTopic(), record.getSinkRecord().kafkaOffset()));
                    }
                }

                stream.close();
                ClickHouseResponseSummary summary;
                s2 = System.currentTimeMillis();
                try (ClickHouseResponse response = future.get()) {
                    summary = response.getSummary();
                    long rows = summary.getWrittenRows();
                    LOGGER.trace(String.format("insert num of rows %d", rows));
                } catch (Exception e) {//This is mostly for auto-closing
                    LOGGER.trace("Exception", e);
                    throw e;
                }
            } catch (Exception e) {//This is mostly for auto-closing
                LOGGER.trace("Exception", e);
                throw e;
            }
        } catch (Exception e) {//This is mostly for auto-closing
            LOGGER.trace("Exception", e);
            throw e;
        }
        s3 = System.currentTimeMillis();
        LOGGER.info("batchSize {} data ms {} send {}", batchSize, s2 - s1, s3 - s2);
    }

    public void doInsertSimple(List<Record> records) {
        // TODO: here we will need to make refactor (not to use query & string , but we can make this optimization later )
        long s1 = System.currentTimeMillis();

        if ( records.isEmpty() )
            return;
        int batchSize = records.size();

        Record first = records.get(0);
        String topic = first.getTopic();
        LOGGER.info(String.format("Number of records to insert %d to table name %s", batchSize, topic));
        // Build the insert SQL
        StringBuffer sb = new StringBuffer();
        sb.append(String.format("INSERT INTO %s ", Utils.escapeTopicName(topic)));
        sb.append(extractFields(first.getFields(), "(", ")", ",", ""));
        sb.append(" VALUES ");
        LOGGER.debug("sb {}", sb);
        for (Record record: records ) {
            LOGGER.debug("records {}", record.getJsonMap().keySet().stream().collect(Collectors.joining(",", "[", "]")));
            List<Object> values = record.getFields().
                    stream().
                    map(field -> record.getJsonMap().get(field.name())).
                    collect(Collectors.toList());
            String valueStr = convertWithStream(values, "(", ")", ",", "'");
            sb.append(valueStr + ",");
        }
        String insertStr = sb.deleteCharAt(sb.length() - 1).toString();
        long s2 = System.currentTimeMillis();
        //ClickHouseClient.load(server, ClickHouseFormat.RowBinaryWithNamesAndTypes)
        LOGGER.debug("*****************");
        LOGGER.debug(insertStr);
        LOGGER.debug("*****************");
        chc.query(insertStr, ClickHouseFormat.RowBinaryWithNamesAndTypes);
        /*
        try (ClickHouseClient client = ClickHouseClient.newInstance(ClickHouseProtocol.HTTP);
             ClickHouseResponse response = client.connect(chc.getServer())  // or client.connect(endpoints)
                     // you'll have to parse response manually if using a different format
                     .option(ClickHouseClientOption.CONNECTION_TIMEOUT, csc.getTimeout())
                     .option(ClickHouseClientOption.SOCKET_TIMEOUT, csc.getTimeout())
                     .format(ClickHouseFormat.RowBinaryWithNamesAndTypes)
                     .query(insertStr)
                     .executeAndWait()) {
            ClickHouseResponseSummary summary = response.getSummary();
            long totalRows = summary.getTotalRowsToRead();
            LOGGER.info("totalRows {}", totalRows);

        } catch (ClickHouseException e) {
            LOGGER.debug(insertStr);
            LOGGER.error(String.format("INSERT ErrorCode %d ", e.getErrorCode()), e);
            throw new RuntimeException(e);
        }

         */
        long s3 = System.currentTimeMillis();
        LOGGER.info("batchSize {} data ms {} send {}", batchSize, s2 - s1, s3 - s2);
    }

    @Override
    public long recordsInserted() {
        return 0;
    }
}
