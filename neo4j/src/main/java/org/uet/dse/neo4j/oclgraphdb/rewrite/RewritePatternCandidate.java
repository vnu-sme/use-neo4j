package org.uet.dse.neo4j.oclgraphdb.rewrite;

public class RewritePatternCandidate {
  private final String name;
  private final RewritePatternNode replacement;
  private final int coverage;
  private final int specificity;
  private final int cypherPushdown;
  private final int estimatedCost;
  private final boolean semanticExact;
  private final String reason;

  private RewritePatternCandidate(Builder builder) {
    this.name = builder.name == null ? "" : builder.name;
    this.replacement = builder.replacement;
    this.coverage = builder.coverage;
    this.specificity = builder.specificity;
    this.cypherPushdown = builder.cypherPushdown;
    this.estimatedCost = builder.estimatedCost;
    this.semanticExact = builder.semanticExact;
    this.reason = builder.reason == null ? "" : builder.reason;
  }

  public static Builder builder(String name, RewritePatternNode replacement) {
    return new Builder(name, replacement);
  }

  public String getName() {
    return name;
  }

  public RewritePatternNode getReplacement() {
    return replacement;
  }

  public int getCoverage() {
    return coverage;
  }

  public int getSpecificity() {
    return specificity;
  }

  public int getCypherPushdown() {
    return cypherPushdown;
  }

  public int getEstimatedCost() {
    return estimatedCost;
  }

  public boolean isSemanticExact() {
    return semanticExact;
  }

  public String getReason() {
    return reason;
  }

  public int weightedScore() {
    return (semanticExact ? 1_000_000 : 0)
        + coverage * 10_000
        + specificity * 1_000
        + cypherPushdown * 100
        + replacement.getScore()
        - estimatedCost;
  }

  public static class Builder {
    private final String name;
    private final RewritePatternNode replacement;
    private int coverage = 1;
    private int specificity = 1;
    private int cypherPushdown = 1;
    private int estimatedCost = 0;
    private boolean semanticExact = true;
    private String reason = "";

    private Builder(String name, RewritePatternNode replacement) {
      if (replacement == null) {
        throw new IllegalArgumentException("Candidate replacement must not be null.");
      }
      this.name = name;
      this.replacement = replacement;
    }

    public Builder coverage(int coverage) {
      this.coverage = coverage;
      return this;
    }

    public Builder specificity(int specificity) {
      this.specificity = specificity;
      return this;
    }

    public Builder cypherPushdown(int cypherPushdown) {
      this.cypherPushdown = cypherPushdown;
      return this;
    }

    public Builder estimatedCost(int estimatedCost) {
      this.estimatedCost = estimatedCost;
      return this;
    }

    public Builder semanticExact(boolean semanticExact) {
      this.semanticExact = semanticExact;
      return this;
    }

    public Builder reason(String reason) {
      this.reason = reason;
      return this;
    }

    public RewritePatternCandidate build() {
      return new RewritePatternCandidate(this);
    }
  }
}
