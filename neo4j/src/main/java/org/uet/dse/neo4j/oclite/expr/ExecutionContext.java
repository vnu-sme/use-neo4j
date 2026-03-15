package org.uet.dse.neo4j.oclite.expr;

import org.neo4j.driver.types.Node;
import org.uet.dse.neo4j.model.ObjectState;
import org.uet.dse.neo4j.oclite.Neo4jRepository;
import java.util.*;
import java.util.stream.Collectors;

public class ExecutionContext {
    private final Neo4jRepository repo;
    private final String modelName;

    private final Map<String, Object> localVariables = new HashMap<>();

    private final Deque<Map<String, Object>> scopeStack = new ArrayDeque<>();

    public ExecutionContext(String modelName) {
        this.repo = new Neo4jRepository(modelName);
        this.modelName = modelName;
        scopeStack.push(localVariables);
    }


    public void pushScope(String varName, Object value) {
        Map<String, Object> newScope = new HashMap<>();
        newScope.put(varName, value);
        scopeStack.push(newScope);
    }

    public void popScope() {
        if (scopeStack.size() > 1) scopeStack.pop();
    }


    public Object resolveVariable(String name) {
        for (Map<String, Object> scope : scopeStack) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return repo.findNodeById(name);
    }

    public String getModelName() {
        return modelName;
    }

    public Object resolveProperty(Object source, String propertyName) {
        if (source == null) return null;

        if (source instanceof Collection) {
            Collection<?> collection = (Collection<?>) source;

            List<Object> results = collection.stream()
                .map(item -> resolveProperty(item, propertyName))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            if (results.size() == 1) return results.get(0);
            return results;
        }

        if (source instanceof Node) {
            return repo.getAttributeValue((Node) source, propertyName);
        }


        return null;
    }

    public Object resolveNavigation(Object source, String relationshipType) {
        if (source == null) return Collections.emptyList();

        if (source instanceof Collection) {
            return ((Collection<?>) source).stream()
                .flatMap(item -> ((Collection<?>) resolveNavigation(item, relationshipType)).stream())
                .distinct()
                .collect(Collectors.toList());
        }

        if (source instanceof Node) {
            return repo.findRelatedNodes((Node) source, relationshipType);
        }

        return Collections.emptyList();
    }

    public Object resolveCollectionOperation(Object source, String opName, List<Object> args) {
        Collection<?> collection;
        if (source instanceof Collection) {
            collection = (Collection<?>) source;
        } else if (source == null) {
            collection = Collections.emptyList();
        } else {
            collection = Collections.singletonList(source);
        }

        switch (opName.toLowerCase()) {
            case "size":
                return (double) collection.size();

            case "isempty":
                return collection.isEmpty();

            case "notempty":
                return !collection.isEmpty();

            case "includes":
                Object target = args.get(0);
                return collection.contains(target);

            default:
                throw new RuntimeException("Not yet supported: " + opName);
        }
    }
}