package org.uet.dse.neo4j.oclgraphdb.multipass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class PatternInstance {
  private final int id;
  private final MultiPassPatternKind kind;
  private final ASTNode astNode;
  private final String label;
  private final ExpressionKind outputKind;
  private final int score;
  private final int passNumber;
  private final boolean exactSemantic;
  private final List<PatternInstance> children;
  private final Map<String, String> metadata;

  public PatternInstance(
      int id,
      MultiPassPatternKind kind,
      ASTNode astNode,
      String label,
      ExpressionKind outputKind,
      int score,
      int passNumber,
      boolean exactSemantic,
      List<PatternInstance> children,
      Map<String, String> metadata
  ) {
    this.id = id;
    this.kind = kind;
    this.astNode = astNode;
    this.label = label;
    this.outputKind = outputKind;
    this.score = score;
    this.passNumber = passNumber;
    this.exactSemantic = exactSemantic;
    this.children = List.copyOf(children);
    this.metadata = Map.copyOf(metadata);
  }

  public int getId() {
    return id;
  }

  public MultiPassPatternKind getKind() {
    return kind;
  }

  public ASTNode getAstNode() {
    return astNode;
  }

  public String getLabel() {
    return label;
  }

  public ExpressionKind getOutputKind() {
    return outputKind;
  }

  public int getScore() {
    return score;
  }

  public int getPassNumber() {
    return passNumber;
  }

  public boolean isExactSemantic() {
    return exactSemantic;
  }

  public List<PatternInstance> getChildren() {
    return children;
  }

  public Map<String, String> getMetadata() {
    return metadata;
  }

  public String signature() {
    StringBuilder sb = new StringBuilder();
    sb.append(kind).append('|').append(label).append('|').append(outputKind);
    for (PatternInstance child : children) {
      sb.append("|c").append(child.getId());
    }
    metadata.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> sb.append('|').append(entry.getKey()).append('=').append(entry.getValue()));
    return sb.toString();
  }

  public String toSummaryLine() {
    StringBuilder sb = new StringBuilder();
    sb.append("#").append(id)
        .append(" pass=").append(passNumber)
        .append(" ").append(kind)
        .append(" label=").append(label)
        .append(" score=").append(score)
        .append(" exact=").append(exactSemantic);
    if (outputKind != null) {
      sb.append(" output=").append(outputKind);
    }
    if (!children.isEmpty()) {
      sb.append(" children=");
      for (int i = 0; i < children.size(); i++) {
        if (i > 0) sb.append(",");
        sb.append("#").append(children.get(i).getId());
      }
    }
    return sb.toString();
  }

  public static Builder builder(MultiPassPatternKind kind, ASTNode astNode) {
    return new Builder(kind, astNode);
  }

  public static class Builder {
    private final MultiPassPatternKind kind;
    private final ASTNode astNode;
    private String label = "";
    private ExpressionKind outputKind;
    private int score;
    private boolean exactSemantic;
    private List<PatternInstance> children = List.of();
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private Builder(MultiPassPatternKind kind, ASTNode astNode) {
      this.kind = kind;
      this.astNode = astNode;
    }

    public Builder label(String label) {
      this.label = label == null ? "" : label;
      return this;
    }

    public Builder outputKind(ExpressionKind outputKind) {
      this.outputKind = outputKind;
      return this;
    }

    public Builder score(int score) {
      this.score = score;
      return this;
    }

    public Builder exactSemantic(boolean exactSemantic) {
      this.exactSemantic = exactSemantic;
      return this;
    }

    public Builder children(List<PatternInstance> children) {
      this.children = children == null ? List.of() : List.copyOf(children);
      return this;
    }

    public Builder metadata(String key, Object value) {
      if (key != null && value != null) {
        metadata.put(key, value.toString());
      }
      return this;
    }

    PatternInstance build(int id, int passNumber) {
      return new PatternInstance(id, kind, astNode, label, outputKind, score, passNumber, exactSemantic, children, metadata);
    }
  }
}
