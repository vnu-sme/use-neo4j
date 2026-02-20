package org.uet.dse.neo4j.ocl.value;

import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.value.Value;

public class NObjectValue extends Value {



  protected NObjectValue(Type t) {
    super(t);
  }

  @Override
  public StringBuilder toString(StringBuilder sb) {
    return null;
  }

  @Override
  public int hashCode() {
    return 0;
  }

  @Override
  public boolean equals(Object obj) {
    return false;
  }

  @Override
  public int compareTo(Value o) {
    return 0;
  }
}
