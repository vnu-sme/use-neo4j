package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class DefaultRewritePatternSelector implements RewritePatternSelector {
  public static final DefaultRewritePatternSelector INSTANCE = new DefaultRewritePatternSelector();

  private static final Comparator<RewritePatternCandidate> ORDERING =
      Comparator.comparingInt(RewritePatternCandidate::weightedScore)
          .thenComparing(RewritePatternCandidate::getName);

  @Override
  public Optional<RewritePatternCandidate> select(List<RewritePatternCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) return Optional.empty();
    return candidates.stream()
        .filter(candidate -> candidate.getReplacement() != null)
        .max(ORDERING);
  }
}
