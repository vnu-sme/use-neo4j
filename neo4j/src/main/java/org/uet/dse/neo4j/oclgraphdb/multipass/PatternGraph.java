package org.uet.dse.neo4j.oclgraphdb.multipass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class PatternGraph {
  private final ASTNode root;
  private final Map<ASTNode, List<PatternInstance>> byAstNode = new IdentityHashMap<>();
  private final Map<String, PatternInstance> bySignature = new LinkedHashMap<>();
  private final List<PatternInstance> all = new ArrayList<>();
  private int nextId = 1;

  public PatternGraph(ASTNode root) {
    this.root = root;
  }

  public ASTNode getRoot() {
    return root;
  }

  public Optional<PatternInstance> add(PatternInstance.Builder builder, int passNumber) {
    PatternInstance candidate = builder.build(nextId, passNumber);
    String graphKey = graphKey(candidate);
    if (bySignature.containsKey(graphKey)) {
      return Optional.empty();
    }

    nextId++;
    bySignature.put(graphKey, candidate);
    all.add(candidate);
    byAstNode.computeIfAbsent(candidate.getAstNode(), ignored -> new ArrayList<>()).add(candidate);
    byAstNode.get(candidate.getAstNode()).sort(Comparator.comparingInt(PatternInstance::getScore).reversed());
    return Optional.of(candidate);
  }

  public List<PatternInstance> patternsFor(ASTNode node) {
    return List.copyOf(byAstNode.getOrDefault(node, List.of()));
  }

  public List<PatternInstance> patternsFor(ASTNode node, MultiPassPatternKind kind) {
    return patternsFor(node).stream()
        .filter(pattern -> pattern.getKind() == kind)
        .toList();
  }

  public Optional<PatternInstance> bestFor(ASTNode node) {
    List<PatternInstance> patterns = byAstNode.getOrDefault(node, List.of());
    return patterns.isEmpty() ? Optional.empty() : Optional.of(patterns.get(0));
  }

  public Optional<PatternInstance> bestFor(ASTNode node, MultiPassPatternKind kind) {
    return patternsFor(node, kind).stream()
        .max(Comparator.comparingInt(PatternInstance::getScore));
  }

  public Optional<PatternInstance> bestAny(ASTNode node, MultiPassPatternKind... kinds) {
    List<MultiPassPatternKind> accepted = List.of(kinds);
    return patternsFor(node).stream()
        .filter(pattern -> accepted.contains(pattern.getKind()))
        .max(Comparator.comparingInt(PatternInstance::getScore));
  }

  public boolean hasKind(ASTNode node, MultiPassPatternKind kind) {
    return patternsFor(node).stream().anyMatch(pattern -> pattern.getKind() == kind);
  }

  public List<PatternInstance> allPatterns() {
    return List.copyOf(all);
  }

  public List<PatternInstance> rootPatterns() {
    return patternsFor(root);
  }

  private String graphKey(PatternInstance instance) {
    return System.identityHashCode(instance.getAstNode()) + "|" + instance.signature();
  }
}
