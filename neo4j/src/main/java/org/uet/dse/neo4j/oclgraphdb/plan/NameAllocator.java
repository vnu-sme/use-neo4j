package org.uet.dse.neo4j.oclgraphdb.plan;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * generate unique but controllable  name
 */
public class NameAllocator {
  private final AtomicInteger counter = new AtomicInteger();

  public String next(String prefix) {
    String cleanPrefix = prefix == null || prefix.isBlank() ? "v" : prefix.replaceAll("[^a-zA-Z0-9_]", "_");
    return "__" + cleanPrefix + "_" + counter.incrementAndGet();
  }
}
