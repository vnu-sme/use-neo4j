package org.uet.dse.neo4j.oclgraphdb.pattern;

import java.util.ArrayList;
import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.AllInstancesIteratorPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.AllInstancesSelectTerminalPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.BinaryPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.CollectionLiteralPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.CollectionOpPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.IfPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.IteratePattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.IteratorPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.LetPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.LiteralPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.MethodCallPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.NavigationIteratorPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.NavigationSelectTerminalPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.NotPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.PropertyPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.QualifiedNavigationPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.QueryPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.RangePattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.UnaryPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.impl.VariablePattern;

public class PatternRegistry {
  private final List<OclPattern> patterns = new ArrayList<>();

  public PatternRegistry register(OclPattern pattern) {
    patterns.add(pattern);
    return this;
  }

  public List<OclPattern> patterns() {
    return List.copyOf(patterns);
  }

  public static PatternRegistry defaults() {
    return new PatternRegistry()
        .register(new LiteralPattern())
        .register(new VariablePattern())
        .register(new RangePattern())
        .register(new CollectionLiteralPattern())
        .register(PropertyPattern.INSTANCE)
        .register(new QualifiedNavigationPattern())
        .register(new MethodCallPattern())
        .register(new CollectionOpPattern())
        .register(new AllInstancesSelectTerminalPattern())
        .register(new NavigationSelectTerminalPattern())
        .register(new AllInstancesIteratorPattern())
        .register(new NavigationIteratorPattern())
        .register(new IteratorPattern())
        .register(new QueryPattern())
        .register(new IteratePattern())
        .register(new LetPattern())
        .register(new IfPattern())
        .register(new UnaryPattern())
        .register(new BinaryPattern())
        .register(new NotPattern());
  }
}
