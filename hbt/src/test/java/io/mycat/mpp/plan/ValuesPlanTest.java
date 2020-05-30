package io.mycat.mpp.plan;

import com.alibaba.fastsql.sql.SQLUtils;
import com.alibaba.fastsql.sql.ast.statement.SQLSelectQuery;
import com.alibaba.fastsql.sql.ast.statement.SQLSelectStatement;
import io.mycat.mpp.*;
import io.mycat.mpp.runtime.Type;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ValuesPlanTest {
    final DataContext dataContext = DataContext.DEFAULT;

    @Test
    public void test() {

        ValuesPlan valuesPlan = ValuesPlan.create(
                RowType.of(
                        Column.of("id", Type.of(Type.INT, false)),
                        Column.of("name", Type.of(Type.VARCHAR, false))
                ),
                values(new Object[]{1, "1"}, new Object[]{2, "2"})
        );
        RowType columns = valuesPlan.getType();
        Scanner scan = valuesPlan.scan(dataContext, 0);
        String collect = scan.stream().map(i -> i.toString())
                .collect(Collectors.joining());

        OrderPlan orderPlan = OrderPlan.create(valuesPlan, new int[]{1}, new boolean[]{false});
        String collect1 = orderPlan.scan(dataContext, 0).stream().map(i -> i.toString()).collect(Collectors.joining());

        LimitPlan limitPlan = LimitPlan.create(orderPlan, 0, 1);

        Scanner scan1 = limitPlan.scan(dataContext, 0);
        String collect2 = scan1.stream().map(i -> i.toString()).collect(Collectors.joining());

        AggregationPlan aggregationPlan = AggregationPlan.create(limitPlan, new String[]{"count", "avg"},  Collections.singletonList(Collections.singletonList(1)),RowType.of(
                Column.of("count()", Type.of(Type.INT, false)),
                Column.of("avg(id)", Type.of(Type.DECIMAL, false))
                )
              , new int[]{});

        String collect3 = aggregationPlan.scan(dataContext, 0).stream().map(i -> i.toString()).collect(Collectors.joining());


    }

    @Test
    public void test2() {

        ValuesPlan one = ValuesPlan.create(
                RowType.of(
                        Column.of("id", Type.of(Type.INT, false)),
                        Column.of("name", Type.of(Type.VARCHAR, false))
                ),
                values(new Object[]{1, "1"}, new Object[]{2, "2"})
        );
        ValuesPlan two = ValuesPlan.create(
                RowType.of(
                        Column.of("id", Type.of(Type.INT, false)),
                        Column.of("name", Type.of(Type.INT, false))
                ),
                values(new Object[]{1, "1"}, new Object[]{2, "2"})
        );

        UnionPlan unionPlan = UnionPlan.create(Arrays.asList(one, two));
        Scanner scan = unionPlan.scan(dataContext, 0);
        String collect = scan.stream().map(i -> i.toString()).collect(Collectors.joining());
    }

    @Test
    public void test3() {
        MyRelBuilder builder = new MyRelBuilder();

        ValuesPlan one = ValuesPlan.create(
                RowType.of(
                        Column.of("id", Type.of(Type.INT, false)),
                        Column.of("name", Type.of(Type.INT, false))
                ),
                values(new Object[]{1, "1"}, new Object[]{2, "2"})
        );
        builder.push(one);
        SqlValue id = builder.field("id");
        SqlValue equality = builder.equality(id, builder.add(builder.literal(1), builder.literal(1)));

        FilterPlan queryPlan = FilterPlan.create(one, equality);
        Scanner scan = queryPlan.scan(dataContext, 0);
        String collect = scan.stream()
                .map(i -> i.toString()).collect(Collectors.joining());
    }

    private List<Object[]> values(Object[]... objects) {
        return Arrays.asList(objects);
    }


    @Test
    public void test4() {
        MyRelBuilder builder = new MyRelBuilder();
        SqlToExprTranslator sqlToExprTranslator = new SqlToExprTranslator(builder,null);
        SQLSelectStatement sqlSelectStatement = (SQLSelectStatement) SQLUtils.parseSingleMysqlStatement("SELECT @@version_comment +1  LIMIT 1");
        SQLSelectQuery query = sqlSelectStatement.getSelect().getQuery();
        QueryPlan queryPlan1 = sqlToExprTranslator.convertQueryRecursive(query);
        Scanner scan = queryPlan1.scan(dataContext, 0);
        RowType type = queryPlan1.getType();
        String collect = scan.stream().map(i -> i.toString()).collect(Collectors.joining());
    }

    @Test
    public void test5() {
        MyRelBuilder builder = new MyRelBuilder(
                (schemaName, tableName) -> {
                    return ListReadOnlyTable.create(schemaName,
                            tableName, RowType.of(Column.of("id", Type.of(Type.INT, true))),
                            Arrays.asList(new Object[]{1}, new Object[]{2})
                    );
                }


        );
        SqlToExprTranslator sqlToExprTranslator = new SqlToExprTranslator(builder,null);
        SQLSelectStatement sqlSelectStatement = (SQLSelectStatement) SQLUtils
                .parseSingleMysqlStatement("SELECT id as i from db1.dual where id = 2 LIMIT 2");
        SQLSelectQuery query = sqlSelectStatement.getSelect().getQuery();
        QueryPlan queryPlan1 = sqlToExprTranslator.convertQueryRecursive(query);
        Scanner scan = queryPlan1.scan(dataContext, 0);
        RowType type = queryPlan1.getType();
        String collect = scan.stream().map(i -> i.toString()).collect(Collectors.joining());

        System.out.println();
    }

    @Test
    public void test6() {
        MyRelBuilder builder = new MyRelBuilder(
                (schemaName, tableName) -> {
                    return ListReadOnlyTable.create(schemaName,
                            tableName, RowType.of(Column.of("id", Type.of(Type.INT, true))),
                            new ArrayList<>(Arrays.asList(new Object[]{1}, new Object[]{2}))
                    );
                }


        );
        SqlToExprTranslator sqlToExprTranslator = new SqlToExprTranslator(builder,new TranslatorBlackboard());
        SQLSelectStatement sqlSelectStatement = (SQLSelectStatement) SQLUtils
                .parseSingleMysqlStatement("SELECT  count(id)   from db1.t   group by id ORDER BY id DESC LIMIT 2");
        SQLSelectQuery query = sqlSelectStatement.getSelect().getQuery();
        QueryPlan queryPlan1 = sqlToExprTranslator.convertQueryRecursive(query);
        Scanner scan = queryPlan1.scan(dataContext, 0);
        RowType type = queryPlan1.getType();
        String collect = scan.stream().map(i -> i.toString()).collect(Collectors.joining());

        System.out.println();
    }
}