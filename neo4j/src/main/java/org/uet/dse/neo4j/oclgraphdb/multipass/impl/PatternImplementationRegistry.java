package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.List;

public final class PatternImplementationRegistry {
  private PatternImplementationRegistry() {}

  public static List<PatternImplementation> defaults() {
    return List.of(
        new RootPatternImplementation(),
        new AttributeAccessImplementation(),
        new NavigationAccessImplementation(),
        new ComparePatternImplementation(),
        new LogicalPatternImplementation(),
        new NegationPatternImplementation(),
        new CollectionSourceImplementation(),
        new FilterPatternImplementation(),
        new QuantifierPatternImplementation(),
        new FilteredTerminalImplementation(),
        new LegacyPatternImplementation()
    );
  }
}
