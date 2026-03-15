package org.uet.dse.neo4j.oclite.expr;

import java.util.Collection;
import java.util.List;

public class CollectionOperationExpression implements ExpressionNode {
    private ExpressionNode source;
    private String opName;
    private List<ExpressionNode> args;

    public CollectionOperationExpression(ExpressionNode sourceNode, String opName, List<ExpressionNode> bindedArgs) {
    }

    @Override
    public Object evaluate(ExecutionContext ctx) {
        Object collection = source.evaluate(ctx);
        if (opName.equals("size")) {
            return (double) ((Collection<?>) collection).size();
        }

        return null;
    }
}