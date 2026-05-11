package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.*;
import org.tzi.use.uml.ocl.type.EnumType;
import org.uet.dse.neo4j.mm.core.node.NodeAttribute;
import org.uet.dse.neo4j.mm.core.node.NodeConcreteClass;
import org.uet.dse.neo4j.mm.core.node.NodeEnumeration;
import org.uet.dse.neo4j.mm.core.node.NodeOperation;

/**
 * This class is responsible for taking input of USE-core knowledge and return output of USE-Neo4j domain knowledge
 */
public class ModelTranslatorFactory {

  public static UseModelTranslators forUse() {
    return new UseModelTranslators(
        new UseClassTranslator(),
        new UseEnumTranslator(),
        new UseGeneralizationTranslator(),
        new UseAttributeTranslator(),
        new UseOperationTranslator(),
        new UseParamTranslator(),
        new UsePreConditionTranslator(),
        new UsePostConditionTranslator(),
        new UseClassInvariantTranslator(),
        new UseBinaryAssociationTranslator(),
        new UseAssociationClassTranslator(),
        new UseTernaryAssociationTranslator()
    );
  }
}
