package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.ocl.expr.VarDecl;
import org.uet.dse.neo4j.helper.TypeMapper;
import org.uet.dse.neo4j.mm.core.common.NCollectionType;
import org.uet.dse.neo4j.mm.core.common.NType;
import org.uet.dse.neo4j.mm.core.node.NodeParam;

import java.util.Map;

public class UseParamTranslator implements IModelTranslator<UseParamTranslator.ParamContext, NodeParam> {

    public record ParamContext(VarDecl param, String opId, int order) {}

    @Override
    public NodeParam translate(ParamContext ctx) {
        VarDecl param = ctx.param();
        Map<String, Object> pInfo = TypeMapper.parseTypeUSE2Neo4j(param.type());
        String paramId = ctx.opId() + "_p_" + param.name();
        boolean isCollection = (Boolean) pInfo.get("isCollection");

        NodeParam node = new NodeParam(param.name());
        node.setInstanceId(paramId);
        node.setpName(param.name());
        node.setpOrder(ctx.order());
        node.setCollection(isCollection);
        node.setType(NType.fromString((String) pInfo.get("type")));
        node.setCollectionType(resolveCollectionType(pInfo, isCollection));

        return node;
    }

    private NCollectionType resolveCollectionType(Map<String, Object> pInfo, boolean isCollection) {
        if (!isCollection) return NCollectionType.None;
        try {
            return NCollectionType.valueOf((String) pInfo.get("collType"));
        } catch (IllegalArgumentException e) {
            return NCollectionType.None;
        }
    }
}