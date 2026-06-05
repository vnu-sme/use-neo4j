package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class RewritePatternNode {
  private final int id;
  private final RewritePatternKind kind;
  private final ASTNode astNode;
  private final String label;
  private final RewriteSemantic semantic;
  private final List<RewritePatternNode> children;
  private final Map<String, String> metadata;
  private final int score;
  private final int passNumber;

  RewritePatternNode(
      int id,
      RewritePatternKind kind,
      ASTNode astNode,
      String label,
      RewriteSemantic semantic,
      List<RewritePatternNode> children,
      Map<String, String> metadata,
      int score,
      int passNumber
  ) {
    this.id = id;
    this.kind = kind;
    this.astNode = astNode;
    this.label = label == null ? "" : label;
    this.semantic = semantic == null ? RewriteSemantic.unknown() : semantic;
    this.children = List.copyOf(children == null ? List.of() : children);
    this.metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    this.score = score;
    this.passNumber = passNumber;
  }

  public int getId() {
    return id;
  }

  public RewritePatternKind getKind() {
    return kind;
  }

  public ASTNode getAstNode() {
    return astNode;
  }

  public String getLabel() {
    return label;
  }

  public RewriteSemantic getSemantic() {
    return semantic;
  }

  public List<RewritePatternNode> getChildren() {
    return children;
  }

  public Map<String, String> getMetadata() {
    return metadata;
  }

  public int getScore() {
    return score;
  }

  public int getPassNumber() {
    return passNumber;
  }

  public String metadata(String key) {
    return metadata.getOrDefault(key, "");
  }

  public String structuralSignature() {
    StringBuilder sb = new StringBuilder();
    sb.append(kind)
        .append('|').append(astNode == null ? "null" : System.identityHashCode(astNode))
        .append('|').append(label)
        .append('|').append(score)
        .append('|').append(semantic.signature());
    metadata.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> sb.append('|').append(entry.getKey()).append('=').append(entry.getValue()));
    for (RewritePatternNode child : children) {
      sb.append("|c(").append(child.structuralSignature()).append(')');
    }
    return sb.toString();
  }

  public String toSummaryLine() {
    StringBuilder sb = new StringBuilder();
    sb.append("#").append(id)
        .append(" pass=").append(passNumber)
        .append(" ").append(kind)
        .append(" label=").append(label)
        .append(" score=").append(score)
        .append(" exact=").append(semantic.isExact());
    if (semantic.getOutputKind() != null) {
      sb.append(" output=").append(semantic.getOutputKind());
    }
    if (!children.isEmpty()) {
      sb.append(" children=");
      for (int i = 0; i < children.size(); i++) {
        if (i > 0) sb.append(",");
        sb.append("#").append(children.get(i).id);
      }
    }
    return sb.toString();
  }

  public Builder toBuilder(RewritePatternNodeFactory factory, int passNumber) {
    return factory.builder(kind, astNode, passNumber)
        .label(label)
        .semantic(semantic)
        .children(children)
        .metadata(metadata)
        .score(score);
  }

  public static class Builder {
    private final RewritePatternNodeFactory factory;
    private final RewritePatternKind kind;
    private final ASTNode astNode;
    private final int passNumber;
    private String label = "";
    private RewriteSemantic semantic = RewriteSemantic.unknown();
    private List<RewritePatternNode> children = List.of();
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private int score;

    Builder(RewritePatternNodeFactory factory, RewritePatternKind kind, ASTNode astNode, int passNumber) {
      this.factory = factory;
      this.kind = kind;
      this.astNode = astNode;
      this.passNumber = passNumber;
    }

    public Builder label(String label) {
      this.label = label == null ? "" : label;
      return this;
    }

    public Builder semantic(RewriteSemantic semantic) {
      this.semantic = semantic == null ? RewriteSemantic.unknown() : semantic;
      return this;
    }

    public Builder children(List<RewritePatternNode> children) {
      this.children = children == null ? List.of() : List.copyOf(children);
      return this;
    }

    public Builder metadata(String key, Object value) {
      if (key != null && value != null) {
        metadata.put(key, value.toString());
      }
      return this;
    }

    public Builder metadata(Map<String, String> values) {
      if (values != null) {
        metadata.putAll(values);
      }
      return this;
    }

    public Builder score(int score) {
      this.score = score;
      return this;
    }

    public RewritePatternNode build() {
      return new RewritePatternNode(factory.nextId(), kind, astNode, label, semantic, children, metadata, score, passNumber);
    }
  }
}
