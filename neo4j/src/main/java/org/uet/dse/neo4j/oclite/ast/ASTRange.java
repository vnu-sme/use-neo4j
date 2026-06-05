package org.uet.dse.neo4j.oclite.ast;

public class ASTRange extends ASTExpression {
    public final ASTExpression start;
    public final ASTExpression end;

    public ASTRange(ASTExpression start, ASTExpression end) {
        this.start = start;
        this.end = end;
    }
}
