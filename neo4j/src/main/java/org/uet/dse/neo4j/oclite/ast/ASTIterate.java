package org.uet.dse.neo4j.oclite.ast;

public class ASTIterate extends ASTExpression {
    public final ASTExpression source;
    public final ASTVarDecl iterator;
    public final ASTLetBinding accumulator;
    public final ASTExpression body;

    public ASTIterate(ASTExpression source, ASTVarDecl iterator, ASTLetBinding accumulator, ASTExpression body) {
        this.source = source;
        this.iterator = iterator;
        this.accumulator = accumulator;
        this.body = body;
    }
}
