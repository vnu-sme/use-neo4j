package org.uet.dse.neo4j.oclite.ast;

public class ASTIf extends ASTExpression {
    public final ASTExpression condition;
    public final ASTExpression thenExpression;
    public final ASTExpression elseExpression;

    public ASTIf(ASTExpression condition, ASTExpression thenExpression, ASTExpression elseExpression) {
        this.condition = condition;
        this.thenExpression = thenExpression;
        this.elseExpression = elseExpression;
    }
}
