package org.uet.dse.neo4j.oclite.expr;

public class TypeCheckExpression implements ExpressionNode {
    private final ExpressionNode source;
    private final String typeName;
    private final boolean includeSubtypes;

    public TypeCheckExpression(ExpressionNode source, String typeName, boolean includeSubtypes) {
        this.source = source;
        this.typeName = typeName;
        this.includeSubtypes = includeSubtypes;
    }

    @Override
    public Object evaluate(ExecutionContext ctx) {
        Object value = source.evaluate(ctx);
        return includeSubtypes ? ctx.isKindOf(value, typeName) : ctx.isTypeOf(value, typeName);
    }
}
