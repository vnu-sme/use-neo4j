package org.uet.dse.neo4j.oclgraphdb.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.uet.dse.neo4j.oclgraphdb.Neo4jSemanticResolver;
import org.uet.dse.neo4j.oclgraphdb.OclGraphDbEvaluationOptions;
import org.uet.dse.neo4j.oclgraphdb.SemanticResolver;
import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternOptions;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternPlan;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternGraph;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.*;

public class OclAnalysisService {
  private static final int MAX_CANDIDATES_PER_NODE = 6;

  private final PatternPlanner planner;
  private final MultiPassPatternPlanner multiPassPlanner;
  private final Function<String, SemanticResolver> resolverFactory;

  public OclAnalysisService() {
    this(PatternPlanner.withDefaultPatterns(), new MultiPassPatternPlanner(), Neo4jSemanticResolver::new);
  }

  public OclAnalysisService(
      PatternPlanner planner,
      Function<String, SemanticResolver> resolverFactory
  ) {
    this(planner, new MultiPassPatternPlanner(planner), resolverFactory);
  }

  public OclAnalysisService(
      PatternPlanner planner,
      MultiPassPatternPlanner multiPassPlanner,
      Function<String, SemanticResolver> resolverFactory
  ) {
    this.planner = planner;
    this.multiPassPlanner = multiPassPlanner;
    this.resolverFactory = resolverFactory;
  }

  public OclAnalysisResult analyze(
      ASTNode ast,
      String modelName,
      OclGraphDbEvaluationOptions options
  ) {
    SemanticResolver resolver = resolverFactory.apply(modelName);
    CompileContext context = createRootContext(modelName, resolver, options);
    CompileContext planningContext = ast instanceof ASTContext astContext
        ? contextWithSelf(astContext, context)
        : context;
    MultiPassPatternPlan multiPassPlan = multiPassPlanner.plan(ast, planningContext, multiPassOptions(options));
    AtomicInteger ids = new AtomicInteger(1);
    return new OclAnalysisResult(analyzeNode(ast, planningContext, multiPassPlan.getGraph(), 1, ids), multiPassPlan);
  }

  private MultiPassPatternOptions multiPassOptions(OclGraphDbEvaluationOptions options) {
    return new MultiPassPatternOptions(
        options.autoPatternPasses(),
        options.maxPatternPasses(),
        true
    );
  }

  private CompileContext createRootContext(
      String modelName,
      SemanticResolver resolver,
      OclGraphDbEvaluationOptions options
  ) {
    CompileContext context = CompileContext.root(modelName, resolver);
    for (Map.Entry<String, String> entry : options.objectBindings().entrySet()) {
      context = context.withBoundObject(entry.getKey(), entry.getValue());
    }
    return context;
  }

  private OclAstAnalysisNode analyzeNode(
      ASTNode node,
      CompileContext context,
      PatternGraph patternGraph,
      int depth,
      AtomicInteger ids
  ) {
    int id = ids.getAndIncrement();
    List<String> notes = new ArrayList<>();
    List<OclPatternCandidateAnalysis> candidates = collectCandidates(node, context, notes);
    List<PatternInstance> multiPassPatterns = patternGraph.patternsFor(node).stream().limit(10).toList();
    List<OclAstAnalysisNode> children = new ArrayList<>();

    if (node instanceof ASTContext astContext) {
      CompileContext selfContext = contextWithSelf(astContext, context);
      children.add(analyzeNode(astContext.expression, selfContext, patternGraph, depth + 1, ids));
    } else if (node instanceof ASTBinary binary) {
      children.add(analyzeNode(binary.left, context, patternGraph, depth + 1, ids));
      children.add(analyzeNode(binary.right, context, patternGraph, depth + 1, ids));
    } else if (node instanceof ASTProperty property) {
      children.add(analyzeNode(property.source, context, patternGraph, depth + 1, ids));
    } else if (node instanceof ASTMethodCall methodCall) {
      children.add(analyzeNode(methodCall.source, context, patternGraph, depth + 1, ids));
      for (ASTExpression arg : methodCall.args) {
        children.add(analyzeNode(arg, context, patternGraph, depth + 1, ids));
      }
    } else if (node instanceof ASTCollectionOp collectionOp) {
      children.add(analyzeNode(collectionOp.source, context, patternGraph, depth + 1, ids));
      for (ASTExpression arg : collectionOp.args) {
        children.add(analyzeNode(arg, context, patternGraph, depth + 1, ids));
      }
    } else if (node instanceof ASTIterator iterator) {
      children.add(analyzeNode(iterator.source, context, patternGraph, depth + 1, ids));
      CompileContext iteratorContext = iteratorContext(iterator, context, notes);
      children.add(analyzeNode(iterator.body, iteratorContext, patternGraph, depth + 1, ids));
    } else if (node instanceof ASTQuery query) {
      children.add(analyzeNode(query.source, context, patternGraph, depth + 1, ids));
      CompileContext queryContext = queryContext(query, context, notes);
      children.add(analyzeNode(query.body, queryContext, patternGraph, depth + 1, ids));
    } else if (node instanceof ASTIterate iterate) {
      children.add(analyzeNode(iterate.source, context, patternGraph, depth + 1, ids));
      children.add(analyzeNode(iterate.accumulator.value, context, patternGraph, depth + 1, ids));
      CompileContext iterateContext = context.enterScope(iterate.iterator.name, iterate.iterator.typeName)
          .enterScope(iterate.accumulator.variable.name, iterate.accumulator.variable.typeName);
      children.add(analyzeNode(iterate.body, iterateContext, patternGraph, depth + 1, ids));
    } else if (node instanceof ASTLet let) {
      CompileContext scoped = context;
      for (ASTLetBinding binding : let.bindings) {
        children.add(analyzeNode(binding.value, scoped, patternGraph, depth + 1, ids));
        scoped = bindLetForAnalysis(binding, scoped);
      }
      children.add(analyzeNode(let.body, scoped, patternGraph, depth + 1, ids));
    } else if (node instanceof ASTIf ifNode) {
      children.add(analyzeNode(ifNode.condition, context, patternGraph, depth + 1, ids));
      children.add(analyzeNode(ifNode.thenExpression, context, patternGraph, depth + 1, ids));
      children.add(analyzeNode(ifNode.elseExpression, context, patternGraph, depth + 1, ids));
    } else if (node instanceof ASTUnary unary) {
      children.add(analyzeNode(unary.expression, context, patternGraph, depth + 1, ids));
    } else if (node instanceof ASTCollectionLiteral literal) {
      for (ASTExpression item : literal.items) {
        children.add(analyzeNode(item, context, patternGraph, depth + 1, ids));
      }
    } else if (node instanceof ASTRange range) {
      children.add(analyzeNode(range.start, context, patternGraph, depth + 1, ids));
      children.add(analyzeNode(range.end, context, patternGraph, depth + 1, ids));
    } else if (node instanceof ASTNot not) {
      children.add(analyzeNode(not.expression, context, patternGraph, depth + 1, ids));
    }

    return new OclAstAnalysisNode(
        id,
        depth,
        node == null ? "null" : node.getClass().getSimpleName(),
        AstPatternSupport.labelOf(node),
        candidates,
        multiPassPatterns,
        notes,
        children
    );
  }

  private CompileContext iteratorContext(
      ASTIterator iterator,
      CompileContext context,
      List<String> notes
  ) {
    try {
      return AstPatternSupport.inferCollectionElementType(iterator.source, context)
          .map(type -> context.enterScope(iterator.iteratorName, type))
          .orElseGet(() -> context.enterScope(iterator.iteratorName));
    } catch (RuntimeException ex) {
      notes.add("Iterator variable type inference failed: " + ex.getMessage());
      return context.enterScope(iterator.iteratorName);
    }
  }

  private CompileContext queryContext(ASTQuery query, CompileContext context, List<String> notes) {
    CompileContext scoped = context;
    String inferredType = null;
    try {
      inferredType = AstPatternSupport.inferCollectionElementType(query.source, context).orElse(null);
    } catch (RuntimeException ex) {
      notes.add("Query variable type inference failed: " + ex.getMessage());
    }

    if (query.variables.isEmpty()) {
      return scoped.enterScope("it", inferredType);
    }
    for (ASTVarDecl variable : query.variables) {
      scoped = scoped.enterScope(variable.name, variable.hasType() ? variable.typeName : inferredType);
    }
    return scoped;
  }

  private CompileContext bindLetForAnalysis(ASTLetBinding binding, CompileContext context) {
    CypherExpression expression;
    try {
      expression = planner.plan(binding.value, context, ExpectedKind.ANY)
          .map(PlanCandidate::expression)
          .orElse(new CypherExpression("null", ExpressionKind.VALUE));
    } catch (RuntimeException ex) {
      expression = new CypherExpression("null", ExpressionKind.VALUE);
    }
    return context.withExpressionBinding(binding.variable.name, expression, binding.variable.typeName);
  }

  private CompileContext contextWithSelf(ASTContext ast, CompileContext context) {
    CompileContext scoped = context.withRootVariable("self", ast.className);
    if (ast.contextVariableName != null && !ast.contextVariableName.isBlank()) {
      scoped = scoped.withExpressionBinding(ast.contextVariableName, new CypherExpression("self", ExpressionKind.NODE), ast.className);
    }
    return scoped;
  }

  private List<OclPatternCandidateAnalysis> collectCandidates(
      ASTNode node,
      CompileContext context,
      List<String> notes
  ) {
    if (node == null) return List.of();

    try {
      Map<String, OclPatternCandidateAnalysis> unique = new LinkedHashMap<>();
      for (PlanCandidate candidate : planner.planMany(node, context, ExpectedKind.ANY)) {
        OclPatternCandidateAnalysis analysis = toAnalysis(candidate, ExpectedKind.ANY);
        unique.putIfAbsent(candidateKey(analysis), analysis);
      }
      return unique.values().stream()
          .sorted(Comparator.comparingInt(OclPatternCandidateAnalysis::getScore).reversed())
          .limit(MAX_CANDIDATES_PER_NODE)
          .toList();
    } catch (RuntimeException ex) {
      notes.add("Pattern planning failed: " + ex.getMessage());
      return List.of();
    }
  }

  private OclPatternCandidateAnalysis toAnalysis(PlanCandidate candidate, ExpectedKind expectedKind) {
    return new OclPatternCandidateAnalysis(
        candidate.getPatternName(),
        expectedKind,
        candidate.expression().getKind(),
        candidate.getScore(),
        candidate.expression().getCoveredNodes(),
        candidate.expression().getCost(),
        candidate.isExactSemantic(),
        candidate.expression().getExpression()
    );
  }

  private String candidateKey(OclPatternCandidateAnalysis candidate) {
    return candidate.getPatternName()
        + "|"
        + candidate.getExpectedKind()
        + "|"
        + candidate.getExpressionKind()
        + "|"
        + candidate.getCypherExpression();
  }

}
