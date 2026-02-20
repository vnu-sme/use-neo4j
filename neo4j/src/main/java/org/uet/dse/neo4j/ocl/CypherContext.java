package org.uet.dse.neo4j.ocl;

import java.util.*;

public class CypherContext {
    private int aliasCount = 0;

    private Stack<Map<String, String>> scopeStack = new Stack<>();

    public CypherContext() {
        scopeStack.push(new HashMap<>()); // Global scope
    }

    public String generateAlias() {
        return "n" + (++aliasCount);
    }

    public void pushScope() {
        scopeStack.push(new HashMap<>(scopeStack.peek()));
    }

    public void popScope() {
        scopeStack.pop();
    }

    public void bind(String oclVar, String cypherAlias) {
        scopeStack.peek().put(oclVar, cypherAlias);
    }

    public String resolve(String oclVar) {
        return scopeStack.peek().getOrDefault(oclVar, oclVar);
    }
}