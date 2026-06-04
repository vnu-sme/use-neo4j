package org.uet.dse.neo4j.oclgraphdb.analysis;

import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternPlan;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternPassSummary;

public class OclAnalysisResult {
  private final OclAstAnalysisNode root;
  private final MultiPassPatternPlan multiPassPlan;

  public OclAnalysisResult(OclAstAnalysisNode root) {
    this(root, null);
  }

  public OclAnalysisResult(OclAstAnalysisNode root, MultiPassPatternPlan multiPassPlan) {
    this.root = root;
    this.multiPassPlan = multiPassPlan;
  }

  public OclAstAnalysisNode getRoot() {
    return root;
  }

  public MultiPassPatternPlan getMultiPassPlan() {
    return multiPassPlan;
  }

  public int getNodeCount() {
    return root == null ? 0 : root.countNodes();
  }

  public int getMaxDepth() {
    return root == null ? 0 : root.maxDepth();
  }

  public int getPatternMatchedNodeCount() {
    return root == null ? 0 : root.countPatternMatchedNodes();
  }

  public int getPatternCandidateCount() {
    return root == null ? 0 : root.countPatternCandidates();
  }

  public String toTextReport() {
    if (root == null) return "No AST was produced.";

    StringBuilder sb = new StringBuilder();
    sb.append("OCL Analysis\n");
    sb.append("============\n");
    sb.append("AST nodes: ").append(getNodeCount()).append('\n');
    sb.append("AST max depth: ").append(getMaxDepth()).append('\n');
    sb.append("Pattern-matched nodes: ")
        .append(getPatternMatchedNodeCount())
        .append("/")
        .append(getNodeCount())
        .append('\n');
    sb.append("Pattern candidates: ").append(getPatternCandidateCount()).append('\n');
    if (multiPassPlan != null) {
      sb.append("Multi-pass patterns: ").append(multiPassPlan.getPatternCount()).append('\n');
      sb.append("Multi-pass rounds: ")
          .append(multiPassPlan.getExecutedRounds())
          .append("/")
          .append(multiPassPlan.getConfiguredMaxPasses())
          .append('\n');
      sb.append("Reached fixpoint: ").append(multiPassPlan.isReachedFixpoint()).append('\n');
    }

    OclPatternCandidateAnalysis bestRoot = root.bestCandidate();
    if (bestRoot != null) {
      sb.append("Best root candidate: ").append(bestRoot.toSummaryLine()).append('\n');
    } else {
      sb.append("Best root candidate: <none>\n");
    }

    if (multiPassPlan != null && !multiPassPlan.getGraph().rootPatterns().isEmpty()) {
      PatternInstance best = multiPassPlan.getGraph().rootPatterns().get(0);
      sb.append("Best root multi-pass pattern: ").append(best.toSummaryLine()).append('\n');
    }

    if (multiPassPlan != null) {
      sb.append("\nMulti-pass Summary\n");
      for (PatternPassSummary summary : multiPassPlan.getPassSummaries()) {
        sb.append("- ").append(summary.toSummaryLine()).append('\n');
      }
    }

    sb.append("\nAST / Pattern Tree\n");
    appendNode(sb, root, "");
    return sb.toString();
  }

  public String toMermaidDiagram() {
    if (root == null) return "flowchart TD\n  empty[\"No AST was produced.\"]\n";

    StringBuilder sb = new StringBuilder();
    sb.append("flowchart TD\n");
    sb.append("  summary[\"")
        .append(mermaidLabel("OCL Analysis\n"
            + "AST nodes: " + getNodeCount() + "\n"
            + "Max depth: " + getMaxDepth() + "\n"
            + "Pattern matched nodes: " + getPatternMatchedNodeCount() + "/" + getNodeCount() + "\n"
            + "Pattern candidates: " + getPatternCandidateCount()))
        .append("\"]:::summary\n");
    appendMermaidAstNode(sb, root, null);

    if (multiPassPlan != null && !multiPassPlan.getPassSummaries().isEmpty()) {
      sb.append("  passSummary[\"")
          .append(mermaidLabel("Multi-pass summary\n"
              + "patterns: " + multiPassPlan.getPatternCount() + "\n"
              + "rounds: " + multiPassPlan.getExecutedRounds() + "/" + multiPassPlan.getConfiguredMaxPasses() + "\n"
              + "fixpoint: " + multiPassPlan.isReachedFixpoint()))
          .append("\"]:::summary\n");
      sb.append("  summary --> passSummary\n");
      String previous = "passSummary";
      int index = 1;
      for (PatternPassSummary summary : multiPassPlan.getPassSummaries()) {
        String id = "pass" + index;
        sb.append("  ").append(id).append("[\"")
            .append(mermaidLabel(summary.toSummaryLine()))
            .append("\"]:::summary\n");
        sb.append("  ").append(previous).append(" --> ").append(id).append("\n");
        previous = id;
        index++;
      }
    }

    sb.append("  classDef ast fill:#e8f3ff,stroke:#2b6cb0,color:#111827\n");
    sb.append("  classDef legacy fill:#fff7ed,stroke:#c2410c,color:#111827\n");
    sb.append("  classDef multipass fill:#ecfdf5,stroke:#047857,color:#111827\n");
    sb.append("  classDef summary fill:#f8fafc,stroke:#475569,color:#111827\n");
    return sb.toString();
  }

  private void appendNode(StringBuilder sb, OclAstAnalysisNode node, String indent) {
    sb.append(indent).append("- ").append(node.toTreeLabel()).append('\n');
    for (String note : node.getNotes()) {
      sb.append(indent).append("  note: ").append(note).append('\n');
    }
    for (OclPatternCandidateAnalysis candidate : node.getCandidates()) {
      sb.append(indent).append("  pattern: ").append(candidate.toSummaryLine()).append('\n');
      sb.append(indent).append("    cypher: ").append(preview(candidate.getCypherExpression())).append('\n');
    }
    for (PatternInstance pattern : node.getMultiPassPatterns()) {
      sb.append(indent).append("  multipass: ").append(pattern.toSummaryLine()).append('\n');
      if (!pattern.getMetadata().isEmpty()) {
        sb.append(indent).append("    metadata: ").append(pattern.getMetadata()).append('\n');
      }
    }
    for (OclAstAnalysisNode child : node.getChildren()) {
      appendNode(sb, child, indent + "  ");
    }
  }

  private String preview(String value) {
    if (value == null || value.isBlank()) return "<empty>";
    String normalized = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    return normalized.length() <= 220 ? normalized : normalized.substring(0, 217) + "...";
  }

  private void appendMermaidAstNode(StringBuilder sb, OclAstAnalysisNode node, String parentId) {
    String astId = astNodeId(node);
    sb.append("  ").append(astId).append("[\"")
        .append(mermaidLabel(node.toTreeLabel()))
        .append("\"]:::ast\n");
    if (parentId == null) {
      sb.append("  summary --> ").append(astId).append("\n");
    } else {
      sb.append("  ").append(parentId).append(" --> ").append(astId).append("\n");
    }

    int candidateIndex = 1;
    for (OclPatternCandidateAnalysis candidate : node.getCandidates()) {
      String candidateId = astId + "_legacy" + candidateIndex;
      sb.append("  ").append(candidateId).append("[\"")
          .append(mermaidLabel("legacy candidate\n" + candidate.toSummaryLine()))
          .append("\"]:::legacy\n");
      sb.append("  ").append(astId).append(" -.-> ").append(candidateId).append("\n");
      candidateIndex++;
    }

    for (PatternInstance pattern : node.getMultiPassPatterns()) {
      String patternId = patternNodeId(pattern);
      sb.append("  ").append(patternId).append("[\"")
          .append(mermaidLabel("multi-pass pattern\n" + pattern.toSummaryLine() + metadataLine(pattern)))
          .append("\"]:::multipass\n");
      sb.append("  ").append(astId).append(" -.-> ").append(patternId).append("\n");
      for (PatternInstance child : pattern.getChildren()) {
        sb.append("  ").append(patternId).append(" --> ").append(patternNodeId(child)).append("\n");
      }
    }

    for (OclAstAnalysisNode child : node.getChildren()) {
      appendMermaidAstNode(sb, child, astId);
    }
  }

  private String astNodeId(OclAstAnalysisNode node) {
    return "ast" + node.getId();
  }

  private String patternNodeId(PatternInstance pattern) {
    return "mp" + pattern.getId();
  }

  private String metadataLine(PatternInstance pattern) {
    if (pattern.getMetadata().isEmpty()) return "";
    return "\nmetadata: " + preview(pattern.getMetadata().toString());
  }

  private String mermaidLabel(String value) {
    if (value == null || value.isBlank()) return "";
    String normalized = value.replace("\r", "").replace("\t", " ").trim();
    if (normalized.length() > 260) {
      normalized = normalized.substring(0, 257) + "...";
    }
    return normalized
        .replace("&", "&amp;")
        .replace("\\", "\\\\")
        .replace("\"", "'")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br/>");
  }
}
