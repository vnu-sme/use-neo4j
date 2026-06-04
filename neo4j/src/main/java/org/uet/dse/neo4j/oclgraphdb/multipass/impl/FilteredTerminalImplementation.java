package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTCollectionOp;
import org.uet.dse.neo4j.oclite.ast.ASTIterator;

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
      case "isNotEmpty" -> "size(" + collection.get().getExpression() + ") > 0";
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
    if (filter.getKind() != MultiPassPatternKind.FILTER || !(filter.getAstNode() instanceof ASTIterator iterator)) {
      return Optional.empty();
    }
    if (filter.getChildren().isEmpty()) return Optional.empty();

    PatternInstance source = filter.getChildren().get(0);
    Optional<PatternInstance> bodyPattern = filter.getChildren().size() > 1
        ? Optional.of(filter.getChildren().get(1))
        : Optional.empty();

    Optional<List<String>> matchClauses = CollectionSourceCypher.matchClauses(source, iterator.iteratorName, compiler, context);
    if (matchClauses.isEmpty()) return Optional.empty();

    CompileContext scoped = AstPatternSupport.inferCollectionElementType(iterator.source, context)
        .map(type -> context.enterScope(iterator.iteratorName, type))
        .orElseGet(() -> context.enterScope(iterator.iteratorName));
    Optional<CypherExpression> body = bodyPattern
        .flatMap(pattern -> compiler.compileExpression(pattern, scoped, ExpectedKind.BOOLEAN))
        .or(() -> compiler.compileLegacy(iterator.body, scoped, ExpectedKind.BOOLEAN));
    if (body.isEmpty()) return Optional.empty();

    String countVar = context.nextName("count");
    String predicate = "reject".equalsIgnoreCase(iterator.operation)
        ? "NOT (" + body.get().getExpression() + ")"
        : body.get().getExpression();

    List<String> prelude = new ArrayList<>();
    prelude.add("CALL {");
    prelude.addAll(CollectionSourceCypher.indent(matchClauses.get(), "  "));
    prelude.addAll(CollectionSourceCypher.indent(body.get().getPreludeClauses(), "  "));
    prelude.add("  WITH *");
    prelude.add("  WHERE " + predicate);
    prelude.add("  RETURN count(DISTINCT " + iterator.iteratorName + ") AS " + countVar);
    prelude.add("}");

    String expression = switch (terminal.opName) {
      case "size" -> countVar;
      case "isEmpty" -> countVar + " = 0";
      case "isNotEmpty" -> countVar + " > 0";
      default -> null;
    };
    if (expression == null) return Optional.empty();
    ExpressionKind kind = "size".equals(terminal.opName) ? ExpressionKind.VALUE : ExpressionKind.BOOLEAN;
    return Optional.of(new CypherExpression(expression, kind, prelude, body.get().getCoveredNodes() + 3, body.get().getCost()));
  }
}
