package org.uet.dse.neo4j.oclgraphdb.analysis;

import java.util.ArrayList;
import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;

public class OclAstAnalysisNode {
  private final int id;
  private final int depth;
  private final String nodeType;
  private final String label;
  private final List<OclPatternCandidateAnalysis> candidates;
  private final List<PatternInstance> multiPassPatterns;
  private final List<String> notes;
  private final List<OclAstAnalysisNode> children;

  public OclAstAnalysisNode(
      int id,
      int depth,
      String nodeType,
      String label,
      List<OclPatternCandidateAnalysis> candidates,
      List<String> notes,
      List<OclAstAnalysisNode> children
  ) {
    this(id, depth, nodeType, label, candidates, List.of(), notes, children);
  }

  public OclAstAnalysisNode(
      int id,
      int depth,
      String nodeType,
      String label,
      List<OclPatternCandidateAnalysis> candidates,
      List<PatternInstance> multiPassPatterns,
      List<String> notes,
      List<OclAstAnalysisNode> children
  ) {
    this.id = id;
    this.depth = depth;
    this.nodeType = nodeType;
    this.label = label;
    this.candidates = List.copyOf(candidates);
    this.multiPassPatterns = List.copyOf(multiPassPatterns);
    this.notes = List.copyOf(notes);
    this.children = List.copyOf(children);
  }

  public int getId() {
    return id;
  }

  public int getDepth() {
    return depth;
  }

  public String getNodeType() {
    return nodeType;
  }

  public String getLabel() {
    return label;
  }

  public List<OclPatternCandidateAnalysis> getCandidates() {
    return candidates;
  }

  public List<PatternInstance> getMultiPassPatterns() {
    return multiPassPatterns;
  }

  public List<String> getNotes() {
    return notes;
  }

  public List<OclAstAnalysisNode> getChildren() {
    return children;
  }

  public int countNodes() {
    int total = 1;
    for (OclAstAnalysisNode child : children) {
      total += child.countNodes();
    }
    return total;
  }

  public int maxDepth() {
    int max = depth;
    for (OclAstAnalysisNode child : children) {
      max = Math.max(max, child.maxDepth());
    }
    return max;
  }

  public int countPatternMatchedNodes() {
    int total = candidates.isEmpty() && multiPassPatterns.isEmpty() ? 0 : 1;
    for (OclAstAnalysisNode child : children) {
      total += child.countPatternMatchedNodes();
    }
    return total;
  }

  public int countPatternCandidates() {
    int total = candidates.size() + multiPassPatterns.size();
    for (OclAstAnalysisNode child : children) {
      total += child.countPatternCandidates();
    }
    return total;
  }

  public OclPatternCandidateAnalysis bestCandidate() {
    return candidates.isEmpty() ? null : candidates.get(0);
  }

  public String toTreeLabel() {
    StringBuilder sb = new StringBuilder();
    sb.append("#").append(id)
        .append(" depth=").append(depth)
        .append(" ").append(label);
    OclPatternCandidateAnalysis best = bestCandidate();
    if (best != null) {
      sb.append("  [best=")
          .append(best.getPatternName())
          .append(", score=")
          .append(best.getScore())
          .append("]");
    } else if (!multiPassPatterns.isEmpty()) {
      PatternInstance pattern = multiPassPatterns.get(0);
      sb.append("  [best=")
          .append(pattern.getKind())
          .append(", score=")
          .append(pattern.getScore())
          .append("]");
    }
    return sb.toString();
  }

  public List<OclAstAnalysisNode> flatten() {
    List<OclAstAnalysisNode> result = new ArrayList<>();
    collectInto(result);
    return result;
  }

  private void collectInto(List<OclAstAnalysisNode> result) {
    result.add(this);
    for (OclAstAnalysisNode child : children) {
      child.collectInto(result);
    }
  }
}
