package org.uet.dse.neo4j.oclgraphdb;

import java.util.LinkedHashMap;
import java.util.Map;

public class OclGraphDbEvaluationOptions {
  private final Map<String, String> objectBindings;
  private final boolean autoPatternPasses;
  private final int maxPatternPasses;

  private OclGraphDbEvaluationOptions(
      Map<String, String> objectBindings,
      boolean autoPatternPasses,
      int maxPatternPasses
  ) {
    this.objectBindings = Map.copyOf(objectBindings);
    this.autoPatternPasses = autoPatternPasses;
    this.maxPatternPasses = maxPatternPasses;
  }

  public static OclGraphDbEvaluationOptions defaults() {
    return builder().build();
  }

  public static OclGraphDbEvaluationOptions withSelf(String objectId) {
    return builder().bindObject("self", objectId).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Map<String, String> objectBindings() {
    return objectBindings;
  }

  public boolean autoPatternPasses() {
    return autoPatternPasses;
  }

  public int maxPatternPasses() {
    return maxPatternPasses;
  }

  public static class Builder {
    private final Map<String, String> objectBindings = new LinkedHashMap<>();
    private boolean autoPatternPasses = true;
    private int maxPatternPasses = 0;

    public Builder bindObject(String variableName, String objectId) {
      if (variableName == null || variableName.isBlank()) {
        throw new IllegalArgumentException("Variable name must not be blank.");
      }
      if (objectId == null || objectId.isBlank()) {
        throw new IllegalArgumentException("Object id must not be blank.");
      }
      objectBindings.put(variableName.trim(), objectId.trim());
      return this;
    }

    public Builder autoPatternPasses(boolean autoPatternPasses) {
      this.autoPatternPasses = autoPatternPasses;
      return this;
    }

    public Builder maxPatternPasses(int maxPatternPasses) {
      if (maxPatternPasses < 0) {
        throw new IllegalArgumentException("maxPatternPasses must be >= 0.");
      }
      this.maxPatternPasses = maxPatternPasses;
      return this;
    }

    public OclGraphDbEvaluationOptions build() {
      return new OclGraphDbEvaluationOptions(objectBindings, autoPatternPasses, maxPatternPasses);
    }
  }
}
