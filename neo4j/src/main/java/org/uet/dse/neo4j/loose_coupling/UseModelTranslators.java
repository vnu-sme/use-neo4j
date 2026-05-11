package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.mm.MGeneralization;
import org.tzi.use.uml.ocl.type.EnumType;
import org.uet.dse.neo4j.mm.core.edge.Extends;
import org.uet.dse.neo4j.mm.core.node.*;

public record UseModelTranslators(
    IModelTranslator<MClass, AbstractClassNode> classTranslator,
    IModelTranslator<EnumType, NodeEnumeration> enumTranslator,
    IModelTranslator<MGeneralization, Extends> generalizationTranslator,
    IModelTranslator<UseAttributeTranslator.AttributeContext, NodeAttribute> attributeTranslator,
    IModelTranslator<UseOperationTranslator.OperationContext, NodeOperation> operationTranslator,
    IModelTranslator<UseParamTranslator.ParamContext, NodeParam> paramTranslator,
    IModelTranslator<UsePreConditionTranslator.PreConditionContext, NodePreCondition> preConditionTranslator,
    IModelTranslator<UsePostConditionTranslator.PostConditionContext, NodePostCondition> postConditionTranslator

) {}