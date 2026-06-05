package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import org.uet.dse.neo4j.oclgraphdb.Neo4jSemanticResolver;
import org.uet.dse.neo4j.oclgraphdb.SemanticResolver;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherPlan;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.rewrite.compile.PatternTreeCypherCompiler;
import org.uet.dse.neo4j.oclite.ast.ASTContext;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class PatternTreeRewriteService {
  private final PatternTreeRewritePlanner planner;
  private final PatternTreeCypherCompiler compiler;
  private final Function<String, SemanticResolver> resolverFactory;

  public PatternTreeRewriteService() {
    this(PatternPlanner.withDefaultPatterns(), Neo4jSemanticResolver::new);
  }

  public PatternTreeRewriteService(
      PatternPlanner legacyPlanner,
      Function<String, SemanticResolver> resolverFactory
  ) {
    this.planner = new PatternTreeRewritePlanner(legacyPlanner);
    this.compiler = new PatternTreeCypherCompiler(legacyPlanner);
    this.resolverFactory = resolverFactory;
  }

  public RewritePatternPlan plan(ASTNode ast, String modelName) {
    return plan(ast, modelName, RewritePatternOptions.defaults());
  }

  public RewritePatternPlan plan(ASTNode ast, String modelName, RewritePatternOptions options) {
    return plan(ast, modelName, options, Map.of());
  }

  public RewritePatternPlan plan(
      ASTNode ast,
      String modelName,
      RewritePatternOptions options,
      Map<String, String> objectBindings
  ) {
    SemanticResolver resolver = resolverFactory.apply(modelName);
    CompileContext context = planningContext(ast, createContext(modelName, resolver, objectBindings));
    return planner.plan(ast, context, options);
  }

  public Optional<CypherPlan> compile(ASTNode ast, String modelName) {
    return compile(ast, modelName, RewritePatternOptions.defaults());
  }

  public Optional<CypherPlan> compile(ASTNode ast, String modelName, RewritePatternOptions options) {
    return compile(ast, modelName, options, Map.of());
  }

  public Optional<CypherPlan> compile(
      ASTNode ast,
      String modelName,
      RewritePatternOptions options,
      Map<String, String> objectBindings
  ) {
    SemanticResolver resolver = resolverFactory.apply(modelName);
    CompileContext context = planningContext(ast, createContext(modelName, resolver, objectBindings));
    RewritePatternPlan plan = planner.plan(ast, context, options);
    return compiler.compilePlan(plan, context);
  }

  private CompileContext createContext(
      String modelName,
      SemanticResolver resolver,
      Map<String, String> objectBindings
  ) {
    CompileContext context = CompileContext.root(modelName, resolver);
    if (objectBindings == null) return context;
    for (Map.Entry<String, String> entry : objectBindings.entrySet()) {
      if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank()) {
        context = context.withBoundObject(entry.getKey(), entry.getValue());
      }
    }
    return context;
  }

  private CompileContext planningContext(ASTNode ast, CompileContext context) {
    if (ast instanceof ASTContext astContext) {
      CompileContext scoped = context.withRootVariable("self", astContext.className);
      if (astContext.contextVariableName != null && !astContext.contextVariableName.isBlank()) {
        scoped = scoped.withExpressionBinding(astContext.contextVariableName, new CypherExpression("self", ExpressionKind.NODE), astContext.className);
      }
      return scoped;
    }
    return context;
  }
}
