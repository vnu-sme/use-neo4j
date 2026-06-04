package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternPlan;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherPlan;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTContext;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class MultiPassCypherCompiler {
  private final PatternPlanner legacyPlanner;
  private final List<PatternImplementation> implementations;

  public MultiPassCypherCompiler(PatternPlanner legacyPlanner) {
    this(legacyPlanner, PatternImplementationRegistry.defaults());
  }

  public MultiPassCypherCompiler(
      PatternPlanner legacyPlanner,
      List<PatternImplementation> implementations
  ) {
    this.legacyPlanner = legacyPlanner;
    this.implementations = List.copyOf(implementations);
  }

  public Optional<CypherPlan> compilePlan(MultiPassPatternPlan plan, CompileContext context) {
    ASTNode root = plan.getGraph().getRoot();
    if (root instanceof ASTContext astContext) {
      Optional<PatternInstance> invariant = plan.getGraph().bestFor(root, MultiPassPatternKind.CONTEXT_INVARIANT);
      Optional<PatternInstance> predicatePattern = invariant
          .flatMap(pattern -> pattern.getChildren().stream().findFirst())
          .or(() -> bestBooleanRoot(plan, astContext.expression));
      if (predicatePattern.isEmpty()) return Optional.empty();

      CompileContext scoped = context.withRootVariable("self", astContext.className);
      Optional<CypherExpression> predicate = compileExpression(predicatePattern.get(), scoped, ExpectedKind.BOOLEAN);
      return predicate.map(expr -> CypherPlan.validation(astContext.className, expr, scoped));
    }

    Optional<PatternInstance> rootPattern = bestRootPattern(plan);
    if (rootPattern.isEmpty()) return Optional.empty();
    return compileExpression(rootPattern.get(), context, ExpectedKind.ANY)
        .map(expr -> CypherPlan.scalar(expr, context));
  }

  public Optional<CypherExpression> compileExpression(
      PatternInstance pattern,
      CompileContext context,
      ExpectedKind expected
  ) {
    for (PatternImplementation implementation : implementations) {
      if (!implementation.supports(pattern)) continue;
      Optional<CypherExpression> expression = implementation.compile(pattern, this, context);
      if (expression.isPresent() && expected.accepts(expression.get().getKind())) {
        return expression;
      }
    }
    return compileLegacy(pattern.getAstNode(), context, expected);
  }

  public Optional<CypherExpression> compileLegacy(ASTNode node, CompileContext context, ExpectedKind expected) {
    Optional<PlanCandidate> candidate = legacyPlanner.plan(node, context, expected);
    return candidate.map(PlanCandidate::expression);
  }

  public Optional<CypherExpression> compileChild(
      PatternInstance pattern,
      int index,
      CompileContext context,
      ExpectedKind expected
  ) {
    if (index < 0 || index >= pattern.getChildren().size()) return Optional.empty();
    return compileExpression(pattern.getChildren().get(index), context, expected);
  }

  public ExpectedKind expectedFor(ExpressionKind kind) {
    if (kind == null) return ExpectedKind.ANY;
    return switch (kind) {
      case BOOLEAN -> ExpectedKind.BOOLEAN;
      case NODE -> ExpectedKind.NODE;
      case COLLECTION -> ExpectedKind.COLLECTION;
      case TYPE -> ExpectedKind.TYPE;
      case VALUE -> ExpectedKind.VALUE;
    };
  }

  public List<String> mergePreludes(CypherExpression... expressions) {
    List<String> prelude = new ArrayList<>();
    for (CypherExpression expression : expressions) {
      prelude.addAll(expression.getPreludeClauses());
    }
    return prelude;
  }

  private Optional<PatternInstance> bestRootPattern(MultiPassPatternPlan plan) {
    return plan.getGraph().rootPatterns().stream()
        .filter(pattern -> pattern.getKind() == MultiPassPatternKind.SCALAR_QUERY)
        .findFirst()
        .or(() -> plan.getGraph().rootPatterns().stream()
            .filter(pattern -> pattern.getKind() != MultiPassPatternKind.CONTEXT_INVARIANT)
            .max(Comparator.comparingInt(PatternInstance::getScore)));
  }

  private Optional<PatternInstance> bestBooleanRoot(MultiPassPatternPlan plan, ASTNode node) {
    return plan.getGraph().patternsFor(node).stream()
        .filter(pattern -> pattern.getOutputKind() == ExpressionKind.BOOLEAN
            || pattern.getKind() == MultiPassPatternKind.COMPARE
            || pattern.getKind() == MultiPassPatternKind.ATTRIBUTE_COMPARE
            || pattern.getKind() == MultiPassPatternKind.NAVIGATION_COMPARE
            || pattern.getKind() == MultiPassPatternKind.LOGICAL_COMPOSITION
            || pattern.getKind() == MultiPassPatternKind.NEGATION
            || pattern.getKind() == MultiPassPatternKind.QUANTIFIER)
        .max(Comparator.comparingInt(PatternInstance::getScore));
  }
}
