package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MPrePostCondition;
import org.uet.dse.neo4j.mm.core.node.NodePostCondition;

public class UsePostConditionTranslator implements IModelTranslator<UsePostConditionTranslator.PostConditionContext, NodePostCondition> {

    public record PostConditionContext(MPrePostCondition condition, String opId, int index) {}

    @Override
    public NodePostCondition translate(PostConditionContext ctx) {
        NodePostCondition node = new NodePostCondition(ctx.opId() + "_post_" + ctx.index());
        node.setCondName(ctx.condition().name());
        node.setExpression(ctx.condition().expression().toString());
        node.setIndex(ctx.index());
        return node;
    }
}