package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.mm.MOperation;
import org.uet.dse.neo4j.helper.TypeMapper;
import org.uet.dse.neo4j.mm.core.common.NType;
import org.uet.dse.neo4j.mm.core.node.NodeOperation;

import java.util.Map;

public class UseOperationTranslator implements IModelTranslator<UseOperationTranslator.OperationContext, NodeOperation> {

    public record OperationContext(MClass cls, MOperation op) {}

    @Override
    public NodeOperation translate(OperationContext ctx) {
        MClass cls = ctx.cls();
        MOperation op = ctx.op();
        String opId = cls.name() + "_" + op.name();

        Map<String, Object> rInfo = TypeMapper.parseTypeUSE2Neo4j(op.resultType());

        NodeOperation node = new NodeOperation(opId);
        node.setOpName(op.name());
        node.setReturnType(NType.fromString((String) rInfo.get("type")));
        node.setReturnCollection((Boolean) rInfo.get("isCollection"));
        node.setAbstract(cls.isAbstract());
        node.setBody(resolveBody(op));
        node.setQuery(resolveIsQuery(op));

        return node;
    }

    private String resolveBody(MOperation op) {
        if (op.hasExpression()) return op.expression().toString();
        if (op.hasStatement()) return op.getStatement().toString();
        return null;
    }

    private boolean resolveIsQuery(MOperation op) {
        if (op.hasExpression()) return true;
        if (op.hasStatement()) return false;
        return true; 
    }
}