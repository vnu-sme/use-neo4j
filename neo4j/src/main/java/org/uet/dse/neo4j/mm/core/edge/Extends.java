package org.uet.dse.neo4j.mm.core.edge;

public class Extends {
  private final String childName;
  private final String parentName;

  public Extends(String childName, String parentName) {
    this.childName = childName;
    this.parentName = parentName;
  }

  public String getChildName() { return childName; }
  public String getParentName() { return parentName; }
}
