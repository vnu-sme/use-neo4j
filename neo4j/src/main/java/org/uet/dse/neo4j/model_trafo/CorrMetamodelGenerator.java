package org.uet.dse.neo4j.model_trafo;

import org.uet.dse.neo4j.model_trafo.parser.*;

import java.util.*;

public class CorrMetamodelGenerator {

    public String generateMetaModelQuery(TGGTransformation trans, String srcModel, String trgModel) {
        StringBuilder sb = new StringBuilder();
        String corrModel = trans.name + "_Corr";

        sb.append(String.format("// ManageModel n MetaNode \n"));
        sb.append(String.format("MERGE (mCorr:ManageModel {name: '%s'})\n", corrModel));
        sb.append("MERGE (metaCorr:MetaNode {name: 'NodeConcreteClass', label: 'NodeConcreteClass'})\n");
        sb.append("MERGE (mCorr)-[:DefineMetamodels]->(metaCorr)\n");

        sb.append(String.format("WITH mCorr, metaCorr\nMATCH (mSrc:ManageModel {name: '%s'})\n", srcModel));
        sb.append(String.format("MATCH (mTrg:ManageModel {name: '%s'})\n", trgModel));
        sb.append("WITH mCorr, metaCorr, mSrc, mTrg\n\n");

        Set<String> corrClasses = new HashSet<>();
        List<BridgeAssocData> bridges = new ArrayList<>();

        for (TGGRule rule : trans.rules) {
            for (TGGElement e : rule.correspondence.rhs) {
                if (e instanceof CorrDecl) {
                    CorrDecl corr = (CorrDecl) e;
                    corrClasses.add(corr.corrType);

                    String sCls = findTypeAcrossRule(corr.nodeARef, rule);
                    String tCls = findTypeAcrossRule(corr.nodeBRef, rule);

                    if (sCls != null) {
                        bridges.add(new BridgeAssocData(
                            corr.corrType + "_" + sCls, corr.corrType, sCls, "mSrc", corr.corrName, corr.aliasA));
                    }
                    if (tCls != null) {
                        bridges.add(new BridgeAssocData(
                            corr.corrType + "_" + tCls, corr.corrType, tCls, "mTrg", corr.corrName, corr.aliasB));
                    }
                }
            }
        }

        sb.append("// Correspondence Classes \n");
        for (String c : corrClasses) {
            sb.append(String.format("MERGE (c_%s:`%s` {name: '%s'}) ", c, c, c));
            sb.append(String.format("MERGE (c_%s)-[:InstanceOf]->(metaCorr) ", c));
            sb.append(String.format("MERGE (mCorr)-[:DefineMetamodels]->(c_%s)\n", c));
        }
        sb.append("\nWITH mSrc, mTrg, mCorr, metaCorr\n\n");

        sb.append("//  Bridge Association Source n Target\n");
        for (int i = 0; i < bridges.size(); i++) {
            BridgeAssocData b = bridges.get(i);

            sb.append(String.format(
                "// Link %s\n" +
                    "MATCH (%s)-[:DefineMetamodels]->(metaT:MetaNode {name: 'NodeConcreteClass'})\n" +
                    "MATCH (targetCls:`%s` {name: '%s'})-[:InstanceOf]->(metaT)\n" +
                    "MATCH (mCorr)-[:DefineMetamodels]->(corrCls:`%s` {name: '%s'})\n" +
                    "MERGE (corrCls)-[r:AssociateWith {associationName: '%s'}]->(targetCls)\n" +
                    "SET r.isTernary = false, \n" +
                    "    r.sourceClassName = '%s', \n" +
                    "    r.sourceClassrole = '%s', \n" +
                    "    r.sourceKind = 0, \n" +
                    "    r.sourceMultiplicity = '1', \n" +
                    "    r.targetClassName = '%s', \n" +
                    "    r.targerClassrole = '%s', \n" +
                    "    r.targetKind = 0, \n" +
                    "    r.targetMultiplicity = '0..1'\n",
                b.assocName, b.targetModelVar, b.targetClassType, b.targetClassType,
                b.corrType, b.corrType, b.assocName, b.corrType, b.corrRole, b.targetClassType, b.targetRole
            ));

            if (i < bridges.size() - 1) {
                sb.append("WITH mSrc, mTrg, mCorr, metaCorr\n");
            }
        }

        sb.append("\nRETURN 'Correspondence Metamodel Created Successfully' AS Status;");
        return sb.toString();
    }

    private String findTypeAcrossRule(String nodeRef, TGGRule rule) {
        if (nodeRef == null) return null;
        String name = nodeRef.replace("*", "").trim();
        List<TGGBlock> blocks = Arrays.asList(rule.source, rule.target);
        for (TGGBlock b : blocks) {
            for (TGGElement e : b.lhs) if (e instanceof NodeDecl && ((NodeDecl)e).name.equals(name)) return ((NodeDecl)e).type;
            for (TGGElement e : b.rhs) if (e instanceof NodeDecl && ((NodeDecl)e).name.equals(name)) return ((NodeDecl)e).type;
        }
        return null;
    }

    static class BridgeAssocData {
        String assocName, corrType, targetClassType, targetModelVar, corrRole, targetRole;
        BridgeAssocData(String an, String ct, String tct, String tmv, String cr, String tr) {
            this.assocName = an; this.corrType = ct; this.targetClassType = tct;
            this.targetModelVar = tmv; this.corrRole = cr; this.targetRole = tr;
        }
    }
    private void resolveTypes(CorrDecl corr, TGGRule rule) {
        String sName = corr.nodeARef.replace("*", "");
        String tName = corr.nodeBRef.replace("*", "");
        corr.sourceClassType = findType(sName, rule.source);
        corr.targetClassType = findType(tName, rule.target);
    }

    private String findType(String name, TGGBlock block) {
        for (TGGElement e : block.lhs) if (e instanceof NodeDecl && ((NodeDecl)e).name.equals(name)) return ((NodeDecl)e).type;
        for (TGGElement e : block.rhs) if (e instanceof NodeDecl && ((NodeDecl)e).name.equals(name)) return ((NodeDecl)e).type;
        return null;
    }
    public String generateMatchingQuery(TGGRule rule, String srcModel, String trgModel) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("// Matching for Rule: %s\n", rule.name));

        sb.append("MATCH ");
        List<String> matches = new ArrayList<>();
        for (TGGElement e : rule.source.lhs) {
            if (e instanceof NodeDecl) {
                NodeDecl n = (NodeDecl) e;
                matches.add(String.format("(%s:`%s` {model: '%s'})", n.name, n.type, srcModel));
            } else if (e instanceof EdgeDecl) {
                EdgeDecl ed = (EdgeDecl) e;
                matches.add(String.format("(%s)-[:`%s`]->(%s)", ed.src, ed.type, ed.target));
            }
        }
        sb.append(String.join(", ", matches)).append("\n");

        sb.append("WHERE NOT EXISTS {\n");
        sb.append(String.format("  MATCH (%s)-[:CONF_SRC]-(corr)-[:CONF_TGT]-(target)\n",
            rule.correspondence.rhs.isEmpty() ? "node" : ((CorrDecl)rule.correspondence.rhs.get(0)).nodeARef.replace("*", "")));
        sb.append("}\n");
        sb.append("RETURN * LIMIT 100");

        return sb.toString();
    }


    public String generateExecutionQuery(TGGRule rule, String srcModel, String trgModel) {
        StringBuilder sb = new StringBuilder();
        String corrModel = transName + "_Corr_Instance";

        sb.append(String.format("// Execute Rule: %s\n", rule.name));

        sb.append("MATCH (mSrc:ManageModel {name: '").append(srcModel).append("'})\n");
        sb.append("MATCH (mTrg:ManageModel {name: '").append(trgModel).append("'})\n");
        for (TGGElement e : rule.source.lhs) {
            if (e instanceof NodeDecl) {
                NodeDecl n = (NodeDecl) e;
                sb.append(String.format("MATCH (%s:`%s` {use_id: $id_%s, model: '%s'})\n", n.name, n.type, n.name, srcModel));
            }
        }

        for (TGGElement e : rule.source.rhs) {
            if (e instanceof NodeDecl) {
                NodeDecl n = (NodeDecl) e;
                sb.append(String.format("CREATE (%s:`%s` {use_id: $new_id_%s, model: '%s'})\n", n.name, n.type, n.name, srcModel));
            }
        }

        for (TGGElement e : rule.target.rhs) {
            if (e instanceof NodeDecl) {
                NodeDecl n = (NodeDecl) e;
                sb.append(String.format("CREATE (%s:`%s` {use_id: $new_id_%s, model: '%s'})\n", n.name, n.type, n.name, trgModel));
            }
        }


        for (TGGElement e : rule.correspondence.rhs) {
            if (e instanceof CorrDecl) {
                CorrDecl corr = (CorrDecl) e;
                String srcNode = corr.nodeARef.replace("*", "");
                String trgNode = corr.nodeBRef.replace("*", "");

                sb.append(String.format("CREATE (%s:`%s` {rule: '%s'})\n", corr.corrName, corr.corrType, rule.name));
                sb.append(String.format("CREATE (%s)-[:CONF_SRC]->(%s)\n", corr.corrName, srcNode));
                sb.append(String.format("CREATE (%s)-[:CONF_TGT]->(%s)\n", corr.corrName, trgNode));
            }
        }

        return sb.toString();
    }

    private String transName = "TGG_Trans"; // Placeholder


}