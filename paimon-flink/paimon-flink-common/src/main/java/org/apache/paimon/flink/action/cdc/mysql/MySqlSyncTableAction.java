/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action.cdc.mysql;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.flink.FlinkConnectorOptions;
import org.apache.paimon.flink.action.Action;
import org.apache.paimon.flink.sink.cdc.EventParser;
import org.apache.paimon.flink.sink.cdc.FlinkCdcSinkBuilder;
import org.apache.paimon.flink.sink.cdc.SchemaChangeProcessFunction;
import org.apache.paimon.options.CatalogOptions;
import org.apache.paimon.options.Options;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.types.DataType;

import com.ververica.cdc.connectors.mysql.source.MySqlSource;
import com.ververica.cdc.connectors.mysql.source.MySqlSourceBuilder;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceOptions;
import com.ververica.cdc.connectors.mysql.source.offset.BinlogOffset;
import com.ververica.cdc.connectors.mysql.source.offset.BinlogOffsetBuilder;
import com.ververica.cdc.connectors.mysql.table.JdbcUrlUtils;
import com.ververica.cdc.connectors.mysql.table.StartupOptions;
import com.ververica.cdc.debezium.JsonDebeziumDeserializationSchema;
import com.ververica.cdc.debezium.table.DebeziumOptions;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.connect.json.JsonConverterConfig;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An {@link Action} which synchronize one or multiple MySQL tables into one Paimon table.
 *
 * <p>You should specify MySQL source table in {@code mySqlConfig}. See <a
 * href="https://ververica.github.io/flink-cdc-connectors/master/content/connectors/mysql-cdc.html#connector-options">document
 * of flink-cdc-connectors</a> for detailed keys and values.
 *
 * <p>If the specified Paimon table does not exist, this action will automatically create the table.
 * Its schema will be derived from all specified MySQL tables. If the Paimon table already exists,
 * its schema will be compared against the schema of all specified MySQL tables.
 *
 * <p>This action supports a limited number of schema changes. Unsupported schema changes will be
 * ignored. Currently supported schema changes includes:
 *
 * <ul>
 *   <li>Adding columns.
 *   <li>Altering column types. More specifically,
 *       <ul>
 *         <li>altering from a string type (char, varchar, text) to another string type with longer
 *             length,
 *         <li>altering from a binary type (binary, varbinary, blob) to another binary type with
 *             longer length,
 *         <li>altering from an integer type (tinyint, smallint, int, bigint) to another integer
 *             type with wider range,
 *         <li>altering from a floating-point type (float, double) to another floating-point type
 *             with wider range,
 *       </ul>
 *       are supported. Other type changes will cause exceptions.
 * </ul>
 */
public class MySqlSyncTableAction implements Action {

    private final Map<String, String> mySqlConfig;
    private final String warehouse;
    private final String database;
    private final String table;
    private final List<String> partitionKeys;
    private final List<String> primaryKeys;
    private final Map<String, String> paimonConfig;

    MySqlSyncTableAction(
            Map<String, String> mySqlConfig,
            String warehouse,
            String database,
            String table,
            List<String> partitionKeys,
            List<String> primaryKeys,
            Map<String, String> paimonConfig) {
        this.mySqlConfig = mySqlConfig;
        this.warehouse = warehouse;
        this.database = database;
        this.table = table;
        this.partitionKeys = partitionKeys;
        this.primaryKeys = primaryKeys;
        this.paimonConfig = paimonConfig;
    }

    @Override
    public void run() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        build(env);
        env.execute(String.format("MySQL CTAS: %s.%s", database, table));
    }

    public void build(StreamExecutionEnvironment env) throws Exception {
        MySqlSource<String> source = buildSource();
        MySqlSchema mySqlSchema =
                getMySqlSchemaList().stream()
                        .reduce(MySqlSchema::merge)
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                "No table satisfies the given database name and table name"));

        Catalog catalog =
                CatalogFactory.createCatalog(
                        CatalogContext.create(
                                new Options().set(CatalogOptions.WAREHOUSE, warehouse)));
        catalog.createDatabase(database, true);

        Identifier identifier = new Identifier(database, table);
        FileStoreTable table;
        try {
            table = (FileStoreTable) catalog.getTable(identifier);
            if (!schemaCompatible(table.schema(), mySqlSchema)) {
                throw new IllegalArgumentException(
                        "Paimon schema and MySQL schema are not compatible.\n"
                                + "Paimon fields are: "
                                + table.schema().fields()
                                + ".\nMySQL fields are: "
                                + mySqlSchema.fields);
            }
        } catch (Catalog.TableNotExistException e) {
            Schema schema = buildSchema(mySqlSchema);
            catalog.createTable(identifier, schema, false);
            table = (FileStoreTable) catalog.getTable(identifier);
        }

        EventParser.Factory<String> parserFactory;
        String serverTimeZone = mySqlConfig.get("server-time-zone");
        if (serverTimeZone != null) {
            parserFactory = () -> new MySqlDebeziumJsonEventParser(ZoneId.of(serverTimeZone));
        } else {
            parserFactory = MySqlDebeziumJsonEventParser::new;
        }

        FlinkCdcSinkBuilder<String> sinkBuilder =
                new FlinkCdcSinkBuilder<String>()
                        .withInput(
                                env.fromSource(
                                        source, WatermarkStrategy.noWatermarks(), "MySQL Source"))
                        .withParserFactory(parserFactory)
                        .withTable(table);
        String sinkParallelism = paimonConfig.get(FlinkConnectorOptions.SINK_PARALLELISM.key());
        if (sinkParallelism != null) {
            sinkBuilder.withParallelism(Integer.parseInt(sinkParallelism));
        }
        sinkBuilder.build();
    }

    private MySqlSource<String> buildSource() {
        MySqlSourceBuilder<String> sourceBuilder = MySqlSource.builder();

        String databaseName = mySqlConfig.get(MySqlSourceOptions.DATABASE_NAME.key());
        String tableName = mySqlConfig.get(MySqlSourceOptions.TABLE_NAME.key());
        sourceBuilder
                .hostname(mySqlConfig.get(MySqlSourceOptions.HOSTNAME.key()))
                .port(Integer.parseInt(mySqlConfig.get(MySqlSourceOptions.PORT.key())))
                .username(mySqlConfig.get(MySqlSourceOptions.USERNAME.key()))
                .password(mySqlConfig.get(MySqlSourceOptions.PASSWORD.key()))
                .databaseList(databaseName)
                .tableList(databaseName + "." + tableName);

        Optional.ofNullable(mySqlConfig.get(MySqlSourceOptions.SERVER_ID.key()))
                .ifPresent(sourceBuilder::serverId);
        Optional.ofNullable(mySqlConfig.get(MySqlSourceOptions.SERVER_TIME_ZONE.key()))
                .ifPresent(sourceBuilder::serverTimeZone);
        Optional.ofNullable(mySqlConfig.get(MySqlSourceOptions.SCAN_SNAPSHOT_FETCH_SIZE.key()))
                .ifPresent(size -> sourceBuilder.fetchSize(Integer.parseInt(size)));
        Optional.ofNullable(mySqlConfig.get(MySqlSourceOptions.CONNECT_TIMEOUT.key()))
                .ifPresent(timeout -> sourceBuilder.connectTimeout(Duration.parse(timeout)));
        Optional.ofNullable(mySqlConfig.get(MySqlSourceOptions.CONNECT_MAX_RETRIES.key()))
                .ifPresent(retries -> sourceBuilder.connectMaxRetries(Integer.parseInt(retries)));
        Optional.ofNullable(mySqlConfig.get(MySqlSourceOptions.CONNECTION_POOL_SIZE.key()))
                .ifPresent(size -> sourceBuilder.connectionPoolSize(Integer.parseInt(size)));
        Optional.ofNullable(mySqlConfig.get(MySqlSourceOptions.HEARTBEAT_INTERVAL.key()))
                .ifPresent(interval -> sourceBuilder.heartbeatInterval(Duration.parse(interval)));

        String startupMode = mySqlConfig.get(MySqlSourceOptions.SCAN_STARTUP_MODE.key());
        // see
        // https://github.com/ververica/flink-cdc-connectors/blob/master/flink-connector-mysql-cdc/src/main/java/com/ververica/cdc/connectors/mysql/table/MySqlTableSourceFactory.java#L196
        if ("initial".equalsIgnoreCase(startupMode)) {
            sourceBuilder.startupOptions(StartupOptions.initial());
        } else if ("earliest-offset".equalsIgnoreCase(startupMode)) {
            sourceBuilder.startupOptions(StartupOptions.earliest());
        } else if ("latest-offset".equalsIgnoreCase(startupMode)) {
            sourceBuilder.startupOptions(StartupOptions.latest());
        } else if ("specific-offset".equalsIgnoreCase(startupMode)) {
            BinlogOffsetBuilder offsetBuilder = BinlogOffset.builder();
            String file =
                    mySqlConfig.get(MySqlSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_FILE.key());
            String pos = mySqlConfig.get(MySqlSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_POS.key());
            if (file != null && pos != null) {
                offsetBuilder.setBinlogFilePosition(file, Long.parseLong(pos));
            }
            Optional.ofNullable(
                            mySqlConfig.get(
                                    MySqlSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_GTID_SET.key()))
                    .ifPresent(offsetBuilder::setGtidSet);
            Optional.ofNullable(
                            mySqlConfig.get(
                                    MySqlSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_SKIP_EVENTS
                                            .key()))
                    .ifPresent(
                            skipEvents -> offsetBuilder.setSkipEvents(Long.parseLong(skipEvents)));
            Optional.ofNullable(
                            mySqlConfig.get(
                                    MySqlSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_SKIP_ROWS
                                            .key()))
                    .ifPresent(skipRows -> offsetBuilder.setSkipRows(Long.parseLong(skipRows)));
            sourceBuilder.startupOptions(StartupOptions.specificOffset(offsetBuilder.build()));
        } else if ("timestamp".equalsIgnoreCase(startupMode)) {
            sourceBuilder.startupOptions(
                    StartupOptions.timestamp(
                            Long.parseLong(
                                    mySqlConfig.get(
                                            MySqlSourceOptions.SCAN_STARTUP_TIMESTAMP_MILLIS
                                                    .key()))));
        }

        Properties jdbcProperties = new Properties();
        Properties debeziumProperties = new Properties();
        for (Map.Entry<String, String> entry : mySqlConfig.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.startsWith(JdbcUrlUtils.PROPERTIES_PREFIX)) {
                jdbcProperties.put(key.substring(JdbcUrlUtils.PROPERTIES_PREFIX.length()), value);
            } else if (key.startsWith(DebeziumOptions.DEBEZIUM_OPTIONS_PREFIX)) {
                debeziumProperties.put(
                        key.substring(DebeziumOptions.DEBEZIUM_OPTIONS_PREFIX.length()), value);
            }
        }
        sourceBuilder.jdbcProperties(jdbcProperties);
        sourceBuilder.debeziumProperties(debeziumProperties);

        Map<String, Object> customConverterConfigs = new HashMap<>();
        customConverterConfigs.put(JsonConverterConfig.DECIMAL_FORMAT_CONFIG, "numeric");
        JsonDebeziumDeserializationSchema schema =
                new JsonDebeziumDeserializationSchema(true, customConverterConfigs);
        return sourceBuilder.deserializer(schema).includeSchemaChanges(true).build();
    }

    private List<MySqlSchema> getMySqlSchemaList() throws Exception {
        Pattern databasePattern =
                Pattern.compile(mySqlConfig.get(MySqlSourceOptions.DATABASE_NAME.key()));
        Pattern tablePattern =
                Pattern.compile(mySqlConfig.get(MySqlSourceOptions.TABLE_NAME.key()));
        List<MySqlSchema> mySqlSchemaList = new ArrayList<>();
        try (Connection conn =
                DriverManager.getConnection(
                        String.format(
                                "jdbc:mysql://%s:%s/",
                                mySqlConfig.get(MySqlSourceOptions.HOSTNAME.key()),
                                mySqlConfig.get(MySqlSourceOptions.PORT.key())),
                        mySqlConfig.get(MySqlSourceOptions.USERNAME.key()),
                        mySqlConfig.get(MySqlSourceOptions.PASSWORD.key()))) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet schemas = metaData.getCatalogs()) {
                while (schemas.next()) {
                    String databaseName = schemas.getString("TABLE_CAT");
                    Matcher databaseMatcher = databasePattern.matcher(databaseName);
                    if (databaseMatcher.matches()) {
                        try (ResultSet tables = metaData.getTables(databaseName, null, "%", null)) {
                            while (tables.next()) {
                                String tableName = tables.getString("TABLE_NAME");
                                Matcher tableMatcher = tablePattern.matcher(tableName);
                                if (tableMatcher.matches()) {
                                    mySqlSchemaList.add(
                                            new MySqlSchema(metaData, databaseName, tableName));
                                }
                            }
                        }
                    }
                }
            }
        }
        return mySqlSchemaList;
    }

    private boolean schemaCompatible(TableSchema tableSchema, MySqlSchema mySqlSchema) {
        for (Map.Entry<String, DataType> entry : mySqlSchema.fields.entrySet()) {
            int idx = tableSchema.fieldNames().indexOf(entry.getKey());
            if (idx < 0) {
                return false;
            }
            DataType type = tableSchema.fields().get(idx).type();
            if (!SchemaChangeProcessFunction.canConvert(entry.getValue(), type)) {
                return false;
            }
        }
        return true;
    }

    private Schema buildSchema(MySqlSchema mySqlSchema) {
        Schema.Builder builder = Schema.newBuilder();
        builder.options(paimonConfig);

        for (Map.Entry<String, DataType> entry : mySqlSchema.fields.entrySet()) {
            builder.column(entry.getKey(), entry.getValue());
        }

        if (primaryKeys.size() > 0) {
            for (String key : primaryKeys) {
                if (!mySqlSchema.fields.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "Specified primary key " + key + " does not exist in MySQL tables");
                }
            }
            builder.primaryKey(primaryKeys);
        } else if (mySqlSchema.primaryKeys.size() > 0) {
            builder.primaryKey(mySqlSchema.primaryKeys);
        } else {
            throw new IllegalArgumentException(
                    "Primary keys are not specified. "
                            + "Also, can't infer primary keys from MySQL table schemas because "
                            + "MySQL tables have no primary keys or have different primary keys.");
        }

        if (partitionKeys.size() > 0) {
            builder.partitionKeys(partitionKeys);
        }

        return builder.build();
    }

    private static class MySqlSchema {

        private final String databaseName;
        private final String tableName;

        private final Map<String, DataType> fields;
        private final List<String> primaryKeys;

        private MySqlSchema(DatabaseMetaData metaData, String databaseName, String tableName)
                throws Exception {
            this.databaseName = databaseName;
            this.tableName = tableName;

            fields = new LinkedHashMap<>();
            try (ResultSet rs = metaData.getColumns(null, databaseName, tableName, null)) {
                while (rs.next()) {
                    String fieldName = rs.getString("COLUMN_NAME");
                    String fieldType = rs.getString("TYPE_NAME");
                    Integer precision = rs.getInt("COLUMN_SIZE");
                    if (rs.wasNull()) {
                        precision = null;
                    }
                    Integer scale = rs.getInt("DECIMAL_DIGITS");
                    if (rs.wasNull()) {
                        scale = null;
                    }
                    fields.put(fieldName, MySqlTypeUtils.toDataType(fieldType, precision, scale));
                }
            }

            primaryKeys = new ArrayList<>();
            try (ResultSet rs = metaData.getPrimaryKeys(null, databaseName, tableName)) {
                while (rs.next()) {
                    String fieldName = rs.getString("COLUMN_NAME");
                    primaryKeys.add(fieldName);
                }
            }
        }

        private MySqlSchema merge(MySqlSchema other) {
            for (Map.Entry<String, DataType> entry : other.fields.entrySet()) {
                String fieldName = entry.getKey();
                DataType newType = entry.getValue();
                if (fields.containsKey(fieldName)) {
                    DataType oldType = fields.get(fieldName);
                    if (SchemaChangeProcessFunction.canConvert(oldType, newType)) {
                        fields.put(fieldName, newType);
                    } else if (SchemaChangeProcessFunction.canConvert(newType, oldType)) {
                        // nothing to do
                    } else {
                        throw new IllegalArgumentException(
                                String.format(
                                        "Column %s have different types in table %s.%s and table %s.%s",
                                        fieldName,
                                        databaseName,
                                        tableName,
                                        other.databaseName,
                                        other.tableName));
                    }
                } else {
                    fields.put(fieldName, newType);
                }
            }
            if (!primaryKeys.equals(other.primaryKeys)) {
                primaryKeys.clear();
            }
            return this;
        }
    }
}