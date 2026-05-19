package org.uet.dse.neo4j.oclite.expr;

import java.util.Collection;
import java.util.Collections;

public class OneExpression implements ExpressionNode {
    private final ExpressionNode source;
    private final String iteratorName;
    private final ExpressionNode body;

    public OneExpression(ExpressionNode source, String var, ExpressionNode body) {
        this.source = source;
        this.iteratorName = var;
        this.body = body;
    }

    @Override
    public Object evaluate(ExecutionContext ctx) {
        Object sourceVal = source.evaluate(ctx);
        Collection<?> collection = convertToCollection(sourceVal);

        int matches = 0;
        for (Object item : collection) {
            ctx.pushScope(iteratorName, item);
            Object isMatch = body.evaluate(ctx);
            ctx.popScope();

            if (isMatch instanceof Boolean && (Boolean) isMatch) {
                matches++;
                if (matches > 1) return false;
            }
        }

        return matches == 1;
    }

    private Collection<?> convertToCollection(Object obj) {
        if (obj instanceof Collection) return (Collection<?>) obj;
        if (obj == null) return Collections.emptyList();
        return Collections.singletonList(obj);
    }
}
