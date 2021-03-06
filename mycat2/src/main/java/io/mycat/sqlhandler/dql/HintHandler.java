package io.mycat.sqlhandler.dql;

import com.alibaba.fastsql.sql.SQLUtils;
import com.alibaba.fastsql.sql.ast.SQLCommentHint;
import com.alibaba.fastsql.sql.ast.SQLStatement;
import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlHintStatement;
import io.mycat.*;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.beans.MySQLDatasource;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.beans.mysql.MySQLAutoCommit;
import io.mycat.beans.mysql.MySQLErrorCode;
import io.mycat.calcite.table.GlobalTable;
import io.mycat.calcite.table.NormalTable;
import io.mycat.calcite.table.SchemaHandler;
import io.mycat.calcite.table.ShardingTable;
import io.mycat.commands.MycatdbCommand;
import io.mycat.config.*;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import io.mycat.datasource.jdbc.datasource.JdbcDataSource;
import io.mycat.calcite.DataSourceFactory;
import io.mycat.calcite.DefaultDatasourceFactory;
import io.mycat.calcite.ResponseExecutorImplementor;
import io.mycat.calcite.executor.TempResultSetFactory;
import io.mycat.calcite.executor.TempResultSetFactoryImpl;
import io.mycat.proxy.reactor.MycatReactorThread;
import io.mycat.proxy.reactor.ReactorThreadManager;
import io.mycat.proxy.session.MySQLClientSession;
import io.mycat.proxy.session.MySQLSessionManager;
import io.mycat.proxy.session.MycatContextThreadPool;
import io.mycat.proxy.session.MycatSession;
import io.mycat.replica.PhysicsInstance;
import io.mycat.replica.ReplicaDataSourceSelector;
import io.mycat.replica.ReplicaSelectorRuntime;
import io.mycat.replica.ReplicaSwitchType;
import io.mycat.replica.heartbeat.DatasourceStatus;
import io.mycat.replica.heartbeat.HeartBeatStatus;
import io.mycat.replica.heartbeat.HeartbeatFlow;
import io.mycat.sqlhandler.AbstractSQLHandler;
import io.mycat.sqlhandler.ConfigUpdater;
import io.mycat.sqlhandler.SQLRequest;
import io.mycat.sqlhandler.SqlHints;
import io.mycat.sqlhandler.dml.DrdsRunners;
import io.mycat.sqlrecorder.SqlRecord;
import io.mycat.sqlrecorder.SqlRecorderRuntime;
import io.mycat.util.JsonUtil;
import io.mycat.util.NameMap;
import io.mycat.Response;

import java.sql.JDBCType;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HintHandler extends AbstractSQLHandler<MySqlHintStatement> {
    @Override
    protected void onExecute(SQLRequest<MySqlHintStatement> request, MycatDataContext dataContext, Response response) throws Exception {
        MySqlHintStatement ast = request.getAst();
        List<SQLCommentHint> hints = ast.getHints();
        if (hints.size() == 1) {
            String s = SqlHints.unWrapperHint(hints.get(0).getText());
            if (s.startsWith("mycat:")) {
                s = s.substring(6);
                int bodyStartIndex = s.indexOf('{');
                String cmd;
                String body;
                if (bodyStartIndex == -1) {
                    cmd = s;
                    body = "{}";
                } else {
                    cmd = s.substring(0, bodyStartIndex);
                    body = s.substring(bodyStartIndex);
                }


                MetadataManager metadataManager = MetaClusterCurrent.wrapper(MetadataManager.class);
                MycatRouterConfig routerConfig = MetaClusterCurrent.wrapper(MycatRouterConfig.class);
                ReplicaSelectorRuntime replicaSelectorRuntime = MetaClusterCurrent.wrapper(ReplicaSelectorRuntime.class);
                JdbcConnectionManager jdbcConnectionManager = MetaClusterCurrent.wrapper(JdbcConnectionManager.class);
                MycatServer mycatServer = MetaClusterCurrent.wrapper(MycatServer.class);

                if ("setUserDialect".equalsIgnoreCase(cmd)) {
                    MycatRouterConfigOps ops = ConfigUpdater.getOps();
                    Authenticator authenticator = MetaClusterCurrent.wrapper(Authenticator.class);
                    Map map = JsonUtil.from(body, Map.class);
                    String username = (String) map.get("username");
                    String dbType = (String) map.get("dialect");
                    UserConfig userInfo = authenticator.getUserInfo(username);
                    if (userInfo == null) {
                        response.sendError("unknown username:" + username, MySQLErrorCode.ER_UNKNOWN_ERROR);
                        return;
                    }
                    userInfo.setDialect(dbType);
                    ops.putUser(userInfo);
                    ops.commit();
                    response.sendOk();
                    return;
                }
                if ("showSlowSql".equalsIgnoreCase(cmd)) {
                    ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();
                    resultSetBuilder.addColumnInfo("trace_id", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("sql", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("sql_rows", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("start_time", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("end_time", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("execute_time", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("target_name", JDBCType.VARCHAR);
                    Stream<SqlRecord> sqlRecords = SqlRecorderRuntime.INSTANCE.getRecords().stream()
                            .sorted(Comparator.comparingLong(SqlRecord::getExecuteTime).reversed());
                    Map map = JsonUtil.from(body, Map.class);
                    Object idText = map.get("trace_id");

                    if (idText != null) {
                        long id = Long.parseLong(Objects.toString(idText));
                        sqlRecords = sqlRecords.filter(i->id == i.getId());
                    }
                    sqlRecords.forEach(r -> {
                        resultSetBuilder.addObjectRowPayload(Arrays.asList(
                                Objects.toString(r.getId()),
                                Objects.toString(r.getSql()),
                                Objects.toString(r.getSqlRows()),
                                Objects.toString(r.getStartTime()),
                                Objects.toString(r.getEndTime()),
                                Objects.toString(r.getExecuteTime()),
                                Objects.toString(r.getTarget())
                        ));
                    });
                    response.sendResultSet(resultSetBuilder.build());
                    return;
                }
                if ("showDataNodes".equalsIgnoreCase(cmd)) {
                    Map map = JsonUtil.from(body, Map.class);
                    TableHandler table = metadataManager.getTable((String) map.get("schemaName"),
                            (String) map.get("tableName"));
                    LogicTableType type = table.getType();
                    List<DataNode> backends = null;
                    switch (type) {
                        case SHARDING:
                            backends = ((ShardingTable) table).getBackends();
                            break;
                        case GLOBAL:
                            backends = ((GlobalTable) table).getGlobalDataNode();
                            break;
                        case NORMAL:
                            backends = Collections.singletonList(
                                    ((NormalTable) table).getDataNode());
                            break;
                        case CUSTOM:
                            throw new UnsupportedOperationException("unsupport custom table");
                    }
                    ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();
                    resultSetBuilder.addColumnInfo("targetName", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("schemaName", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("tableName", JDBCType.VARCHAR);

                    for (DataNode dataNode : backends) {
                        String targetName = dataNode.getTargetName();
                        String schemaName = dataNode.getSchema();
                        String tableName = dataNode.getTable();

                        resultSetBuilder.addObjectRowPayload(
                                Arrays.asList(targetName, schemaName, tableName));
                    }
                    response.sendResultSet(resultSetBuilder.build());
                    return;
                }
                if ("resetConfig".equalsIgnoreCase(cmd)) {
                    MycatRouterConfigOps ops = ConfigUpdater.getOps();
                    ops.reset();
                    ops.commit();
                    response.sendOk();
                    return;
                }
                if ("run".equalsIgnoreCase(cmd)) {
                    Map<String, Object> map = JsonUtil.from(body, Map.class);
                    String hbt = Objects.toString(map.get("hbt"));
                    TempResultSetFactory tempResultSetFactory = new TempResultSetFactoryImpl();
                    try (DataSourceFactory datasourceFactory = new DefaultDatasourceFactory(dataContext)) {
                        DrdsRunners.runHbtOnDrds(dataContext, hbt,
                                new ResponseExecutorImplementor(dataContext, datasourceFactory, tempResultSetFactory, response));
                    }
                    return;
                }
                if ("createSqlCache".equalsIgnoreCase(cmd)) {
                    MycatRouterConfigOps ops = ConfigUpdater.getOps();
                    SQLStatement sqlStatement = null;
                    if (ast.getHintStatements() != null && ast.getHintStatements().size() == 1) {
                        sqlStatement = ast.getHintStatements().get(0);
                    }
                    SqlCacheConfig sqlCacheConfig = JsonUtil.from(body, SqlCacheConfig.class);
                    if (sqlCacheConfig.getSql() == null && sqlStatement != null) {
                        sqlCacheConfig.setSql(sqlStatement.toString());
                    }

                    ops.putSqlCache(sqlCacheConfig);
                    ops.commit();

                    if (sqlStatement == null) {
                        String sql = sqlCacheConfig.getSql();
                        sqlStatement = SQLUtils.parseSingleMysqlStatement(sql);
                    }

                    MycatdbCommand.execute(dataContext, response, sqlStatement);
                    return;
                }
                if ("showSqlCaches".equalsIgnoreCase(cmd)) {
                    ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();
                    resultSetBuilder.addColumnInfo("info", JDBCType.VARCHAR);
                    if (MetaClusterCurrent.exist(SqlResultSetService.class)) {
                        SqlResultSetService sqlResultSetService = MetaClusterCurrent.wrapper(SqlResultSetService.class);
                        sqlResultSetService.snapshot().toStringList()
                                .forEach(c -> resultSetBuilder.addObjectRowPayload(Arrays.asList(c)));
                    }
                    response.sendResultSet(resultSetBuilder.build());
                    return;
                }
                if ("dropSqlCache".equalsIgnoreCase(cmd)) {
                    MycatRouterConfigOps ops = ConfigUpdater.getOps();
                    SqlCacheConfig sqlCacheConfig = JsonUtil.from(body, SqlCacheConfig.class);
                    ops.removeSqlCache(sqlCacheConfig.getName());
                    ops.commit();
                    response.sendOk();
                    return;
                }
                if ("showBufferUsage".equalsIgnoreCase(cmd)) {
                    ResultSetBuilder builder = ResultSetBuilder.create();
                    builder.addColumnInfo("bufferUsage", JDBCType.BIGINT);
                    MycatSession mycatSession = response.unWrapper(MycatSession.class);
                    builder.addObjectRowPayload(Arrays.asList(mycatSession.writeBufferPool().trace()));
                    response.sendResultSet(() -> builder.build());
                    return;
                }
                if ("showUsers".equalsIgnoreCase(cmd)) {
                    ResultSetBuilder builder = ResultSetBuilder.create();
                    builder.addColumnInfo("username", JDBCType.VARCHAR);
                    builder.addColumnInfo("ip", JDBCType.VARCHAR);
                    builder.addColumnInfo("transactionType", JDBCType.VARCHAR);
                    builder.addColumnInfo("dbType", JDBCType.VARCHAR);
                    Authenticator authenticator = MetaClusterCurrent.wrapper(Authenticator.class);
                    List<UserConfig> userConfigs = authenticator.allUsers();
                    for (UserConfig userConfig : userConfigs) {
                        builder.addObjectRowPayload(Arrays.asList(
                                userConfig.getUsername(),
                                userConfig.getPassword(),
                                userConfig.getTransactionType(),
                                userConfig.getDialect()
                        ));
                    }
                    response.sendResultSet(() -> builder.build());
                    return;
                }

                if ("showSchemas".equalsIgnoreCase(cmd)) {
                    Map map = JsonUtil.from(body, Map.class);
                    String schemaName = (String) map.get("schemaName");
                    Collection<SchemaHandler> schemaHandlers;
                    if (schemaName == null) {
                        schemaHandlers = metadataManager.getSchemaMap().values();
                    } else {
                        schemaHandlers = Collections.singletonList(
                                metadataManager.getSchemaMap().get(schemaName)
                        );
                    }
                    ResultSetBuilder builder = ResultSetBuilder.create();
                    builder.addColumnInfo("SCHEMA_NAME", JDBCType.VARCHAR)
                            .addColumnInfo("DEFAULT_TARGET_NAME", JDBCType.VARCHAR)
                            .addColumnInfo("TABLE_NAMES", JDBCType.VARCHAR);
                    for (SchemaHandler value : schemaHandlers) {
                        String SCHEMA_NAME = value.getName();
                        String DEFAULT_TARGET_NAME = value.defaultTargetName();
                        String TABLE_NAMES = String.join(",", value.logicTables().keySet());
                        builder.addObjectRowPayload(Arrays.asList(SCHEMA_NAME, DEFAULT_TARGET_NAME, TABLE_NAMES));
                    }
                    response.sendResultSet(() -> builder.build());
                    return;
                }

                if ("showTables".equalsIgnoreCase(cmd)) {
                    Map map = JsonUtil.from(body, Map.class);
                    String type = (String) map.get("type");
                    String schemaName = (String) map.get("schemaName");

                    Stream<TableHandler> tables;
                    Stream<TableHandler> tableHandlerStream;
                    if (schemaName == null) {
                        tableHandlerStream = metadataManager.getSchemaMap().values().stream().flatMap(i -> i.logicTables().values().stream());
                    } else {
                        SchemaHandler schemaHandler = Objects.requireNonNull(
                                metadataManager.getSchemaMap().get(schemaName)
                        );
                        NameMap<TableHandler> logicTables = schemaHandler.logicTables();
                        tableHandlerStream = logicTables.values().stream();
                    }
                    if ("global".equalsIgnoreCase(type)) {
                        tables = tableHandlerStream.filter(i -> i.getType() == LogicTableType.GLOBAL);
                    } else if ("sharding".equalsIgnoreCase(type)) {
                        tables = tableHandlerStream.filter(i -> i.getType() == LogicTableType.SHARDING);
                    } else if ("normal".equalsIgnoreCase(type)) {
                        tables = tableHandlerStream.filter(i -> i.getType() == LogicTableType.NORMAL);
                    } else if ("custom".equalsIgnoreCase(type)) {
                        tables = tableHandlerStream.filter(i -> i.getType() == LogicTableType.CUSTOM);
                    } else {
                        tables = tableHandlerStream;
                    }

                    ResultSetBuilder builder = ResultSetBuilder.create();
                    builder.addColumnInfo("SCHEMA_NAME", JDBCType.VARCHAR)
                            .addColumnInfo("TABLE_NAME", JDBCType.VARCHAR)
                            .addColumnInfo("CREATE_TABLE_SQL", JDBCType.VARCHAR)
                            .addColumnInfo("TYPE", JDBCType.VARCHAR)
                            .addColumnInfo("COLUMNS", JDBCType.VARCHAR)
                            .addColumnInfo("CONFIG", JDBCType.VARCHAR);
                    tables.forEach(table -> {
                        String SCHEMA_NAME = table.getSchemaName();
                        String TABLE_NAME = table.getTableName();
                        String CREATE_TABLE_SQL = table.getCreateTableSQL();
                        LogicTableType TYPE = table.getType();
                        String COLUMNS = table.getColumns().stream().map(i -> i.toString()).collect(Collectors.joining(","));
                        String CONFIG = routerConfig.getSchemas().stream()
                                .filter(i -> SCHEMA_NAME.equalsIgnoreCase(i.getSchemaName()))
                                .map(i -> {
                                    switch (TYPE) {
                                        case SHARDING:
                                            return i.getShadingTables();
                                        case GLOBAL:
                                            return i.getGlobalTables();
                                        case NORMAL:
                                            return i.getNormalTables();
                                        case CUSTOM:
                                            return i.getCustomTables();
                                        default:
                                            return null;
                                    }
                                }).map(i -> i.get(TABLE_NAME)).findFirst()
                                .map(i -> i.toString()).orElse(null);
                        builder.addObjectRowPayload(Arrays.asList(SCHEMA_NAME, TABLE_NAME, CREATE_TABLE_SQL, TYPE, COLUMNS, CONFIG));
                    });
                    response.sendResultSet(() -> builder.build());
                    return;
                }

                if ("showClusters".equalsIgnoreCase(cmd)) {
                    Map map = JsonUtil.from(body, Map.class);
                    String clusterName = (String) map.get("name");
                    RowBaseIterator rowBaseIterator = showClusters( clusterName);
                    response.sendResultSet(rowBaseIterator );
                    return;
                }
                if ("showDataSources".equalsIgnoreCase(cmd)) {

                    Map<String, DatasourceConfig> datasourceConfigMap = routerConfig.getDatasources().stream().collect(Collectors.toMap(k -> k.getName(), v -> v));
                    Optional<JdbcConnectionManager> connectionManager = Optional.ofNullable(jdbcConnectionManager);
                    Collection<JdbcDataSource> jdbcDataSources = connectionManager.map(i -> i.getDatasourceInfo()).map(i -> i.values()).orElse(Collections.emptyList());
                    ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();

                    resultSetBuilder.addColumnInfo("NAME", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("USERNAME", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("PASSWORD", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("MAX_CON", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("MIN_CON", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("EXIST_CON", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("USE_CON", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("MAX_RETRY_COUNT", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("MAX_CONNECT_TIMEOUT", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("DB_TYPE", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("URL", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("WEIGHT", JDBCType.VARCHAR);

                    resultSetBuilder.addColumnInfo("INIT_SQL", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("INIT_SQL_GET_CONNECTION", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("INSTANCE_TYPE", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("IDLE_TIMEOUT", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("DRIVER", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("TYPE", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("IS_MYSQL", JDBCType.BOOLEAN);

                    for (JdbcDataSource jdbcDataSource : jdbcDataSources) {
                        DatasourceConfig config = jdbcDataSource.getConfig();
                        String NAME = config.getName();
                        String USERNAME = config.getUser();
                        String PASSWORD = config.getPassword();
                        int MAX_CON = config.getMaxCon();
                        int MIN_CON = config.getMinCon();

                        int USED_CON = jdbcDataSource.getUsedCount();//注意显示顺序
                        int EXIST_CON = USED_CON;//jdbc连接池已经存在连接数量是内部状态,未知
                        int MAX_RETRY_COUNT = config.getMaxRetryCount();
                        long MAX_CONNECT_TIMEOUT = config.getMaxConnectTimeout();
                        String DB_TYPE = config.getDbType();
                        String URL = config.getUrl();
                        int WEIGHT = config.getWeight();
                        String INIT_SQL = Optional.ofNullable(config.getInitSqls()).map(o -> String.join(";", o)).orElse("");
                        boolean INIT_SQL_GET_CONNECTION = config.isInitSqlsGetConnection();

                        String INSTANCE_TYPE = Optional.ofNullable(replicaSelectorRuntime.getPhysicsInstanceByName(NAME)).map(i -> i.getType().name()).orElse(config.getInstanceType());
                        long IDLE_TIMEOUT = config.getIdleTimeout();
                        String DRIVER = jdbcDataSource.getDataSource().toString();//保留属性
                        String TYPE = config.getType();
                        boolean IS_MYSQL = jdbcDataSource.isMySQLType();

                        resultSetBuilder.addObjectRowPayload(Arrays.asList(NAME, USERNAME, PASSWORD, MAX_CON, MIN_CON, EXIST_CON, USED_CON,
                                MAX_RETRY_COUNT, MAX_CONNECT_TIMEOUT, DB_TYPE, URL, WEIGHT, INIT_SQL, INIT_SQL_GET_CONNECTION, INSTANCE_TYPE,
                                IDLE_TIMEOUT, DRIVER, TYPE, IS_MYSQL));
                    }
                    Map<String, Long> map = Optional
                            .ofNullable(mycatServer.getReactorManager()).map(i -> i.getList())
                            .orElse(Collections.emptyList()).stream().flatMap(i -> i.getMySQLSessionManager().getAllSessions().stream())
                            .filter(i -> !i.isIdle())
                            .collect(Collectors.groupingBy(k -> k.getDatasourceName(), Collectors.counting()));
                    for (MySQLDatasource value : mycatServer.getDatasourceMap().values()) {
                        String NAME = value.getName();
                        Optional<DatasourceConfig> e = Optional.ofNullable(datasourceConfigMap.get(NAME));

                        String IP = value.getIp();
                        int PORT = value.getPort();
                        String USERNAME = value.getUsername();
                        String PASSWORD = value.getPassword();
                        int MAX_CON = value.getSessionLimitCount();
                        int MIN_CON = value.getSessionMinCount();
                        long USED_CON = map.getOrDefault(NAME, -1L);
                        int EXIST_CON = value.getConnectionCounter();
                        int MAX_RETRY_COUNT = value.gerMaxRetry();
                        long MAX_CONNECT_TIMEOUT = value.getMaxConnectTimeout();
                        String DB_TYPE = "mysql";
                        String URL = null;
                        int WEIGHT = e.map(i -> i.getWeight()).orElse(-1);
                        String INIT_SQL = value.getInitSqlForProxy();
                        boolean INIT_SQL_GET_CONNECTION = false;

                        String INSTANCE_TYPE = Optional.ofNullable(replicaSelectorRuntime.getPhysicsInstanceByName(NAME)).map(i -> i.getType().name()).orElse(e.map(i -> i.getInstanceType()).orElse(null));
                        long IDLE_TIMEOUT = value.getIdleTimeout();

                        String DRIVER = "native";//保留属性
                        String TYPE = e.map(i -> i.getType()).orElse(null);
                        boolean IS_MYSQL = true;

                        resultSetBuilder.addObjectRowPayload(Arrays.asList(NAME, IP, PORT, USERNAME, PASSWORD, MAX_CON, MIN_CON, EXIST_CON, USED_CON,
                                MAX_RETRY_COUNT, MAX_CONNECT_TIMEOUT, DB_TYPE, URL, WEIGHT, INIT_SQL, INIT_SQL_GET_CONNECTION, INSTANCE_TYPE,
                                IDLE_TIMEOUT, DRIVER, TYPE, IS_MYSQL));
                    }
                    response.sendResultSet(() -> resultSetBuilder.build());
                    return;
                }
                if ("showHeartbeats".equalsIgnoreCase(cmd)) {
                    Map<String, DatasourceConfig> dataSourceConfig = routerConfig.getDatasources().stream().collect(Collectors.toMap(k -> k.getName(), v -> v));

                    ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();

                    resultSetBuilder.addColumnInfo("NAME", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("TYPE", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("READABLE", JDBCType.BOOLEAN);
                    resultSetBuilder.addColumnInfo("SESSION_COUNT", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("WEIGHT", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("ALIVE", JDBCType.BOOLEAN);
                    resultSetBuilder.addColumnInfo("MASTER", JDBCType.BOOLEAN);
                    resultSetBuilder.addColumnInfo("LIMIT_SESSION_COUNT", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("REPLICA", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("SLAVE_THRESHOLD", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("IS_HEARTBEAT_TIMEOUT", JDBCType.BOOLEAN);
                    resultSetBuilder.addColumnInfo("HB_ERROR_COUNT", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("HB_LAST_SWITCH_TIME", JDBCType.TIMESTAMP);
                    resultSetBuilder.addColumnInfo("HB_MAX_RETRY", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("IS_CHECKING", JDBCType.BOOLEAN);
                    resultSetBuilder.addColumnInfo("MIN_SWITCH_TIME_INTERVAL", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("HEARTBEAT_TIMEOUT", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("SYNC_DS_STATUS", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("HB_DS_STATUS", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("IS_SLAVE_BEHIND_MASTER", JDBCType.BOOLEAN);
                    resultSetBuilder.addColumnInfo("LAST_SEND_QUERY_TIME", JDBCType.TIMESTAMP);
                    resultSetBuilder.addColumnInfo("LAST_RECEIVED_QUERY_TIME", JDBCType.TIMESTAMP);


                    for (HeartbeatFlow heartbeatFlow : replicaSelectorRuntime.getHeartbeatDetectorMap().values()) {
                        PhysicsInstance instance = heartbeatFlow.instance();

                        String NAME = instance.getName();
                        String TYPE = instance.getType().name();
                        boolean READABLE = instance.asSelectRead();
                        int SESSION_COUNT = instance.getSessionCounter();
                        int WEIGHT = instance.getWeight();
                        boolean ALIVE = instance.isAlive();
                        boolean MASTER = instance.isMaster();

                        long SLAVE_THRESHOLD = heartbeatFlow.getSlaveThreshold();

                        boolean IS_HEARTBEAT_TIMEOUT = heartbeatFlow.isHeartbeatTimeout();
                        final HeartBeatStatus HEART_BEAT_STATUS = heartbeatFlow.getHbStatus();
                        int HB_ERROR_COUNT = HEART_BEAT_STATUS.getErrorCount();
                        LocalDateTime HB_LAST_SWITCH_TIME =
                                new Timestamp(HEART_BEAT_STATUS.getLastSwitchTime()).toLocalDateTime();
                        int HB_MAX_RETRY = HEART_BEAT_STATUS.getMaxRetry();
                        boolean IS_CHECKING = HEART_BEAT_STATUS.isChecking();
                        long MIN_SWITCH_TIME_INTERVAL = HEART_BEAT_STATUS.getMinSwitchTimeInterval();
                        final long HEARTBEAT_TIMEOUT = (heartbeatFlow.getHeartbeatTimeout());
                        DatasourceStatus DS_STATUS_OBJECT = heartbeatFlow.getDsStatus();
                        String SYNC_DS_STATUS = DS_STATUS_OBJECT.getDbSynStatus().name();
                        String HB_DS_STATUS = DS_STATUS_OBJECT.getStatus().name();
                        boolean IS_SLAVE_BEHIND_MASTER = DS_STATUS_OBJECT.isSlaveBehindMaster();
                        LocalDateTime LAST_SEND_QUERY_TIME =
                                new Timestamp(heartbeatFlow.getLastSendQryTime()).toLocalDateTime();
                        LocalDateTime LAST_RECEIVED_QUERY_TIME =
                                new Timestamp(heartbeatFlow.getLastReceivedQryTime()).toLocalDateTime();
                        Optional<DatasourceConfig> e = Optional.ofNullable(dataSourceConfig.get(NAME));

                        String replicaDataSourceSelectorList = String.join(",", replicaSelectorRuntime.getRepliaNameListByInstanceName(NAME));

                        resultSetBuilder.addObjectRowPayload(
                                Arrays.asList(NAME,
                                        TYPE,
                                        READABLE,
                                        SESSION_COUNT,
                                        WEIGHT,
                                        ALIVE,
                                        MASTER,
                                        e.map(i -> i.getMaxCon()).orElse(-1),
                                        replicaDataSourceSelectorList,
                                        SLAVE_THRESHOLD,
                                        IS_HEARTBEAT_TIMEOUT,
                                        HB_ERROR_COUNT,
                                        HB_LAST_SWITCH_TIME,
                                        HB_MAX_RETRY,
                                        IS_CHECKING,
                                        MIN_SWITCH_TIME_INTERVAL,
                                        HEARTBEAT_TIMEOUT,
                                        SYNC_DS_STATUS,
                                        HB_DS_STATUS,
                                        IS_SLAVE_BEHIND_MASTER,
                                        LAST_SEND_QUERY_TIME,
                                        LAST_RECEIVED_QUERY_TIME
                                ));
                    }
                    response.sendResultSet(resultSetBuilder.build());
                    return;
                }
                if ("showHeartbeatStatus".equalsIgnoreCase(cmd)) {
                    Map<String, HeartbeatFlow> heartbeatDetectorMap = replicaSelectorRuntime.getHeartbeatDetectorMap();
                    for (Map.Entry<String, HeartbeatFlow> entry : heartbeatDetectorMap.entrySet()) {
                        String key = entry.getKey();
                        HeartbeatFlow value = entry.getValue();

                        ResultSetBuilder builder = ResultSetBuilder.create();
                        builder.addColumnInfo("name", JDBCType.VARCHAR);
                        builder.addColumnInfo("status", JDBCType.VARCHAR);
                        builder.addObjectRowPayload(Arrays.asList(
                                Objects.toString(key),
                                Objects.toString(value.getDsStatus())
                        ));
                        response.sendResultSet(() -> builder.build());
                        return;
                    }
                }
                if ("showInstances".equalsIgnoreCase(cmd)) {
                    ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();
                    resultSetBuilder.addColumnInfo("NAME", JDBCType.VARCHAR);

                    resultSetBuilder.addColumnInfo("ALIVE", JDBCType.BOOLEAN);
                    resultSetBuilder.addColumnInfo("READABLE", JDBCType.BOOLEAN);
                    resultSetBuilder.addColumnInfo("TYPE", JDBCType.VARCHAR);
                    resultSetBuilder.addColumnInfo("SESSION_COUNT", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("WEIGHT", JDBCType.BIGINT);

                    resultSetBuilder.addColumnInfo("MASTER", JDBCType.BOOLEAN);
                    resultSetBuilder.addColumnInfo("LIMIT_SESSION_COUNT", JDBCType.BIGINT);
                    resultSetBuilder.addColumnInfo("REPLICA", JDBCType.VARCHAR);
                    Collection<PhysicsInstance> values =
                            replicaSelectorRuntime.getPhysicsInstanceMap().values();
                    Map<String, DatasourceConfig> dataSourceConfig = routerConfig.getDatasources().stream().collect(Collectors.toMap(k -> k.getName(), v -> v));


                    for (PhysicsInstance instance : values) {

                        String NAME = instance.getName();
                        String TYPE = instance.getType().name();
                        boolean READABLE = instance.asSelectRead();
                        int SESSION_COUNT = instance.getSessionCounter();
                        int WEIGHT = instance.getWeight();
                        boolean ALIVE = instance.isAlive();
                        boolean MASTER = instance.isMaster();

                        Optional<DatasourceConfig> e = Optional.ofNullable(dataSourceConfig.get(NAME));


                        String replicaDataSourceSelectorList = String.join(",", replicaSelectorRuntime.getRepliaNameListByInstanceName(NAME));

                        resultSetBuilder.addObjectRowPayload(
                                Arrays.asList(NAME, ALIVE, READABLE, TYPE, SESSION_COUNT, WEIGHT, MASTER,
                                        e.map(i -> i.getMaxCon()).orElse(-1),
                                        replicaDataSourceSelectorList
                                ));
                    }
                    response.sendResultSet(resultSetBuilder.build());
                    return;
                }
                if ("showReactors".equalsIgnoreCase(cmd)) {
                    ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();
                    resultSetBuilder.addColumnInfo("THREAD_NAME", JDBCType.VARCHAR)
                            .addColumnInfo("THREAD_ID", JDBCType.BIGINT)
                            .addColumnInfo("CUR_SESSION_ID", JDBCType.BIGINT)
                            .addColumnInfo("PREPARE_STOP", JDBCType.BOOLEAN)
                            .addColumnInfo("BUFFER_POOL_SNAPSHOT", JDBCType.VARCHAR)
                            .addColumnInfo("LAST_ACTIVE_TIME", JDBCType.TIMESTAMP);
                    for (MycatReactorThread mycatReactorThread : mycatServer.getReactorManager().getList()) {
                        String THREAD_NAME = mycatReactorThread.getName();
                        long THREAD_ID = mycatReactorThread.getId();
                        Integer CUR_SESSION_ID = Optional.ofNullable(mycatReactorThread.getCurSession()).map(i -> i.sessionId()).orElse(null);
                        boolean PREPARE_STOP = mycatReactorThread.isPrepareStop();
                        String BUFFER_POOL_SNAPSHOT = Optional.ofNullable(mycatReactorThread.getBufPool()).map(i -> i.snapshot().toString("|")).orElse("");
                        LocalDateTime LAST_ACTIVE_TIME = new Timestamp(mycatReactorThread.getLastActiveTime()).toLocalDateTime();
                        resultSetBuilder.addObjectRowPayload(Arrays.asList(
                                THREAD_NAME,
                                THREAD_ID,
                                CUR_SESSION_ID,
                                PREPARE_STOP,
                                BUFFER_POOL_SNAPSHOT,
                                LAST_ACTIVE_TIME
                        ));
                    }
                    response.sendResultSet(() -> resultSetBuilder.build());
                    return;
                }
                if ("showThreadPools".equalsIgnoreCase(cmd)) {
                    ResultSetBuilder builder = ResultSetBuilder.create();
                    builder.addColumnInfo("NAME", JDBCType.VARCHAR)
                            .addColumnInfo("POOL_SIZE", JDBCType.BIGINT)
                            .addColumnInfo("ACTIVE_COUNT", JDBCType.BIGINT)
                            .addColumnInfo("TASK_QUEUE_SIZE", JDBCType.BIGINT)
                            .addColumnInfo("COMPLETED_TASK", JDBCType.BIGINT)
                            .addColumnInfo("TOTAL_TASK", JDBCType.BIGINT);

                    MycatWorkerProcessor mycatWorkerProcessor = MetaClusterCurrent.wrapper(MycatWorkerProcessor.class);
                    List<NameableExecutor> nameableExecutors = Arrays.asList(mycatWorkerProcessor.getMycatWorker(),
                            mycatWorkerProcessor.getTimeWorker());

                    MycatContextThreadPool gThreadPool = mycatServer.getMycatContextThreadPool();
                    for (NameableExecutor w : nameableExecutors) {
                        builder.addObjectRowPayload(Arrays.asList(
                                w.getName(),
                                w.getPoolSize(),
                                w.getActiveCount(),
                                w.getQueue().size(),
                                w.getCompletedTaskCount(),
                                w.getTaskCount()));
                    }
                    response.sendResultSet(() -> builder.build());
                    return;
                }
                if ("showNativeBackends".equalsIgnoreCase(cmd)) {
                    ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();
                    resultSetBuilder.addColumnInfo("SESSION_ID", JDBCType.BIGINT)
                            .addColumnInfo("THREAD_NAME", JDBCType.VARCHAR)
                            .addColumnInfo("THREAD_ID", JDBCType.BIGINT)
                            .addColumnInfo("DS_NAME", JDBCType.VARCHAR)
                            .addColumnInfo("LAST_MESSAGE", JDBCType.VARCHAR)
                            .addColumnInfo("MYCAT_SESSION_ID", JDBCType.BIGINT)
                            .addColumnInfo("IS_IDLE", JDBCType.BOOLEAN)
                            .addColumnInfo("SELECT_LIMIT", JDBCType.BIGINT)
                            .addColumnInfo("IS_AUTOCOMMIT", JDBCType.BOOLEAN)
                            .addColumnInfo("IS_RESPONSE_FINISHED", JDBCType.BOOLEAN)
                            .addColumnInfo("RESPONSE_TYPE", JDBCType.VARCHAR)
                            .addColumnInfo("IS_IN_TRANSACTION", JDBCType.BOOLEAN)
                            .addColumnInfo("IS_REQUEST_SUCCESS", JDBCType.BOOLEAN)
                            .addColumnInfo("IS_READ_ONLY", JDBCType.BOOLEAN);
                    for (MycatReactorThread i : mycatServer.getReactorManager().getList()) {
                        MySQLSessionManager c = i.getMySQLSessionManager();
                        for (MySQLClientSession session : c.getAllSessions()) {

                            int SESSION_ID = session.sessionId();
                            String THREAD_NAME = i.getName();
                            long THREAD_ID = i.getId();
                            String DS_NAME = session.getDatasource().getName();
                            String LAST_MESSAGE = session.getLastMessage();
                            Integer MYCAT_SESSION_ID = Optional.ofNullable(session.getMycat()).map(m -> m.sessionId()).orElse(null);
                            boolean IS_IDLE = session.isIdle();

                            long SELECT_LIMIT = session.getSelectLimit();
                            boolean IS_AUTOCOMMIT = session.isAutomCommit() == MySQLAutoCommit.ON;
                            boolean IS_RESPONSE_FINISHED = session.isResponseFinished();
                            String RESPONSE_TYPE = Optional.ofNullable(session.getResponseType()).map(j -> j.name()).orElse(null);
                            boolean IS_IN_TRANSACTION = session.isMonopolizedByTransaction();
                            boolean IS_REQUEST_SUCCESS = session.isRequestSuccess();
                            boolean IS_READ_ONLY = session.isReadOnly();

                            resultSetBuilder.addObjectRowPayload(Arrays.asList(
                                    SESSION_ID,
                                    THREAD_NAME,
                                    THREAD_ID,
                                    DS_NAME,
                                    LAST_MESSAGE,
                                    MYCAT_SESSION_ID,
                                    IS_IDLE,
                                    SELECT_LIMIT,
                                    IS_AUTOCOMMIT,
                                    IS_RESPONSE_FINISHED,
                                    RESPONSE_TYPE,
                                    IS_IN_TRANSACTION,
                                    IS_REQUEST_SUCCESS,
                                    IS_READ_ONLY
                            ));
                        }

                    }
                    response.sendResultSet(() -> resultSetBuilder.build());
                    return;
                }
                if ("showConnections".equalsIgnoreCase(cmd)) {
                    ReactorThreadManager reactorManager = mycatServer.getReactorManager();
                    Objects.requireNonNull(reactorManager);
                    List<MycatSession> sessions = reactorManager.getList().stream().flatMap(i -> i.getFrontManager().getAllSessions().stream()).collect(Collectors.toList());

                    ResultSetBuilder builder = ResultSetBuilder.create();

                    builder.addColumnInfo("ID", JDBCType.BIGINT);
                    builder.addColumnInfo("USER_NAME", JDBCType.VARCHAR);
                    builder.addColumnInfo("HOST", JDBCType.VARCHAR);
                    builder.addColumnInfo("SCHEMA", JDBCType.VARCHAR);
                    builder.addColumnInfo("AFFECTED_ROWS", JDBCType.BIGINT);
                    builder.addColumnInfo("AUTOCOMMIT", JDBCType.BOOLEAN);
                    builder.addColumnInfo("IN_TRANSACTION", JDBCType.BOOLEAN);
                    builder.addColumnInfo("CHARSET", JDBCType.VARCHAR);
                    builder.addColumnInfo("CHARSET_INDEX", JDBCType.BIGINT);
                    builder.addColumnInfo("OPEN", JDBCType.BOOLEAN);
                    builder.addColumnInfo("SERVER_CAPABILITIES", JDBCType.BIGINT);
                    builder.addColumnInfo("ISOLATION", JDBCType.VARCHAR);
                    builder.addColumnInfo("LAST_ERROR_CODE", JDBCType.BIGINT);
                    builder.addColumnInfo("LAST_INSERT_ID", JDBCType.BIGINT);
                    builder.addColumnInfo("LAST_MESSAGE", JDBCType.VARCHAR);
                    builder.addColumnInfo("PROCESS_STATE", JDBCType.VARCHAR);
                    builder.addColumnInfo("WARNING_COUNT", JDBCType.BIGINT);
                    builder.addColumnInfo("MYSQL_SESSION_ID", JDBCType.BIGINT);
                    builder.addColumnInfo("TRANSACTION_TYPE", JDBCType.VARCHAR);
                    builder.addColumnInfo("TRANSCATION_SNAPSHOT", JDBCType.VARCHAR);
                    builder.addColumnInfo("CANCEL_FLAG", JDBCType.BOOLEAN);

                    for (MycatSession session : sessions) {
                        int ID = session.sessionId();
                        MycatUser user = session.getUser();
                        String USER_NAME = user.getUserName();
                        String HOST = user.getHost();
                        String SCHEMA = session.getSchema();
                        long AFFECTED_ROWS = session.getAffectedRows();
                        boolean AUTOCOMMIT = session.isAutocommit();
                        boolean IN_TRANSACTION = session.isInTransaction();
                        String CHARSET = Optional.ofNullable(session.charset()).map(i -> i.displayName()).orElse("");
                        int CHARSET_INDEX = session.charsetIndex();
                        boolean OPEN = session.checkOpen();
                        int SERVER_CAPABILITIES = session.getCapabilities();
                        String ISOLATION = session.getIsolation().getText();
                        int LAST_ERROR_CODE = session.getLastErrorCode();
                        long LAST_INSERT_ID = session.getLastInsertId();
                        String LAST_MESSAGE = session.getLastMessage();
                        String PROCESS_STATE = session.getProcessState().name();

                        int WARNING_COUNT = session.getWarningCount();
                        Integer MYSQL_SESSION_ID = Optional.ofNullable(session.getMySQLSession()).map(i -> i.sessionId()).orElse(null);


                        String TRANSACTION_TYPE = Optional.ofNullable(dataContext.transactionType()).map(i -> i.getName()).orElse("");

                        TransactionSession transactionSession = dataContext.getTransactionSession();
                        String TRANSCATION_SMAPSHOT = transactionSession.snapshot().toString("|");
                        boolean CANCEL_FLAG = dataContext.getCancelFlag().get();
                        builder.addObjectRowPayload(Arrays.asList(
                                ID,
                                USER_NAME,
                                HOST,
                                SCHEMA,
                                AFFECTED_ROWS,
                                AUTOCOMMIT,
                                IN_TRANSACTION,
                                CHARSET,
                                CHARSET_INDEX,
                                OPEN,
                                SERVER_CAPABILITIES,
                                ISOLATION,
                                LAST_ERROR_CODE,
                                LAST_INSERT_ID,
                                LAST_MESSAGE,
                                PROCESS_STATE,
                                WARNING_COUNT,
                                MYSQL_SESSION_ID,
                                TRANSACTION_TYPE,
                                TRANSCATION_SMAPSHOT,
                                CANCEL_FLAG
                        ));
                    }
                    response.sendResultSet(() -> builder.build());
                    return;
                }
                if ("showSchedules".equalsIgnoreCase(cmd)) {
                    ResultSetBuilder builder = ResultSetBuilder.create();
                    ScheduledExecutorService timer = ScheduleUtil.getTimer();
                    String NAME = timer.toString();
                    boolean IS_TERMINATED = timer.isTerminated();
                    boolean IS_SHUTDOWN = timer.isShutdown();
                    int SCHEDULE_COUNT = ScheduleUtil.getScheduleCount();
                    builder.addColumnInfo("NAME", JDBCType.VARCHAR)
                            .addColumnInfo("IS_TERMINATED", JDBCType.BOOLEAN)
                            .addColumnInfo("IS_SHUTDOWN", JDBCType.BOOLEAN)
                            .addColumnInfo("SCHEDULE_COUNT", JDBCType.BIGINT);
                    builder.addObjectRowPayload(Arrays.asList(NAME, IS_TERMINATED, IS_SHUTDOWN, SCHEDULE_COUNT));
                    response.sendResultSet(() -> builder.build());
                    return;
                }
                mycatDmlHandler(cmd, body, ast);
                response.sendOk();
                return;
            }
        }
        response.sendOk();
    }

    public static RowBaseIterator showClusters(String clusterName) {
        MycatRouterConfig routerConfig = MetaClusterCurrent.wrapper(MycatRouterConfig.class);
        ResultSetBuilder resultSetBuilder = ResultSetBuilder.create();
        resultSetBuilder.addColumnInfo("NAME", JDBCType.VARCHAR);
        resultSetBuilder.addColumnInfo("SWITCH_TYPE", JDBCType.VARCHAR);
        resultSetBuilder.addColumnInfo("MAX_REQUEST_COUNT", JDBCType.VARCHAR);
        resultSetBuilder.addColumnInfo("TYPE", JDBCType.VARCHAR);
        resultSetBuilder.addColumnInfo("WRITE_DS", JDBCType.VARCHAR);
        resultSetBuilder.addColumnInfo("READ_DS", JDBCType.VARCHAR);
        resultSetBuilder.addColumnInfo("WRITE_L", JDBCType.VARCHAR);
        resultSetBuilder.addColumnInfo("READ_L", JDBCType.VARCHAR);
        resultSetBuilder.addColumnInfo("AVAILABLE", JDBCType.BOOLEAN);
        Collection<ReplicaDataSourceSelector> values = MetaClusterCurrent.wrapper(ReplicaSelectorRuntime.class).getReplicaMap().values();

        Map<String, ClusterConfig> clusterConfigMap = routerConfig.getClusters().stream()
                .collect(Collectors.toMap(k -> k.getName(), v -> v));

        for (ReplicaDataSourceSelector value :
                values.stream().filter(v -> {
                    if (clusterName != null) {
                        return clusterName.equalsIgnoreCase(v.getName());
                    }
                    return true;
                }).collect(Collectors.toList())
        ) {
            String NAME = value.getName();


            Optional<ClusterConfig> e = Optional.ofNullable(clusterConfigMap.get(NAME));

            ReplicaSwitchType SWITCH_TYPE = value.getSwitchType();
            int MAX_REQUEST_COUNT = value.maxRequestCount();
            String TYPE = value.getBalanceType().name();
            String WRITE_DS = ((List<PhysicsInstance>) value.getWriteDataSource()).stream().map(i -> i.getName()).collect(Collectors.joining(","));
            String READ_DS = (value.getReadDataSource()).stream().map(i -> i.getName()).collect(Collectors.joining(","));
            String WL = Optional.ofNullable(value.getDefaultWriteLoadBalanceStrategy()).map(i -> i.getClass().getName()).orElse(null);
            String RL = Optional.ofNullable(value.getDefaultReadLoadBalanceStrategy()).map(i -> i.getClass().getName()).orElse(null);
            boolean AVAILABLE = ((List<PhysicsInstance>) value.getWriteDataSource()).stream().anyMatch(PhysicsInstance::isAlive);

            resultSetBuilder.addObjectRowPayload(
                    Arrays.asList(NAME, SWITCH_TYPE, MAX_REQUEST_COUNT, TYPE,
                            WRITE_DS, READ_DS,
                            WL, RL, AVAILABLE
                    ));
        }
        return resultSetBuilder.build();
    }

    public static void mycatDmlHandler(String cmd, String body, MySqlHintStatement ast) throws Exception {
        if ("createTable".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                CreateTableConfig createTableConfig = JsonUtil.from(body, CreateTableConfig.class);
                ops.putTable(createTableConfig);
                ops.commit();
            }
            return;
        }
        if ("dropTable".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                Map map = JsonUtil.from(body, Map.class);
                String schemaName = (String) map.get("schemaName");
                String tableName = (String) map.get("tableName");
                ops.removeTable(schemaName, tableName);
                ops.commit();
            }
            return;
        }
        if ("createDataSource".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                ops.putDatasource(JsonUtil.from(body, DatasourceConfig.class));
                ops.commit();
            }
            return;
        }
        if ("dropDataSource".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                ops.removeDatasource(JsonUtil.from(body, DatasourceConfig.class).getName());
                ops.commit();
            }
            return;
        }
        if ("createUser".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                ops.putUser(JsonUtil.from(body, UserConfig.class));
                ops.commit();
            }
            return;
        }
        if ("dropUser".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                ops.deleteUser(JsonUtil.from(body, UserConfig.class).getUsername());
                ops.commit();
            }
            return;
        }
        if ("createCluster".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                ops.putReplica(JsonUtil.from(body, ClusterConfig.class));
                ops.commit();
            }
            return;
        }
        if ("dropCluster".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                ops.removeReplica(JsonUtil.from(body, ClusterConfig.class).getName());
                ops.commit();
            }
            return;
        }
        if ("setSequence".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                ops.putSequence(JsonUtil.from(body, SequenceConfig.class));
                ops.commit();
            }
            return;
        }
        if ("createSchema".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                ops.putSchema(JsonUtil.from(body, LogicSchemaConfig.class));
                ops.commit();
            }
            return;
        }
        if ("dropSchema".equalsIgnoreCase(cmd)) {
            try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
                ops.dropSchema(JsonUtil.from(body, LogicSchemaConfig.class).getSchemaName());
                ops.commit();
            }
            return;
        }
    }
}