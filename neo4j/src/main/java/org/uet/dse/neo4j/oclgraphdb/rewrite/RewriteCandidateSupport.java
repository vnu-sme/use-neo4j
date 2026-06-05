package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.List;
import java.util.Optional;

public final class RewriteCandidateSupport {
  private RewriteCandidateSupport() {}

  public static RewritePatternNode selectOrOriginal(
      RewritePatternNode original,
      List<RewritePatternCandidate> candidates,
      RewriteContext context,
      int round
  ) {
    Optional<RewritePatternCandidate> selected = context.patternSelector().select(candidates);
    if (selected.isEmpty()) return original;

    RewritePatternCandidate candidate = selected.get();
    return candidate.getReplacement()
        .toBuilder(context.nodeFactory(), round)
        .metadata("selectedCandidate", candidate.getName())
        .metadata("candidateCount", candidates == null ? 0 : candidates.size())
        .metadata("candidateScore", candidate.weightedScore())
        .metadata("candidateReason", candidate.getReason())
        .build();
  }
}
