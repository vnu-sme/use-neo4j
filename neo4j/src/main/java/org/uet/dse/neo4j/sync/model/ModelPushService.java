package org.uet.dse.neo4j.sync.model;

public interface ModelPushService {
  default void pushModelToNeo4j() {
    System.out.println("sike");
  }

  default void pushModelToNeo4j(ModelDiff modelDiff) {
    System.out.println("sike");
  }
}
