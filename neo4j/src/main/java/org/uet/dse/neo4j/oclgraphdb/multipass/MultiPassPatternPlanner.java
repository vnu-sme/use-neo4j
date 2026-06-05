package org.uet.dse.neo4j.oclgraphdb.multipass;

import java.util.ArrayList;
import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.AdvancedCollectionCompositionPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.CompareCompositionPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.CollectionSourcePass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.DateTimeOperationPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.FilteredCompareCompositionPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.ImplicitPropertyPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.IteratorCompositionPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.PartialRecursiveCompositionPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.PrimitivePatternPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.RootCompositionPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.SemanticPropertyPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.SizeCompareCompositionPass;
import org.uet.dse.neo4j.oclgraphdb.multipass.pass.TerminalCompositionPass;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class MultiPassPatternPlanner {
  private final List<PatternPass> passes;
  private final PatternPlanner legacyPlanner;

  public MultiPassPatternPlanner() {
    this(PatternPlanner.withDefaultPatterns(), defaultPasses());
  }

  public MultiPassPatternPlanner(PatternPlanner legacyPlanner) {
    this(legacyPlanner, defaultPasses());
  }

  public MultiPassPatternPlanner(PatternPlanner legacyPlanner, List<PatternPass> passes) {
    this.legacyPlanner = legacyPlanner;
    this.passes = List.copyOf(passes);
  }

  public MultiPassPatternPlan plan(ASTNode root, CompileContext context, MultiPassPatternOptions options) {
    MultiPassPatternOptions effectiveOptions = options == null ? MultiPassPatternOptions.defaults() : options;
    PatternGraph graph = new PatternGraph(root);
    MultiPassPatternContext passContext = new MultiPassPatternContext(context, legacyPlanner);
    int maxPasses = effectiveMaxPasses(root, effectiveOptions);
    List<PatternPassSummary> summaries = new ArrayList<>();
    boolean fixpoint = false;
    int executedRounds = 0;

    for (int round = 1; round <= maxPasses; round++) {
      executedRounds = round;
      int addedThisRound = 0;
      for (PatternPass pass : passes) {
        int added;
        String error = null;
        try {
          added = pass.apply(graph, passContext, round);
        } catch (RuntimeException ex) {
          added = 0;
          error = ex.getMessage();
        }
        addedThisRound += added;
        summaries.add(new PatternPassSummary(round, pass.name(), added, error));
      }
      if (effectiveOptions.isStopOnFixpoint() && addedThisRound == 0) {
        fixpoint = true;
        break;
      }
    }

    return new MultiPassPatternPlan(graph, summaries, maxPasses, executedRounds, fixpoint);
  }

  private int effectiveMaxPasses(ASTNode root, MultiPassPatternOptions options) {
    if (!options.isAutoPasses()) {
      return Math.max(1, options.getMaxPasses());
    }
    if (options.getMaxPasses() > 0) {
      return options.getMaxPasses();
    }
    int nodeCount = AstPatternSupport.count(root);
    int depth = AstPatternSupport.maxDepth(root);
    int complexity = 2 + depth / 2 + nodeCount / 8;
    return Math.max(3, Math.min(10, complexity));
  }

  private static List<PatternPass> defaultPasses() {
    return List.of(
        new PrimitivePatternPass(),
        new SemanticPropertyPass(),
        new ImplicitPropertyPass(),
        new DateTimeOperationPass(),
        new CollectionSourcePass(),
        new CompareCompositionPass(),
        new IteratorCompositionPass(),
        new TerminalCompositionPass(),
        new SizeCompareCompositionPass(),
        new FilteredCompareCompositionPass(),
        new AdvancedCollectionCompositionPass(),
        new PartialRecursiveCompositionPass(),
        new RootCompositionPass()
    );
  }
}
