package org.uet.dse.neo4j.oclgraphdb.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.uet.dse.neo4j.oclgraphdb.SemanticResolver;

public class CompileContext {
  private final String modelName;
  private final SemanticResolver resolver;
  private final NameAllocator names;
  private final Map<String, Object> params;
  private final Set<String> rootVariables;
  private final Map<String, String> objectBindings;
  private final Map<String, String> variableTypes;
  private final Map<String, CypherExpression> expressionBindings;
  private final Deque<Set<String>> localScopes;

  private CompileContext(
      String modelName,
      SemanticResolver resolver,
      NameAllocator names,
      Map<String, Object> params,
      Set<String> rootVariables,
      Map<String, String> objectBindings,
      Map<String, String> variableTypes,
      Map<String, CypherExpression> expressionBindings,
      Deque<Set<String>> localScopes
  ) {
    this.modelName = modelName;
    this.resolver = resolver;
    this.names = names;
    this.params = params;
    this.rootVariables = rootVariables;
    this.objectBindings = objectBindings;
    this.variableTypes = variableTypes;
    this.expressionBindings = expressionBindings;
    this.localScopes = localScopes;
  }

  public static CompileContext root(String modelName, SemanticResolver resolver) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("modelName", modelName);
    Deque<Set<String>> scopes = new ArrayDeque<>();
    scopes.push(new LinkedHashSet<>());
    return new CompileContext(
        modelName,
        resolver,
        new NameAllocator(),
        params,
        new LinkedHashSet<>(),
        new LinkedHashMap<>(),
        new LinkedHashMap<>(),
        new LinkedHashMap<>(),
        scopes
    );
  }

  public CompileContext withRootVariable(String name) {
    Set<String> roots = new LinkedHashSet<>(rootVariables);
    roots.add(name);
    return new CompileContext(modelName, resolver, names, params, roots, copyObjectBindings(), copyVariableTypes(), copyExpressionBindings(), copyScopes());
  }

  public CompileContext withRootVariable(String name, String typeName) {
    Set<String> roots = new LinkedHashSet<>(rootVariables);
    roots.add(name);
    Map<String, String> types = copyVariableTypes();
    if (typeName != null && !typeName.isBlank()) {
      types.put(name, typeName);
    }
    return new CompileContext(modelName, resolver, names, params, roots, copyObjectBindings(), types, copyExpressionBindings(), copyScopes());
  }

  public CompileContext withBoundObject(String variableName, String objectId) {
    Set<String> roots = new LinkedHashSet<>(rootVariables);
    roots.add(variableName);
    Map<String, String> bindings = copyObjectBindings();
    bindings.put(variableName, objectId);
    Map<String, String> types = copyVariableTypes();
    resolver.objectClass(objectId).ifPresent(type -> types.put(variableName, type));
    return new CompileContext(modelName, resolver, names, params, roots, bindings, types, copyExpressionBindings(), copyScopes());
  }

  public CompileContext enterScope(String name) {
    return enterScope(name, null);
  }

  public CompileContext enterScope(String name, String typeName) {
    Deque<Set<String>> scopes = copyScopes();
    Set<String> top = new LinkedHashSet<>();
    top.add(name);
    scopes.push(top);
    Map<String, String> types = copyVariableTypes();
    if (typeName != null && !typeName.isBlank()) {
      types.put(name, typeName);
    }
    return new CompileContext(modelName, resolver, names, params, rootVariables, copyObjectBindings(), types, copyExpressionBindings(), scopes);
  }

  public CompileContext withExpressionBinding(String name, CypherExpression expression) {
    return withExpressionBinding(name, expression, null);
  }

  public CompileContext withExpressionBinding(String name, CypherExpression expression, String typeName) {
    Deque<Set<String>> scopes = copyScopes();
    Set<String> top = new LinkedHashSet<>();
    top.add(name);
    scopes.push(top);
    Map<String, CypherExpression> expressions = copyExpressionBindings();
    expressions.put(name, expression);
    Map<String, String> types = copyVariableTypes();
    if (typeName != null && !typeName.isBlank()) {
      types.put(name, typeName);
    }
    return new CompileContext(modelName, resolver, names, params, rootVariables, copyObjectBindings(), types, expressions, scopes);
  }

  public boolean isLocalVariable(String name) {
    if (rootVariables.contains(name)) return true;
    for (Set<String> scope : localScopes) {
      if (scope.contains(name)) return true;
    }
    return false;
  }

  public String addParam(String baseName, Object value) {
    String name = names.next(baseName);
    params.put(name, value);
    return "$" + name;
  }

  public String nextName(String prefix) {
    return names.next(prefix);
  }

  public boolean hasObjectBinding(String variableName) {
    return objectBindings.containsKey(variableName) && !isIteratorVariable(variableName);
  }

  public String objectBinding(String variableName) {
    return objectBindings.get(variableName);
  }

  public boolean hasExpressionBinding(String variableName) {
    return expressionBindings.containsKey(variableName);
  }

  public CypherExpression expressionBinding(String variableName) {
    return expressionBindings.get(variableName);
  }

  public java.util.Optional<String> variableType(String variableName) {
    return java.util.Optional.ofNullable(variableTypes.get(variableName));
  }

  public List<String> visibleVariables() {
    List<String> variables = new ArrayList<>();
    for (Set<String> scope : localScopes) {
      variables.addAll(scope);
    }
    variables.addAll(rootVariables);
    return List.copyOf(variables);
  }

  public String getModelName() {
    return modelName;
  }

  public SemanticResolver resolver() {
    return resolver;
  }

  public Map<String, Object> params() {
    return params;
  }

  private Deque<Set<String>> copyScopes() {
    Deque<Set<String>> copy = new ArrayDeque<>();
    List<Set<String>> ordered = new ArrayList<>(localScopes);
    for (int i = ordered.size() - 1; i >= 0; i--) {
      copy.push(new LinkedHashSet<>(ordered.get(i)));
    }
    return copy;
  }

  private Map<String, String> copyObjectBindings() {
    return new LinkedHashMap<>(objectBindings);
  }

  private Map<String, String> copyVariableTypes() {
    return new LinkedHashMap<>(variableTypes);
  }

  private Map<String, CypherExpression> copyExpressionBindings() {
    return new LinkedHashMap<>(expressionBindings);
  }

  private boolean isIteratorVariable(String name) {
    for (Set<String> scope : localScopes) {
      if (scope.contains(name)) return true;
    }
    return false;
  }
}
