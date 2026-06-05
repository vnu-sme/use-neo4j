package org.uet.dse.neo4j.oclite.ast;

import java.util.List;

public class ASTLet extends ASTExpression {
    public final List<ASTLetBinding> bindings;
    public final ASTExpression body;

    public ASTLet(List<ASTLetBinding> bindings, ASTExpression body) {
        this.bindings = List.copyOf(bindings);
        this.body = body;
    }
}
