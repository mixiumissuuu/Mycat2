package io.mycat.calcite.rewriter;

import io.mycat.calcite.MycatRel;
import io.mycat.calcite.spm.Plan;
import io.mycat.calcite.spm.PlanCache;
import io.mycat.calcite.spm.PlanImpl;
import lombok.Getter;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMetadataQuery;

import java.util.List;

@Getter
public class OptimizationContext {
    boolean predicateOnPhyView = false;
    boolean complex = false;
    boolean predicateOnView = false;
    public final List<Object> params;

    public OptimizationContext(List<Object> params, PlanCache planCache) {
        this.params = params;
        this.planCache = planCache;
    }

    final PlanCache planCache;

    public void saveAlways(String p, MycatRel mycatRel) {
        RelOptCost relOptCost = getDefaultRelOptCost(mycatRel);
        planCache.put(p, new PlanImpl(Plan.Type.FINAL, relOptCost, mycatRel));
    }

    private RelOptCost getDefaultRelOptCost(RelNode mycatRel) {
        RelOptCluster cluster = mycatRel.getCluster();
        RelOptPlanner planner = cluster.getPlanner();
        RelMetadataQuery mq = cluster.getMetadataQuery();
        return mycatRel.computeSelfCost(planner, mq);
    }

    public void setPredicateOnPhyView(boolean b) {
        this.predicateOnPhyView = b;
    }

    public void setComplex(boolean complex) {
        this.complex = complex;
    }

    public void setPredicateOnView(boolean b) {
        this.predicateOnView = b;
    }

    public void saveParameterized(String drdsSql, MycatRel mycatRel) {
        saveAlways(drdsSql, mycatRel);
    }

    public void saveParse(String drdsSql, RelNode o) {
        planCache.put(drdsSql, new PlanImpl(Plan.Type.PARSE, getDefaultRelOptCost(o), o));
    }
}