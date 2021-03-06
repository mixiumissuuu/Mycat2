package io.mycat.sqlhandler.dcl;

import com.alibaba.fastsql.sql.dialect.mysql.ast.statement.MySqlKillStatement;
import io.mycat.MycatDataContext;
import io.mycat.sqlhandler.AbstractSQLHandler;
import io.mycat.sqlhandler.SQLRequest;
import io.mycat.Response;




public class KillSQLHandler extends AbstractSQLHandler<MySqlKillStatement> {

    @Override
    protected void onExecute(SQLRequest<MySqlKillStatement> request, MycatDataContext dataContext, Response response) throws Exception {
        response.sendOk();
    }
}
