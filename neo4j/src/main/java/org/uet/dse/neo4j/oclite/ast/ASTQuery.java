package org.uet.dse.neo4j.oclite.ast;

import java.util.List;

public class ASTQuery extends ASTExpression {
    public final ASTExpression source;
    public final String operation;
    public final List<ASTVarDecl> variables;
    public final ASTExpression body;

    public ASTQuery(ASTExpression source, String operation, List<ASTVarDecl> variables, ASTExpression body) {
        this.source = source;
        this.operation = operation;
        this.variables = List.copyOf(variables);
        this.body = body;
    }

    public boolean hasExplicitVariables() {
        return !variables.isEmpty();
    }

    public String primaryVariableName() {
        return variables.isEmpty() ? "it" : variables.get(0).name;
    }
}
