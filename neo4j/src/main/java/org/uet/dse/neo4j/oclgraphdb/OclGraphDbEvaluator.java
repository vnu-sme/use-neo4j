package org.uet.dse.neo4j.oclgraphdb;

import java.util.Optional;
import java.util.function.Function;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternOptions;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternPlan;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.multipass.impl.MultiPassCypherCompiler;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternRegistry;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherPlan;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTContext;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class OclGraphDbEvaluator {
  private final PatternPlanner planner;
  private final MultiPassPatternPlanner multiPassPlanner;
  private final MultiPassCypherCompiler multiPassCompiler;
  private final CypherExecutor executor;
  private final Function<String, SemanticResolver> resolverFactory;

  public OclGraphDbEvaluator() {
    this(PatternPlanner.withDefaultPatterns(), new CypherExecutor(), Neo4jSemanticResolver::new);
  }

  public OclGraphDbEvaluator(PatternRegistry registry) {
    this(new PatternPlanner(registry), new CypherExecutor(), Neo4jSemanticResolver::new);
  }

  public OclGraphDbEvaluator(
      PatternPlanner planner,
      CypherExecutor executor,
      Function<String, SemanticResolver> resolverFactory
  ) {
    this(planner, new MultiPassPatternPlanner(planner), new MultiPassCypherCompiler(planner), executor, resolverFactory);
  }

  public OclGraphDbEvaluator(
      PatternPlanner planner,
      MultiPassPatternPlanner multiPassPlanner,
      MultiPassCypherCompiler multiPassCompiler,
      CypherExecutor executor,
      Function<String, SemanticResolver> resolverFactory
  ) {
    this.planner = planner;
    this.multiPassPlanner = multiPassPlanner;
    this.multiPassCompiler = multiPassCompiler;
    this.executor = executor;
    this.resolverFactory = resolverFactory;
  }

  public Optional<GraphDbEvaluationResult> tryEvaluate(ASTNode ast, String modelName) {
    return tryEvaluate(ast, modelName, OclGraphDbEvaluationOptions.defaults());
  }

  public Optional<GraphDbEvaluationResult> tryEvaluate(ASTNode ast, String modelName, String selfObjectId) {
    if (selfObjectId == null || selfObjectId.isBlank()) {
      return tryEvaluate(ast, modelName);
    }
    return tryEvaluate(ast, modelName, OclGraphDbEvaluationOptions.withSelf(selfObjectId));
  }

  public Optional<GraphDbEvaluationResult> tryEvaluate(
      ASTNode ast,
      String modelName,
      OclGraphDbEvaluationOptions options
  ) {
    try {
      SemanticResolver resolver = resolverFactory.apply(modelName);
      CompileContext multiPassContext = planningContext(ast, createQueryContext(modelName, resolver, options));
      Optional<GraphDbEvaluationResult> multiPassResult = tryEvaluateMultiPass(ast, options, multiPassContext);
      if (multiPassResult.isPresent()) return multiPassResult;

      if (ast instanceof ASTContext) {
        return Optional.of(evaluateInvariant((ASTContext) ast, createQueryContext(modelName, resolver, options), planner));
      }

      CompileContext context = createQueryContext(modelName, resolver, options);
      Optional<PlanCandidate> candidate = planner.plan(ast, context, ExpectedKind.ANY);
      if (candidate.isEmpty()) return Optional.empty();

      CypherPlan plan = CypherPlan.scalar(candidate.get().expression(), context);
      Object value = executor.executeScalar(plan);
      return Optional.of(new GraphDbEvaluationResult(value, plan.toCypher(), true));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private Optional<GraphDbEvaluationResult> tryEvaluateMultiPass(
      ASTNode ast,
      OclGraphDbEvaluationOptions options,
      CompileContext context
  ) {
    try {
      MultiPassPatternPlan patternPlan = multiPassPlanner.plan(ast, context, multiPassOptions(options));
      Optional<CypherPlan> cypherPlan = multiPassCompiler.compilePlan(patternPlan, context);
      if (cypherPlan.isEmpty()) return Optional.empty();

      if (ast instanceof ASTContext astContext) {
        CypherExecutor.ValidationSummary summary = executor.executeValidation(cypherPlan.get());
        String value = summary.format(astContext.className, astContext.invName);
        return Optional.of(new GraphDbEvaluationResult(value, cypherPlan.get().toCypher(), true));
      }

      Object value = executor.executeScalar(cypherPlan.get());
      return Optional.of(new GraphDbEvaluationResult(value, cypherPlan.get().toCypher(), true));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private MultiPassPatternOptions multiPassOptions(OclGraphDbEvaluationOptions options) {
    return new MultiPassPatternOptions(
        options.autoPatternPasses(),
        options.maxPatternPasses(),
        true
    );
  }

  private CompileContext createQueryContext(
      String modelName,
      SemanticResolver resolver,
      OclGraphDbEvaluationOptions options
  ) {
    CompileContext context = CompileContext.root(modelName, resolver);
    for (var entry : options.objectBindings().entrySet()) {
      context = context.withBoundObject(entry.getKey(), entry.getValue());
    }
    return context;
  }

  private CompileContext planningContext(ASTNode ast, CompileContext context) {
    if (ast instanceof ASTContext astContext) {
      return context.withRootVariable("self", astContext.className);
    }
    return context;
  }

  private GraphDbEvaluationResult evaluateInvariant(
      ASTContext ast,
      CompileContext baseContext,
      PatternPlanner planner
  ) {
    CompileContext context = baseContext.withRootVariable("self", ast.className);
    Optional<PlanCandidate> candidate = planner.plan(ast.expression, context, ExpectedKind.BOOLEAN);
    if (candidate.isEmpty()) {
      throw new RuntimeException("Invariant is not supported by OCLGraphDB patterns.");
    }

    CypherPlan plan = CypherPlan.validation(ast.className, candidate.get().expression(), context);
    CypherExecutor.ValidationSummary summary = executor.executeValidation(plan);
    String value = summary.format(ast.className, ast.invName);
    return new GraphDbEvaluationResult(value, plan.toCypher(), true);
  }
}
