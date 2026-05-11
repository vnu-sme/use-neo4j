package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.ocl.type.EnumType;
import org.uet.dse.neo4j.mm.core.node.AbstractClassNode;
import org.uet.dse.neo4j.mm.core.node.NodeEnumeration;

// UseModelTranslators.java — value object chứa đủ bộ translators
public record UseModelTranslators(
    IModelTranslator<MClass, AbstractClassNode> classTranslator,
    IModelTranslator<EnumType, NodeEnumeration> enumTranslator
//    IModelTranslator<MAssociation, NodeAssociationEdge> associationTranslator,
//    IModelTranslator<MGeneralization, NodeInheritanceEdge> generalizationTranslator
) {}