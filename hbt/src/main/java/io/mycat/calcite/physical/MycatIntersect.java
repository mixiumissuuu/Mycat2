/**
 * Copyright (C) <2020>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite.physical;


import io.mycat.calcite.*;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Intersect;
import org.apache.calcite.rel.metadata.RelMetadataQuery;

import java.util.List;

/**
 * Intersect operator implemented in Mycat convention.
 */
public class MycatIntersect
        extends Intersect
        implements MycatRel {
    protected MycatIntersect(
            RelOptCluster cluster,
            RelTraitSet traitSet,
            List<RelNode> inputs,
            boolean all) {
        super(cluster, traitSet, inputs, all);
    }
    public static MycatIntersect create(
            RelTraitSet traitSet,
            List<RelNode> inputs,
            boolean all) {
        RelOptCluster cluster = inputs.get(0).getCluster();
        RelMetadataQuery mq = cluster.getMetadataQuery();
        traitSet = traitSet.replace(MycatConvention.INSTANCE);
        return new MycatIntersect(cluster,traitSet,inputs,all);
    }
    public MycatIntersect copy(
            RelTraitSet traitSet, List<RelNode> inputs, boolean all) {
        return new MycatIntersect(getCluster(), traitSet, inputs, all);
    }


    @Override
    public ExplainWriter explain(ExplainWriter writer) {
        writer.name("MycatIntersect").into();
        for (RelNode input : getInputs()) {
            MycatRel rel = (MycatRel) input;
            rel.explain(writer);
        }
        return writer.ret();
    }


    @Override
    public Executor implement(ExecutorImplementor implementor) {
        return implementor.implement(this);
    }
}