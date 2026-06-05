package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTCollectionOp;

public class FilteredTerminalImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.FILTERED_TERMINAL;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (!(pattern.getAstNode() instanceof ASTCollectionOp terminal)) return Optional.empty();
    if (pattern.getChildren().isEmpty()) return Optional.empty();
    PatternInstance source = pattern.getChildren().get(0);

    Optional<CypherExpression> optimized = compileOptimizedTerminal(source, terminal, compiler, context);
    if (optimized.isPresent()) return optimized;

    Optional<CypherExpression> collection = compiler.compileExpression(source, context, ExpectedKind.COLLECTION);
    if (collection.isEmpty()) return Optional.empty();
    String expression = switch (terminal.opName) {
      case "size" -> "size(" + collection.get().getExpression() + ")";
      case "isEmpty" -> "size(" + collection.get().getExpression() + ") = 0";
      case "isNotEmpty", "notEmpty" -> "size(" + collection.get().getExpression() + ") > 0";
      default -> null;
    };
    if (expression == null) return Optional.empty();
    ExpressionKind kind = "size".equals(terminal.opName) ? ExpressionKind.VALUE : ExpressionKind.BOOLEAN;
    return Optional.of(CypherExpression.combine(expression, kind, collection.get()));
  }

  private Optional<CypherExpression> compileOptimizedTerminal(
      PatternInstance filter,
      ASTCollectionOp terminal,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (filter.getKind() != MultiPassPatternKind.FILTER) return Optional.empty();
    if ("isEmpty".equals(terminal.opName)) {
      return FilteredQueryCypher.existsMatches(filter, false, compiler, context);
    }
    if ("isNotEmpty".equals(terminal.opName) || "notEmpty".equals(terminal.opName)) {
      return FilteredQueryCypher.existsMatches(filter, true, compiler, context);
    }
    if (!"size".equals(terminal.opName)) return Optional.empty();
    return FilteredQueryCypher.countMatches(filter, compiler, context);
  }
}
