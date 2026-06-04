package org.uet.dse.neo4j.oclgraphdb.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class PatternPlanner {
  private static final int BEAM_WIDTH = 4;

  private final PatternRegistry registry;

  public PatternPlanner(PatternRegistry registry) {
    this.registry = registry;
  }

  public static PatternPlanner withDefaultPatterns() {
    return new PatternPlanner(PatternRegistry.defaults());
  }

  public Optional<PlanCandidate> plan(ASTNode node, CompileContext context, ExpectedKind expected) {
    List<PlanCandidate> candidates = planMany(node, context, expected);
    return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
  }

  public List<PlanCandidate> planMany(ASTNode node, CompileContext context, ExpectedKind expected) {
    if (node == null) return List.of();

    List<PlanCandidate> candidates = new ArrayList<>();
    for (OclPattern pattern : registry.patterns()) {
      if (!pattern.supports(node)) continue;
      for (PlanCandidate candidate : pattern.match(node, context, expected, this)) {
        if (expected.accepts(candidate.expression().getKind())) {
          candidates.add(candidate);
        }
      }
    }

    Collections.sort(candidates);
    if (candidates.size() <= BEAM_WIDTH) return candidates;
    return List.copyOf(candidates.subList(0, BEAM_WIDTH));
  }
}
