package org.uet.dse.neo4j.oclite.expr;

import java.lang.reflect.Field;
import org.neo4j.driver.types.Node;

public class PropertyExpression implements ExpressionNode {
    private ExpressionNode source;
    private String propertyName;

    public PropertyExpression(ExpressionNode source, String propertyName) {
        this.source = source;
        this.propertyName = propertyName;
    }

    @Override
    public Object evaluate(ExecutionContext ctx) {
        Object obj = source.evaluate(ctx);
        if (obj instanceof Node) {
            Node neo4jNode = (Node) obj;
            var value = neo4jNode.get(propertyName);
            if (value.isNull()) return null;

            if (value.type().name().equals("INTEGER")) return value.asLong();
            if (value.type().name().equals("FLOAT")) return value.asDouble();
            if (value.type().name().equals("STRING")) return value.asString();
            return value.asObject();
        }
        return null;
    }
}