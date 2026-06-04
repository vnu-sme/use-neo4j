package org.uet.dse.neo4j.oclgraphdb.multipass;

public interface PatternPass {
  String name();

  int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber);
}
