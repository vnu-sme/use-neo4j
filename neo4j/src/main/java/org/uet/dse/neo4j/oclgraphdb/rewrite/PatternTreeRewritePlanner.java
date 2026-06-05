package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.ArrayList;
import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.rewrite.pass.CompareTreePass;
import org.uet.dse.neo4j.oclgraphdb.rewrite.pass.ControlFlowTreePass;
import org.uet.dse.neo4j.oclgraphdb.rewrite.pass.QueryTreePass;
import org.uet.dse.neo4j.oclgraphdb.rewrite.pass.RootTreePass;
import org.uet.dse.neo4j.oclgraphdb.rewrite.pass.SemanticPropertyTreePass;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class PatternTreeRewritePlanner {
  private final PatternPlanner legacyPlanner;
  private final List<RewritePass> passes;
  private final RewritePatternSelector patternSelector;

  public PatternTreeRewritePlanner() {
    this(PatternPlanner.withDefaultPatterns(), defaultPasses());
  }

  public PatternTreeRewritePlanner(PatternPlanner legacyPlanner) {
    this(legacyPlanner, defaultPasses());
  }

  public PatternTreeRewritePlanner(PatternPlanner legacyPlanner, List<RewritePass> passes) {
    this(legacyPlanner, passes, DefaultRewritePatternSelector.INSTANCE);
  }

  public PatternTreeRewritePlanner(
      PatternPlanner legacyPlanner,
      List<RewritePass> passes,
      RewritePatternSelector patternSelector
  ) {
    this.legacyPlanner = legacyPlanner;
    this.passes = List.copyOf(passes);
    this.patternSelector = patternSelector == null ? DefaultRewritePatternSelector.INSTANCE : patternSelector;
  }

  public RewritePatternPlan plan(ASTNode root, CompileContext compileContext, RewritePatternOptions options) {
    RewritePatternOptions effectiveOptions = options == null ? RewritePatternOptions.defaults() : options;
    RewritePatternNodeFactory factory = new RewritePatternNodeFactory();
    RewriteContext context = new RewriteContext(compileContext, legacyPlanner, factory, patternSelector);
    RewritePatternNode initialRoot = new AstPatternTreeBuilder(factory).build(root);
    RewritePatternTree initialTree = new RewritePatternTree(initialRoot);
    RewritePatternNode current = initialRoot;
    int maxPasses = effectiveMaxPasses(initialTree, effectiveOptions);
    List<RewritePassSummary> summaries = new ArrayList<>();
    boolean fixpoint = false;
    int executedRounds = 0;

    for (int round = 1; round <= maxPasses; round++) {
      executedRounds = round;
      String beforeRound = current.structuralSignature();
      for (RewritePass pass : passes) {
        int rewritten = 0;
        String error = null;
        try {
          RewritePassResult result = pass.apply(current, context, round);
          current = result.getRoot();
          rewritten = result.getRewrittenNodes();
        } catch (RuntimeException ex) {
          error = ex.getMessage();
        }
        summaries.add(new RewritePassSummary(round, pass.name(), rewritten, error));
      }
      String afterRound = current.structuralSignature();
      if (effectiveOptions.isStopOnFixpoint() && beforeRound.equals(afterRound)) {
        fixpoint = true;
        break;
      }
    }

    return new RewritePatternPlan(
        initialTree,
        new RewritePatternTree(current),
        summaries,
        maxPasses,
        executedRounds,
        fixpoint
    );
  }

  private int effectiveMaxPasses(RewritePatternTree tree, RewritePatternOptions options) {
    if (!options.isAutoPasses()) return Math.max(1, options.getMaxPasses());
    if (options.getMaxPasses() > 0) return options.getMaxPasses();
    int complexity = 2 + tree.maxDepth() / 2 + tree.countNodes() / 8;
    return Math.max(3, Math.min(12, complexity));
  }

  private static List<RewritePass> defaultPasses() {
    return List.of(
        new SemanticPropertyTreePass(),
        new CompareTreePass(),
        new QueryTreePass(),
        new ControlFlowTreePass(),
        new RootTreePass()
    );
  }
}
