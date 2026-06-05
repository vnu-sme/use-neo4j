package org.uet.dse.neo4j.oclite.ast;

public class ASTUnary extends ASTExpression {
    public final String operator;
    public final ASTExpression expression;

    public ASTUnary(String operator, ASTExpression expression) {
        this.operator = operator;
        this.expression = expression;
    }
}
