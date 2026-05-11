package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MPrePostCondition;
import org.uet.dse.neo4j.mm.core.node.NodePreCondition;

public class UsePreConditionTranslator implements IModelTranslator<UsePreConditionTranslator.PreConditionContext, NodePreCondition> {

    public record PreConditionContext(MPrePostCondition condition, String opId, int index) {}

    @Override
    public NodePreCondition translate(PreConditionContext ctx) {
        NodePreCondition node = new NodePreCondition(ctx.opId() + "_pre_" + ctx.index());
        node.setCondName(ctx.condition().name());
        node.setExpression(ctx.condition().expression().toString());
        node.setIndex(ctx.index());
        return node;
    }
}