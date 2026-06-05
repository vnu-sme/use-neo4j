package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.List;
import java.util.Optional;

public interface RewritePatternSelector {
  Optional<RewritePatternCandidate> select(List<RewritePatternCandidate> candidates);
}
