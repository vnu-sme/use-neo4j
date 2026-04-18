package org.uet.dse.neo4j.model_trafo.matching;

import org.uet.dse.neo4j.model_trafo.parser.*;

import java.util.*;

public class CorrMetamodelGenerator {
  private int edgeCounter = 0;

  public String generateMetaModelQuery(TGGTransformation trans, String srcModel, String trgModel) {
    StringBuilder sb = new StringBuilder();
    String corrModel = trans.name + "_Corr";

    sb.append("// --- STEP 0: CREATE CORRESPONDENCE METAMODEL ---\n");
    sb.append(String.format("MERGE (mCorr:ManageModel {name: '%s'})\n", corrModel));
    sb.append("MERGE (metaC:MetaNode {name: 'NodeConcreteClass', label: 'NodeConcreteClass'})\n");
    sb.append("MERGE (mCorr)-[:DefineMetamodels]->(metaC)\n");

    sb.append(String.format("WITH mCorr, metaC\nMATCH (mSrc:ManageModel {name: '%s'})\n", srcModel));
    sb.append(String.format("MATCH (mTrg:ManageModel {name: '%s'})\n", trgModel));
    sb.append("WITH mCorr, metaC, mSrc, mTrg\n\n");

    Set<String> corrClasses = new HashSet<>();
    List<BridgeAssocData> bridges = new ArrayList<>();
    for (TGGRule rule : trans.rules) {
      for (TGGElement e : rule.correspondence.rhs) {
        if (e instanceof CorrDecl) {
          CorrDecl corr = (CorrDecl) e;
          corrClasses.add(corr.corrType);
          String sCls = findTypeAcrossRule(corr.nodeARef, rule);
          String tCls = findTypeAcrossRule(corr.nodeBRef, rule);
          if (sCls != null)
            bridges.add(new BridgeAssocData(corr.corrType + "_" + sCls, corr.corrType, sCls, "mSrc", corr.corrName, corr.aliasA));
          if (tCls != null)
            bridges.add(new BridgeAssocData(corr.corrType + "_" + tCls, corr.corrType, tCls, "mTrg", corr.corrName, corr.aliasB));
        }
      }
    }

    sb.append("// 2. Create class node with class label\n");
    for (String c : corrClasses) {
      sb.append(String.format("MERGE (c_%s:`%s`:NodeConcreteClass {name: '%s'}) ", c, c, c));

      sb.append(String.format("MERGE (c_%s)-[:InstanceOf]->(metaC)\n", c));
    }
    sb.append("WITH mSrc, mTrg, mCorr, metaC\n\n");

    sb.append("// 3. Tao cac lien ket AssociateWith giua cac Class\n");
    for (int i = 0; i < bridges.size(); i++) {
      BridgeAssocData b = bridges.get(i);
      sb.append(String.format("// Link %s\n" + "MATCH (%s)-[:DefineMetamodels]->(metaT:MetaNode {name: 'NodeConcreteClass'})\n" +
              "MATCH (targetCls:`%s` {name: '%s'})-[:InstanceOf]->(metaT)\n" +

              "MATCH (corrCls:`%s` {name: '%s'})-[:InstanceOf]->(metaC)\n" + "MERGE (corrCls)-[r:AssociateWith {associationName: '%s'}]->(targetCls)\n" + "SET r.isTernary = false, \n" + "    r.sourceClassName = '%s', \n" + "    r.sourceClassrole = '%s', \n" + "    r.targetClassName = '%s', \n" + "    r.targerClassrole = '%s'\n",
          b.assocName, b.targetModelVar, b.targetClassType, b.targetClassType, b.corrType, b.corrType, b.assocName, b.corrType, b.corrRole, b.targetClassType, b.targetRole));
      if (i < bridges.size() - 1)
        sb.append("WITH mSrc, mTrg, mCorr, metaC\n");
    }
    sb.append("\nRETURN 'Correspondence Metamodel Created Successfully';");
    return sb.toString();
  }

  static class BridgeAssocData {
    String assocName, corrType, targetClassType, targetModelVar, corrRole, targetRole;

    BridgeAssocData(String an, String ct, String tct, String tmv, String cr, String tr) {
      this.assocName = an;
      this.corrType = ct;
      this.targetClassType = tct;
      this.targetModelVar = tmv;
      this.corrRole = cr;
      this.targetRole = tr;
    }
  }

  public String generateMatchingQueryNotManaged(TGGRule rule, String srcM, String trgM, int mode) {
    edgeCounter = 0;
    StringBuilder sb = new StringBuilder();
    String corrM = srcM + "2" + trgM + "_Corr";

    sb.append("// --- ATOMIC BOOLEAN CHECK (OBJECT LEVEL - FIXED PATH) --- \n");
    sb.append("RETURN EXISTS {\n");

    sb.append("  MATCH (mSrc:ManageModel {name: '").append(srcM).append("'}),\n");
    sb.append("        (mTrg:ManageModel {name: '").append(trgM).append("'}),\n");
    sb.append("        (mCorr:ManageModel {name: '").append(corrM).append("'})\n");

    sb.append("  MATCH (mSrc)-[:DefineMetamodels]->(metaS:MetaNode {name: 'NodeConcreteClass'}),\n");
    sb.append("        (mTrg)-[:DefineMetamodels]->(metaT:MetaNode {name: 'NodeConcreteClass'}),\n");
    sb.append("        (mCorr)-[:DefineMetamodels]->(metaC:MetaNode {name: 'NodeConcreteClass'})\n");

    List<String> patterns = new ArrayList<>();
    List<String> filters = new ArrayList<>();

    processObjectBlock(rule.source.lhs, patterns, filters, "metaS");
    if (mode == 0 || mode == 2)
      processObjectBlock(rule.source.rhs, patterns, filters, "metaS");

    processObjectBlock(rule.target.lhs, patterns, filters, "metaT");
    if (mode == 1 || mode == 2)
      processObjectBlock(rule.target.rhs, patterns, filters, "metaT");

    for (TGGElement e : rule.correspondence.lhs) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNode = c.nodeARef.replace("*", "");
        String tNode = c.nodeBRef.replace("*", "");
        String e1 = "rel" + (edgeCounter++);
        String e2 = "rel" + (edgeCounter++);
        String clsVar = "cls_" + c.corrName;

        patterns.add(String.format("(%s)-[%s]-(%s)-[%s]-(%s)", sNode, e1, c.corrName, e2, tNode));
        filters.add(String.format("type(%s) = 'LinkAssociateWith'", e1));
        filters.add(String.format("type(%s) = 'LinkAssociateWith'", e2));

        patterns.add(String.format("(%s)-[:ObjectInstanceOf]->(%s)-[:InstanceOf]->(metaC)", c.corrName, clsVar));
        filters.add(String.format("%s.name = '%s'", clsVar, c.corrType));
      }
    }

    sb.append("  MATCH ").append(String.join(",\n    ", patterns)).append("\n");
    sb.append("  WHERE ").append(String.join("\n    AND ", filters)).append("\n");

    sb.append("  AND NOT EXISTS {\n");
    for (TGGElement e : rule.correspondence.rhs) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNode = c.nodeARef.replace("*", "");
        String tNode = c.nodeBRef.replace("*", "");
        sb.append(String.format("    MATCH (%s)-[:AssociateWith]-(existing)-[:AssociateWith]-(%s)\n", sNode, tNode));
        sb.append(String.format("    MATCH (existing)-[:ObjectInstanceOf]->(exCls)-[:InstanceOf]->(metaC) WHERE exCls.name = '%s'\n", c.corrType));
      }
    }
    sb.append("  }\n");

    appendConstraints(sb, rule);

    sb.append("} AS isMatched");

    return sb.toString();
  }

  public String generateMatchingQuery(TGGRule rule, String srcM, String trgM, int mode) {
    this.edgeCounter = 0;
    StringBuilder sb = new StringBuilder();
    String corrM = srcM + "2" + trgM + "_Corr";

    sb.append("// --- ATOMIC BOOLEAN CHECK (FIXED SYNTAX & PATH) --- \n");
    sb.append("RETURN EXISTS {\n");

    sb.append(String.format("  MATCH (mSrc:ManageModel {name: '%s'})-[:DefineMetamodels]->(metaS:MetaNode {name: 'NodeConcreteClass'})\n", srcM));
    sb.append(String.format("  MATCH (mTrg:ManageModel {name: '%s'})-[:DefineMetamodels]->(metaT:MetaNode {name: 'NodeConcreteClass'})\n", trgM));
    sb.append(String.format("  MATCH (mCorr:ManageModel {name: '%s'})-[:DefineMetamodels]->(metaC:MetaNode {name: 'NodeConcreteClass'})\n", corrM));

    List<String> patterns = new ArrayList<>();
    List<String> allConditions = new ArrayList<>();

    processObjectBlock(rule.source.lhs, patterns, allConditions, null, "metaS");
    if (mode == 0 || mode == 2) processObjectBlock(rule.source.rhs, patterns, allConditions, null, "metaS");

    processObjectBlock(rule.target.lhs, patterns, allConditions, null, "metaT");
    if (mode == 1 || mode == 2) processObjectBlock(rule.target.rhs, patterns, allConditions, null, "metaT");

    for (TGGElement e : rule.correspondence.lhs) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNode = c.nodeARef.replace("*", "");
        String tNode = c.nodeBRef;
        String e1 = "rel" + (edgeCounter++), e2 = "rel" + (edgeCounter++);
        String clsVar = "cls_" + c.corrName;

        patterns.add(String.format("(%s)-[%s]-(%s)-[%s]-(%s)", sNode, e1, c.corrName, e2, tNode));
        allConditions.add(String.format("type(%s) = 'LinkAssociateWith'", e1));
        allConditions.add(String.format("type(%s) = 'LinkAssociateWith'", e2));

        patterns.add(String.format("(%s)-[:ObjectInstanceOf]->(%s)-[:InstanceOf]->(metaC)", c.corrName, clsVar));
        allConditions.add(String.format("%s.name = '%s'", clsVar, c.corrType));
      }
    }

    //NAC
    for (TGGElement e : rule.correspondence.rhs) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNodeVar = c.nodeARef.replace("*", "");

        allConditions.add(String.format(
            "NOT EXISTS {\n" +
                "    MATCH (%s)-[:LinkAssociateWith]-(existingCorr)-[:ObjectInstanceOf]->(clsRHS)-[:InstanceOf]->(metaC)\n" +
                "    WHERE clsRHS.name = '%s'\n" +
                "  }", sNodeVar, c.corrType));
      }
    }

    appendConstraintsToList(allConditions, rule);

    sb.append("  MATCH ").append(String.join(",\n    ", patterns)).append("\n");

    if (!allConditions.isEmpty()) {
      sb.append("  WHERE ").append(String.join("\n    AND ", allConditions)).append("\n");
    }

    sb.append("} AS isMatched");

    return sb.toString();
  }

  private void processObjectBlock(List<TGGElement> elements, List<String> p, List<String> f, List<String> r, String metaVar) {
    if (elements == null) return;
    for (TGGElement e : elements) {
      if (e instanceof NodeDecl) {
        NodeDecl n = (NodeDecl) e;
        p.add(String.format("(%s)-[:ObjectInstanceOf]->(cls_%s)-[:InstanceOf]->(%s)", n.name, n.name, metaVar));
        f.add(String.format("cls_%s.name = '%s'", n.name, n.type));
        //r.add(n.name + ".use_id AS " + n.name);
        if (r != null) {
          r.add(n.name + ".use_id AS " + n.name);
        }
      } else if (e instanceof EdgeDecl) {
        EdgeDecl ed = (EdgeDecl) e;
        String eVar = "e" + (edgeCounter++);
        p.add(String.format("(%s)-[%s]-(%s)", ed.src, eVar, ed.target));
        f.add(String.format("%s.name = '%s' AND type(%s) STARTS WITH 'Link'", eVar, ed.type, eVar));
        //r.add(String.format("elementId(%s) AS %s", eVar, eVar));
        if (r != null) {
          r.add(String.format("elementId(%s) AS %s", eVar, eVar));
        }
      }
    }
  }

  private void appendConstraints(StringBuilder sb, TGGRule rule) {
    List<TGGBlock> blocks = Arrays.asList(rule.source, rule.target, rule.correspondence);
    for (TGGBlock b : blocks) {
      for (TGGElement e : b.lhs) {
        if (e instanceof Constraint) {
          sb.append("  AND ").append(translateOCL(((Constraint) e).expression)).append("\n");
        }
      }
    }
  }

  private String translateOCL(String ocl) {
    return ocl.replace("self.", "").replace("<>", "<>").replace("Undefined", "null");
  }

  private String getModeName(int mode) {
    return (mode == 0) ? "FORWARD" : (mode == 1) ? "BACKWARD" : "INTEGRATION";
  }

  private void processObjectBlock(List<TGGElement> elements, List<String> patterns, List<String> filters, String metaVar) {
    for (TGGElement e : elements) {
      if (e instanceof NodeDecl) {
        NodeDecl n = (NodeDecl) e;
        String clsVar = "cls_" + n.name;
        // Pattern: (Object)-[:ObjectInstanceOf]->(Class)-[:InstanceOf]->(MetaNode)
        patterns.add(String.format("(%s)-[:ObjectInstanceOf]->(%s)-[:InstanceOf]->(%s)", n.name, clsVar, metaVar));
        filters.add(String.format("%s.name = '%s'", clsVar, n.type));
      } else if (e instanceof EdgeDecl) {
        EdgeDecl ed = (EdgeDecl) e;
        String eVar = "e" + (edgeCounter++);
        // un-directed association
        patterns.add(String.format("(%s)-[%s]-(%s)", ed.src, eVar, ed.target));
        filters.add(String.format("%s.name = '%s' AND type(%s) STARTS WITH 'Link'", eVar, ed.type, eVar));
      }
    }
  }


  /**
   * This version is good, just by doing ()-[]->(), keeps it directed search, passing the finding logic to neo4j
   */
  public String generateExecuteMatchQuery(TGGRule rule, String srcM, String trgM, int mode, Map<String, String> candidate) {
    StringBuilder sb = new StringBuilder();
    String corrM = srcM + "2" + trgM + "_Corr";

    sb.append("// --- STEP 2: EXECUTION ON SELECTED TUPLE ---\n");
    sb.append(String.format("MATCH (mSrc:ManageModel {name:'%s'}), (mTrg:ManageModel {name:'%s'}), (mCorr:ManageModel {name:'%s'})\n", srcM, trgM, corrM));
    sb.append("MATCH (mSrc)-[:DefineMetamodels]->(metaS:MetaNode {name:'NodeConcreteClass'})\n");
    sb.append("MATCH (mTrg)-[:DefineMetamodels]->(metaT:MetaNode {name:'NodeConcreteClass'})\n");
    sb.append("MATCH (mCorr)-[:DefineMetamodels]->(metaC:MetaNode {name:'NodeConcreteClass'})\n");

    Set<String> classes = collectAllClassesInRule(rule);
    for (String cls : classes) {
      String mVar = findMetaVarForClass(cls, rule);
      sb.append(String.format("MATCH (cls_%s {name:'%s'})-[:InstanceOf]->(%s)\n", cls, cls, mVar));
    }

    sb.append("WITH * \n");
    for (Map.Entry<String, String> entry : candidate.entrySet()) {
      if (entry.getKey().startsWith("e") || entry.getKey().startsWith("rel")) {
        //old version don't have the ->, which caused duplicate records
        sb.append(String.format("MATCH ()-[%s]->() WHERE elementId(%s) = '%s'\n", entry.getKey(), entry.getKey(), entry.getValue()));
      } else {
        String type = findTypeAcrossRule(entry.getKey(), rule);
        sb.append(String.format("MATCH (%s {use_id: '%s'})-[:ObjectInstanceOf]->(cls_%s)\n", entry.getKey(), entry.getValue(), type));
      }
    }

    sb.append("CREATE \n");
    List<String> creates = new ArrayList<>();
    if (mode == 0)
      addCreateObjectStmts(creates, rule.target.rhs, rule);
    if (mode == 1)
      addCreateObjectStmts(creates, rule.source.rhs, rule);
    addCorrRhsCreateStmts(creates, rule.correspondence.rhs, rule);
    sb.append(String.join(",\n", creates)).append("\n");

    appendObjectInstanceOfLinks(sb, rule, mode);
    sb.append("RETURN 'Success';");
    return sb.toString();
  }

  private void addCreateObjectStmts(List<String> stmts, List<TGGElement> elements, TGGRule rule) {
    for (TGGElement e : elements) {
      if (e instanceof NodeDecl) {
        NodeDecl n = (NodeDecl) e;
        String newId = n.type + "_" + (System.currentTimeMillis() % 1000000);
        stmts.add(String.format("  (%s:`%s`:Object {use_id: '%s', name: '%s', isCorrespondence: false})", n.name, n.type, newId, newId));
      } else if (e instanceof EdgeDecl) {
        EdgeDecl ed = (EdgeDecl) e;
        String typeLower = ed.type.toLowerCase();
        String label = (typeLower.contains("registration") || typeLower.contains("father") || typeLower.contains("mother") || typeLower.contains("son") || typeLower.contains("daughter")) ? "LinkComposeOf" : "LinkAssociateWith";

        stmts.add(String.format("  (%s)-[:%s {name: '%s', isCorrespondenceLink: false}]->(%s)", ed.src, label, ed.type, ed.target));
      }
    }
  }

  private void appendObjectInstanceOfLinks(StringBuilder sb, TGGRule rule, int mode) {
    if (mode == 0)
      for (TGGElement e : rule.target.rhs)
        if (e instanceof NodeDecl)
          sb.append(String.format("MERGE (%s)-[:ObjectInstanceOf]->(cls_%s)\n", ((NodeDecl) e).name, ((NodeDecl) e).type));
    if (mode == 1)
      for (TGGElement e : rule.source.rhs)
        if (e instanceof NodeDecl)
          sb.append(String.format("MERGE (%s)-[:ObjectInstanceOf]->(cls_%s)\n", ((NodeDecl) e).name, ((NodeDecl) e).type));
    for (TGGElement e : rule.correspondence.rhs)
      if (e instanceof CorrDecl)
        sb.append(String.format("MERGE (%s)-[:ObjectInstanceOf]->(cls_%s)\n", ((CorrDecl) e).corrName, ((CorrDecl) e).corrType));
  }

  private String findMetaVarForClass(String cls, TGGRule rule) {
    for (TGGElement e : rule.correspondence.lhs)
      if (e instanceof CorrDecl && ((CorrDecl) e).corrType.equals(cls))
        return "metaC";
    for (TGGElement e : rule.correspondence.rhs)
      if (e instanceof CorrDecl && ((CorrDecl) e).corrType.equals(cls))
        return "metaC";
    for (TGGElement e : rule.target.lhs)
      if (e instanceof NodeDecl && ((NodeDecl) e).type.equals(cls))
        return "metaT";
    for (TGGElement e : rule.target.rhs)
      if (e instanceof NodeDecl && ((NodeDecl) e).type.equals(cls))
        return "metaT";
    return "metaS";
  }

  private Set<String> collectAllClassesInRule(TGGRule rule) {
    Set<String> classes = new HashSet<>();
    List<TGGBlock> all = Arrays.asList(rule.source, rule.target, rule.correspondence);
    for (TGGBlock b : all) {
      for (List<TGGElement> list : Arrays.asList(b.lhs, b.rhs)) {
        for (TGGElement e : list) {
          if (e instanceof NodeDecl)
            classes.add(((NodeDecl) e).type);
          if (e instanceof CorrDecl)
            classes.add(((CorrDecl) e).corrType);
        }
      }
    }
    return classes;
  }

  private String findAnchor(TGGRule rule) {
    return !rule.source.lhs.isEmpty() ? ((NodeDecl) rule.source.lhs.get(0)).name : "fr";
  }

  public String generateFindMatchQueryDataNotManaged(TGGRule rule, String srcM, String trgM, int mode) {
    edgeCounter = 0;
    StringBuilder sb = new StringBuilder();
    String corrM = srcM + "2" + trgM + "_Corr";

    sb.append("// --- STEP 1: FIND CANDIDATES ---\n");
    sb.append(String.format("MATCH (mSrc:ManageModel {name:'%s'})-[:DefineMetamodels]->(metaS:MetaNode {name:'NodeConcreteClass'})\n", srcM));
    sb.append(String.format("MATCH (mTrg:ManageModel {name:'%s'})-[:DefineMetamodels]->(metaT:MetaNode {name:'NodeConcreteClass'})\n", trgM));
    sb.append(String.format("MATCH (mCorr:ManageModel {name:'%s'})-[:DefineMetamodels]->(metaC:MetaNode {name:'NodeConcreteClass'})\n", corrM));

    List<String> patterns = new ArrayList<>();
    List<String> filters = new ArrayList<>();
    List<String> returnItems = new ArrayList<>();

    processObjectBlock(rule.source.lhs, patterns, filters, returnItems, "metaS");
    if (mode == 0 || mode == 2)
      processObjectBlock(rule.source.rhs, patterns, filters, returnItems, "metaS");
    processObjectBlock(rule.target.lhs, patterns, filters, returnItems, "metaT");
    if (mode == 1 || mode == 2)
      processObjectBlock(rule.target.rhs, patterns, filters, returnItems, "metaT");

    // Correspondence LHS (Context)
    for (TGGElement e : rule.correspondence.lhs) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String e1 = "rel" + (edgeCounter++), e2 = "rel" + (edgeCounter++);
        patterns.add(String.format("(%s)-[%s]-(%s)-[%s]-(%s)", c.nodeARef.replace("*", ""), e1, c.corrName, e2, c.nodeBRef));
        filters.add(String.format("type(%s) = 'LinkAssociateWith' AND type(%s) = 'LinkAssociateWith'", e1, e2));
        patterns.add(String.format("(%s)-[:ObjectInstanceOf]->(cls_%s)-[:InstanceOf]->(metaC)", c.corrName, c.corrName));
        filters.add(String.format("cls_%s.name = '%s'", c.corrName, c.corrType));
        returnItems.add(c.corrName + ".use_id AS " + c.corrName);
      }
    }



    sb.append("  MATCH ").append(String.join(",\n    ", patterns)).append("\n");
    sb.append("  WHERE ").append(String.join("\n    AND ", filters)).append("\n");

    sb.append("  AND NOT EXISTS { MATCH (").append(findAnchor(rule)).append(")-[:LinkAssociateWith]-(:NodeConcreteClass {isCorrespondence:true}) }\n");
    sb.append("RETURN DISTINCT ").append(String.join(", ", returnItems));
    return sb.toString();
  }

  public String generateFindMatchQueryData(TGGRule rule, String srcM, String trgM, int mode) {
    this.edgeCounter = 0;
    StringBuilder sb = new StringBuilder();
    String corrM = srcM + "2" + trgM + "_Corr";

    sb.append("// --- STEP 1: FETCH MATCHING TUPLES (SYNTAX FIXED) --- \n");

    sb.append(String.format("MATCH (mSrc:ManageModel {name: '%s'})-[:DefineMetamodels]->(metaS:MetaNode {name: 'NodeConcreteClass'})\n", srcM));
    sb.append(String.format("MATCH (mTrg:ManageModel {name: '%s'})-[:DefineMetamodels]->(metaT:MetaNode {name: 'NodeConcreteClass'})\n", trgM));
    sb.append(String.format("MATCH (mCorr:ManageModel {name: '%s'})-[:DefineMetamodels]->(metaC:MetaNode {name: 'NodeConcreteClass'})\n", corrM));

    List<String> patterns = new ArrayList<>();
    List<String> allConditions = new ArrayList<>(); // Danh sách chứa tất cả điều kiện WHERE
    List<String> returnItems = new ArrayList<>();

    processObjectBlock(rule.source.lhs, patterns, allConditions, returnItems, "metaS");
    if (mode == 0 || mode == 2) processObjectBlock(rule.source.rhs, patterns, allConditions, returnItems, "metaS");

    processObjectBlock(rule.target.lhs, patterns, allConditions, returnItems, "metaT");
    if (mode == 1 || mode == 2) processObjectBlock(rule.target.rhs, patterns, allConditions, returnItems, "metaT");

    for (TGGElement e : rule.correspondence.lhs) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNode = c.nodeARef.replace("*", "");
        String tNode = c.nodeBRef;
        String e1 = "rel" + (edgeCounter++), e2 = "rel" + (edgeCounter++);
        String clsVar = "cls_" + c.corrName;

        patterns.add(String.format("(%s)-[%s]-(%s)-[%s]-(%s)", sNode, e1, c.corrName, e2, tNode));
        allConditions.add(String.format("type(%s) = 'LinkAssociateWith'", e1));
        allConditions.add(String.format("type(%s) = 'LinkAssociateWith'", e2));
        patterns.add(String.format("(%s)-[:ObjectInstanceOf]->(%s)-[:InstanceOf]->(metaC)", c.corrName, clsVar));
        allConditions.add(String.format("%s.name = '%s'", clsVar, c.corrType));
        returnItems.add(c.corrName + ".use_id AS " + c.corrName);
      }
    }

    if (patterns.isEmpty()) return "RETURN 'Error: No input patterns' AS Error";

    //NAC
    for (TGGElement e : rule.correspondence.rhs) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNodeVar = c.nodeARef.replace("*", "");
        allConditions.add(String.format(
            "NOT EXISTS {\n" +
                "      MATCH (%s)-[:LinkAssociateWith]-(existingCorr)-[:ObjectInstanceOf]->(clsRHS)-[:InstanceOf]->(metaC)\n" +
                "      WHERE clsRHS.name = '%s'\n" +
                "    }", sNodeVar, c.corrType));
      }
    }

    appendConstraintsToList(allConditions, rule);

    sb.append("  MATCH ").append(String.join(",\n    ", patterns)).append("\n");

    if (!allConditions.isEmpty()) {
      sb.append("  WHERE ").append(String.join("\n    AND ", allConditions)).append("\n");
    }

    sb.append("RETURN DISTINCT ").append(String.join(", ", returnItems));
    return sb.toString();
  }

  private void appendConstraintsToList(List<String> conditions, TGGRule rule) {
    List<TGGBlock> blocks = Arrays.asList(rule.source, rule.target, rule.correspondence);
    for (TGGBlock b : blocks) {
      for (TGGElement e : b.lhs) {
        if (e instanceof Constraint) {
          conditions.add(translateOCL(((Constraint) e).expression));
        }
      }
    }
  }

  public String generateExecutionQuery(TGGRule rule, String srcM, String trgM, int mode, Map<String, String> candidate) {
    StringBuilder sb = new StringBuilder();
    String corrM = srcM + "2" + trgM + "_Corr";
    edgeCounter = 0;

    sb.append(String.format("// --- ATOMIC EXECUTION (PREVENT DUPLICATION) FOR RULE: %s ---\n", rule.name));

    sb.append(String.format("MATCH (mSrc:ManageModel {name: '%s'}), (mTrg:ManageModel {name: '%s'}), (mCorr:ManageModel {name: '%s'})\n", srcM, trgM, corrM));

    sb.append("MATCH (mSrc)-[:DefineMetamodels]->(metaS:MetaNode {name: 'NodeConcreteClass'})\n");
    sb.append("MATCH (mTrg)-[:DefineMetamodels]->(metaT:MetaNode {name: 'NodeConcreteClass'})\n");
    sb.append("MATCH (mCorr)-[:DefineMetamodels]->(metaC:MetaNode {name: 'NodeConcreteClass'})\n");

    Set<String> allClassNames = collectAllClassesInRule(rule);
    for (String cls : allClassNames) {
      String metaVar = findMetaVarForClass(cls, rule);
      sb.append(String.format("MATCH (cls_%s:NodeConcreteClass {name: '%s'})-[:InstanceOf]->(%s)\n", cls, cls, metaVar));
    }

    sb.append("WITH * \n");

    // tight binding
    List<String> bindings = new ArrayList<>();
    // match node first
    for (Map.Entry<String, String> entry : candidate.entrySet()) {
      if (!entry.getKey().startsWith("e") && !entry.getKey().startsWith("rel")) {
        String typeName = findTypeAcrossRule(entry.getKey(), rule);
        bindings.add(String.format("MATCH (%s {use_id: '%s'})-[:ObjectInstanceOf]->(cls_%s)",
            entry.getKey(), entry.getValue(), typeName));
      }
    }
    // match link based on above node
    for (Map.Entry<String, String> entry : candidate.entrySet()) {
      String var = entry.getKey();
      if (var.startsWith("e") || var.startsWith("rel")) {
        String[] nodes = findNodesForEdge(var, rule);
        bindings.add(String.format("MATCH (%s)-[%s]-(%s) WHERE elementId(%s) = '%s'",
            nodes[0], var, nodes[1], var, entry.getValue()));
      }
    }
    sb.append(String.join("\n", bindings)).append("\n");

    List<String> allReturnVars = new ArrayList<>(candidate.keySet());
    allClassNames.forEach(c -> allReturnVars.add("cls_" + c));
    sb.append("WITH DISTINCT ").append(String.join(", ", allReturnVars)).append(" LIMIT 1\n");

    sb.append("CREATE \n");
    List<String> createStmts = new ArrayList<>();
    if (mode == 0) addCreateObjectStmts(createStmts, rule.target.rhs, rule);
    if (mode == 1) addCreateObjectStmts(createStmts, rule.source.rhs, rule);
    addCorrRhsCreateStmts(createStmts, rule.correspondence.rhs, rule);
    sb.append(String.join(",\n", createStmts)).append("\n");

    appendObjectInstanceOfLinks(sb, rule, mode);

    for (TGGElement e : rule.correspondence.rhs) {
      if (e instanceof AttributeAssignment) {
        sb.append("SET ").append(((AttributeAssignment) e).code.replace(":=", "=")).append("\n");
      }
    }

    sb.append("RETURN 'Success';");
    return sb.toString();
  }


  private String[] findNodesForEdge(String edgeVar, TGGRule rule) {
    for (TGGBlock b : Arrays.asList(rule.source, rule.target)) {
      for (TGGElement e : b.lhs)
        if (e instanceof EdgeDecl && ("e"+edgeVar.substring(1)).equals(edgeVar)) return new String[]{((EdgeDecl)e).src, ((EdgeDecl)e).target};
      for (TGGElement e : b.rhs)
        if (e instanceof EdgeDecl) return new String[]{((EdgeDecl)e).src, ((EdgeDecl)e).target};
    }
    for (TGGElement e : rule.correspondence.lhs) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        return new String[]{c.corrName, c.nodeARef.replace("*","")};
      }
    }
    return new String[]{"nodeA", "nodeB"}; // Fallback
  }

  private String findTypeAcrossRule(String varName, TGGRule rule) {
    String cleanName = varName.replace("*", "").trim();
    for (TGGElement e : rule.source.lhs) if (e instanceof NodeDecl && ((NodeDecl)e).name.equals(cleanName)) return ((NodeDecl)e).type;
    for (TGGElement e : rule.source.rhs) if (e instanceof NodeDecl && ((NodeDecl)e).name.equals(cleanName)) return ((NodeDecl)e).type;
    for (TGGElement e : rule.target.lhs) if (e instanceof NodeDecl && ((NodeDecl)e).name.equals(cleanName)) return ((NodeDecl)e).type;
    for (TGGElement e : rule.target.rhs) if (e instanceof NodeDecl && ((NodeDecl)e).name.equals(cleanName)) return ((NodeDecl)e).type;
    for (TGGElement e : rule.correspondence.lhs) if (e instanceof CorrDecl && ((CorrDecl)e).corrName.equals(cleanName)) return ((CorrDecl)e).corrType;
    for (TGGElement e : rule.correspondence.rhs) if (e instanceof CorrDecl && ((CorrDecl)e).corrName.equals(cleanName)) return ((CorrDecl)e).corrType;

    return "Unknown";
  }

  private void addCorrRhsCreateStmtsl(List<String> stmts, List<TGGElement> elements, TGGRule rule) {
    for (TGGElement e : elements) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNode = c.nodeARef.replace("*", "");
        String newId = c.corrType + "_" + (System.currentTimeMillis() % 1000000);

        stmts.add(String.format("  (%s:`%s`:CorrespondenceObject {use_id: '%s', name: '%s', isCorrespondence: true})", c.corrName, c.corrType, newId, newId));

        String sCls = findTypeAcrossRule(c.nodeARef, rule);
        String tCls = findTypeAcrossRule(c.nodeBRef, rule);

        stmts.add(String.format("  (%s)-[:LinkAssociateWith {name: '%s', isCorrespondenceLink: true}]->(%s)", c.corrName, c.corrType + "_" + sCls, sNode));
        stmts.add(String.format("  (%s)-[:LinkAssociateWith {name: '%s', isCorrespondenceLink: true}]->(%s)", c.corrName, c.corrType + "_" + tCls, c.nodeBRef));
      }
    }
  }
  private void addCorrRhsCreateStmts(List<String> stmts, List<TGGElement> elements, TGGRule rule) {
    for (TGGElement e : elements) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNode = c.nodeARef.replace("*", "");
        String newId = c.corrType + "_" + (System.currentTimeMillis() % 1000000);

        stmts.add(String.format("  (%s:`%s`:CorrespondenceObject {use_id: '%s', name: '%s', isCorrespondence: true})",
            c.corrName, c.corrType, newId, newId));

        String sCls = findTypeAcrossRule(c.nodeARef, rule);
        String tCls = findTypeAcrossRule(c.nodeBRef, rule);

        stmts.add(String.format("  (%s)-[:LinkAssociateWith {name: '%s', isCorrespondenceLink: true}]->(%s)",
            c.corrName, c.corrType + "_" + sCls, sNode));
        stmts.add(String.format("  (%s)-[:LinkAssociateWith {name: '%s', isCorrespondenceLink: true}]->(%s)",
            c.corrName, c.corrType + "_" + tCls, c.nodeBRef));
      }
    }
  }
}