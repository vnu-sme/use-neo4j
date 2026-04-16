package org.uet.dse.neo4j.model_trafo;

import org.uet.dse.neo4j.model_trafo.parser.*;

import java.util.*;

public class CorrMetamodelGenerator {
  private int edgeCounter = 0;

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
            bridges.add(new BridgeAssocData(corr.corrType + "_" + sCls, corr.corrType, sCls, "mSrc", corr.corrName, corr.aliasA));
          }
          if (tCls != null) {
            bridges.add(new BridgeAssocData(corr.corrType + "_" + tCls, corr.corrType, tCls, "mTrg", corr.corrName, corr.aliasB));
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
          "// Link %s\n" + "MATCH (%s)-[:DefineMetamodels]->(metaT:MetaNode {name: 'NodeConcreteClass'})\n" + "MATCH (targetCls:`%s` {name: '%s'})-[:InstanceOf]->(metaT)\n" + "MATCH (mCorr)-[:DefineMetamodels]->(corrCls:`%s` {name: '%s'})\n" + "MERGE (corrCls)-[r:AssociateWith {associationName: '%s'}]->(targetCls)\n" + "SET r.isTernary = false, \n" + "    r.sourceClassName = '%s', \n" + "    r.sourceClassrole = '%s', \n" + "    r.sourceKind = 0, \n" + "    r.sourceMultiplicity = '1', \n" + "    r.targetClassName = '%s', \n" + "    r.targerClassrole = '%s', \n" + "    r.targetKind = 0, \n" + "    r.targetMultiplicity = '0..1'\n",
          b.assocName, b.targetModelVar, b.targetClassType, b.targetClassType, b.corrType, b.corrType, b.assocName, b.corrType, b.corrRole, b.targetClassType, b.targetRole));

      if (i < bridges.size() - 1) {
        sb.append("WITH mSrc, mTrg, mCorr, metaCorr\n");
      }
    }

    sb.append("\nRETURN 'Correspondence Metamodel Created Successfully' AS Status;");
    return sb.toString();
  }

  private String findTypeAcrossRule(String nodeRef, TGGRule rule) {
    if (nodeRef == null)
      return null;
    String name = nodeRef.replace("*", "").trim();
    List<TGGBlock> blocks = Arrays.asList(rule.source, rule.target);
    for (TGGBlock b : blocks) {
      for (TGGElement e : b.lhs)
        if (e instanceof NodeDecl && ((NodeDecl) e).name.equals(name))
          return ((NodeDecl) e).type;
      for (TGGElement e : b.rhs)
        if (e instanceof NodeDecl && ((NodeDecl) e).name.equals(name))
          return ((NodeDecl) e).type;
    }
    return null;
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

  private void resolveTypes(CorrDecl corr, TGGRule rule) {
    String sName = corr.nodeARef.replace("*", "");
    String tName = corr.nodeBRef.replace("*", "");
    corr.sourceClassType = findType(sName, rule.source);
    corr.targetClassType = findType(tName, rule.target);
  }

  private String findType(String name, TGGBlock block) {
    for (TGGElement e : block.lhs)
      if (e instanceof NodeDecl && ((NodeDecl) e).name.equals(name))
        return ((NodeDecl) e).type;
    for (TGGElement e : block.rhs)
      if (e instanceof NodeDecl && ((NodeDecl) e).name.equals(name))
        return ((NodeDecl) e).type;
    return null;
  }


  public String generateMatchingQuery(TGGRule rule, String srcM, String trgM, int mode) {
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
    if (mode == 0 || mode == 2) processObjectBlock(rule.source.rhs, patterns, filters, "metaS");

    processObjectBlock(rule.target.lhs, patterns, filters, "metaT");
    if (mode == 1 || mode == 2) processObjectBlock(rule.target.rhs, patterns, filters, "metaT");

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

  public String generateExecutionQuery(TGGRule rule, String srcM, String trgM, int mode) {
    StringBuilder sb = new StringBuilder();
    String corrM = srcM + "2" + trgM + "_Corr";

    sb.append(String.format("// --- EXECUTION (%s) FOR RULE: %s ---\n", getModeName(mode), rule.name));

    sb.append("MATCH (mSrc:ManageModel {name: '").append(srcM).append("'}),\n");
    sb.append("      (mTrg:ManageModel {name: '").append(trgM).append("'}),\n");
    sb.append("      (mCorr:ManageModel {name: '").append(corrM).append("'})\n");
    sb.append("MATCH (metaNCC:MetaNode {name: 'NodeConcreteClass'})\n");

    sb.append("// Match Classes for instantiation\n");
    Set<String> neededClasses = collectNeededClasses(rule, mode);
    for (String cls : neededClasses) {
      String mVar = findModelVarForClass(cls, rule, srcM, trgM, corrM);
      sb.append(String.format("MATCH (m%s:ManageModel {name: '%s'})-[:DefineMetamodels]->(cls_%s:`%s` {name: '%s'})\n",
          cls, mVar, cls, cls, cls));
    }

    sb.append("WITH * \nMATCH \n");
    List<String> matchPatterns = new ArrayList<>();
    addMatchPatterns(matchPatterns, rule.source.lhs, "mSrc");
    addMatchPatterns(matchPatterns, rule.target.lhs, "mTrg");
    addCorrLhsMatch(matchPatterns, rule.correspondence.lhs);

    if (mode == 0 || mode == 2) addMatchPatterns(matchPatterns, rule.source.rhs, "mSrc");
    if (mode == 1 || mode == 2) addMatchPatterns(matchPatterns, rule.target.rhs, "mTrg");

    sb.append(String.join(",\n", matchPatterns)).append("\n");

    sb.append("CREATE \n");
    List<String> createStmts = new ArrayList<>();

    if (mode == 0) addCreateObjectStmts(createStmts, rule.target.rhs, rule);
    if (mode == 1) addCreateObjectStmts(createStmts, rule.source.rhs, rule);

    addCorrRhsCreateStmts(createStmts, rule.correspondence.rhs, rule);

    sb.append(String.join(",\n", createStmts)).append("\n");

    sb.append("// Link Objects to their Classes\n");
    appendObjectInstanceOfLinks(sb, rule, mode);

    for (TGGElement e : rule.correspondence.rhs) {
      if (e instanceof AttributeAssignment) {
        sb.append("SET ").append(((AttributeAssignment) e).code.replace(":=", "=")).append("\n");
      }
    }

    sb.append("RETURN *");
    return sb.toString();
  }

  private void addCreateObjectStmts(List<String> stmts, List<TGGElement> elements, TGGRule rule) {
    for (TGGElement e : elements) {
      if (e instanceof NodeDecl) {
        NodeDecl n = (NodeDecl) e;
        stmts.add(String.format("  (%s:`%s` {use_id: $id_%s, name: $id_%s, isCorrespondence: false})",
            n.name, n.type, n.name, n.name));
      } else if (e instanceof EdgeDecl) {
        EdgeDecl ed = (EdgeDecl) e;
        stmts.add(String.format("  (%s)-[:AssociateWith {associationName: '%s'}]->(%s)", ed.src, ed.type, ed.target));
      }
    }
  }

  private void addCorrRhsCreateStmts(List<String> stmts, List<TGGElement> elements, TGGRule rule) {
    for (TGGElement e : elements) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNode = c.nodeARef.replace("*", "");
        String tNode = c.nodeBRef;

        stmts.add(String.format("  (%s:`%s` {use_id: $id_%s, name: $id_%s, isCorrespondence: true})",
            c.corrName, c.corrType, c.corrName, c.corrName));
        stmts.add(String.format("  (%s)-[:AssociateWith {associationName: '%s'}]->(%s)", c.corrName, c.corrType + "_" + findTypeAcrossRule(c.nodeARef, rule), sNode));
        stmts.add(String.format("  (%s)-[:AssociateWith {associationName: '%s'}]->(%s)", c.corrName, c.corrType + "_" + findTypeAcrossRule(c.nodeBRef, rule), tNode));
      }
    }
  }

  private void appendObjectInstanceOfLinks(StringBuilder sb, TGGRule rule, int mode) {
    List<TGGElement> toLink = new ArrayList<>();
    if (mode == 0) toLink.addAll(rule.target.rhs);
    if (mode == 1) toLink.addAll(rule.source.rhs);
    toLink.addAll(rule.correspondence.rhs);

    for (TGGElement e : toLink) {
      if (e instanceof NodeDecl) {
        NodeDecl n = (NodeDecl) e;
        sb.append(String.format("MERGE (%s)-[:ObjectInstanceOf]->(cls_%s)\n", n.name, n.type));
      } else if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        sb.append(String.format("MERGE (%s)-[:ObjectInstanceOf]->(cls_%s)\n", c.corrName, c.corrType));
      }
    }
  }


  private Set<String> collectNeededClasses(TGGRule rule, int mode) {
    Set<String> classes = new HashSet<>();
    if (mode == 0) for (TGGElement e : rule.target.rhs) if (e instanceof NodeDecl) classes.add(((NodeDecl)e).type);
    if (mode == 1) for (TGGElement e : rule.source.rhs) if (e instanceof NodeDecl) classes.add(((NodeDecl)e).type);
    for (TGGElement e : rule.correspondence.rhs) if (e instanceof CorrDecl) classes.add(((CorrDecl)e).corrType);
    return classes;
  }

  private String findModelVarForClass(String clsName, TGGRule rule, String s, String t, String c) {
    for (TGGElement e : rule.source.lhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return s;
    for (TGGElement e : rule.source.rhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return s;
    for (TGGElement e : rule.target.lhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return t;
    for (TGGElement e : rule.target.rhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return t;
    return c;
  }


  private void addMatchPatterns(List<String> patterns, List<TGGElement> elements, String modelVar) {
    for (TGGElement e : elements) {
      if (e instanceof NodeDecl) {
        NodeDecl n = (NodeDecl) e;
        patterns.add(String.format("  (%s:`%s` {model: '%s'})", n.name, n.type, modelVar.equals("mSrc") ? "Families" : "Persons"));
      } else if (e instanceof EdgeDecl) {
        EdgeDecl ed = (EdgeDecl) e;
        patterns.add(String.format("  (%s)-[:AssociateWith {associationName: '%s'}]->(%s)", ed.src, ed.type, ed.target));
      }
    }
  }


  private String getModeName(int mode) {
    return (mode == 0) ? "FORWARD" : (mode == 1) ? "BACKWARD" : "INTEGRATION";
  }

  private void addCorrLhsMatch(List<String> patterns, List<TGGElement> elements) {
    for (TGGElement e : elements) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;

        String sNodeVar = c.nodeARef.replace("*", "").trim();
        String tNodeVar = c.nodeBRef.replace("*", "").trim();
        String corrVar = c.corrName;
        String corrType = c.corrType;

        patterns.add(String.format("  (%s)-[:AssociateWith]->(%s:`%s` {isCorrespondence: true})-[:AssociateWith]->(%s)",
            sNodeVar, corrVar, corrType, tNodeVar));

        patterns.add(String.format("  (%s)-[:ObjectInstanceOf]->(cls_%s)",
            corrVar, corrType));
      }
    }
  }
}