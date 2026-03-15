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

    public Object resolveCollectionOperation(Object source, String opName, List<Object> args) {
        if (!(source instanceof Collection)) {
            source = source == null ? Collections.emptyList() : Collections.singletonList(source);
        }

        Collection<?> col = (Collection<?>) source;
        switch (opName.toLowerCase()) {
            case "size": return (long) col.size();
            case "isempty": return col.isEmpty();
            case "notempty": return !col.isEmpty();
            case "includes": return col.contains(args.get(0));
            default:
                throw new UnsupportedOperationException("Chưa hỗ trợ hàm tập hợp: " + opName);
        }
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
}