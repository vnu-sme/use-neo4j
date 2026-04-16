package org.uet.dse.neo4j.model_trafo.matching;

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

  public String generateExecutionQuery(TGGRule rule, String srcM, String trgM, int mode, Map<String, String> candidate) {
    StringBuilder sb = new StringBuilder();
    String corrM = srcM + "2" + trgM + "_Corr";

    sb.append(String.format("// --- ATOMIC EXECUTION (%s) FOR RULE: %s ---\n", getModeName(mode), rule.name));

    // 1. MATCH các ManageModel mốc
    sb.append(String.format("MATCH (mSrc:ManageModel {name: '%s'})\n", srcM));
    sb.append(String.format("MATCH (mTrg:ManageModel {name: '%s'})\n", trgM));
    sb.append(String.format("MATCH (mCorr:ManageModel {name: '%s'})\n", corrM));

    // 2. MATCH MetaNodes (Mỏ neo để tìm đúng Class của từng Model)
    // Mỗi ManageModel có một MetaNode tên 'NodeConcreteClass' riêng
    sb.append("MATCH (mSrc)-[:DefineMetamodels]->(metaS:MetaNode {name: 'NodeConcreteClass'})\n");
    sb.append("MATCH (mTrg)-[:DefineMetamodels]->(metaT:MetaNode {name: 'NodeConcreteClass'})\n");
    sb.append("MATCH (mCorr)-[:DefineMetamodels]->(metaC:MetaNode {name: 'NodeConcreteClass'})\n");

    // 3. MATCH TẤT CẢ Class Node xuất hiện trong luật (Sử dụng nhãn :NodeConcreteClass như dump của bạn)
    Set<String> allClassNames = collectAllClassesInRule(rule);
    for (String cls : allClassNames) {
      String metaVar = findMetaVarForClass(cls, rule);
      // Phải đi qua InstanceOf nối vào đúng MetaNode của Model đó
      sb.append(String.format("MATCH (cls_%s:NodeConcreteClass {name: '%s'})-[:InstanceOf]->(%s)\n", cls, cls, metaVar));
    }

    sb.append("WITH * \n// Bind Candidate Objects and Links from Match Step\n");

    // 4. MATCH Object bằng use_id và Link bằng elementId
    for (Map.Entry<String, String> entry : candidate.entrySet()) {
      String var = entry.getKey();
      String id = entry.getValue();

      if (var.startsWith("e") || var.startsWith("rel")) {
        // Khóa Link bằng elementId
        sb.append(String.format("MATCH ()-[%s]-() WHERE elementId(%s) = '%s'\n", var, var, id));
      } else {
        // Khóa Object bằng use_id VÀ kiểm tra xem nó có nối tới Class tương ứng không
        String typeName = findTypeAcrossRule(var, rule);
        sb.append(String.format("MATCH (%s {use_id: '%s'})-[:ObjectInstanceOf]->(cls_%s)\n", var, id, typeName));
      }
    }

    // 5. CREATE: Tạo các thực thể mới (RHS)
    sb.append("CREATE \n");
    List<String> createStmts = new ArrayList<>();
    if (mode == 0) addCreateObjectStmts(createStmts, rule.target.rhs, rule);
    if (mode == 1) addCreateObjectStmts(createStmts, rule.source.rhs, rule);
    addCorrRhsCreateStmts(createStmts, rule.correspondence.rhs, rule);
    sb.append(String.join(",\n", createStmts)).append("\n");

    // 6. THIẾT LẬP INSTANCE OF CHO CÁC NODE MỚI (Nối vào cls_ đã MATCH ở trên)
    sb.append("// Link new Objects to Metamodel Classes\n");
    appendObjectInstanceOfLinks(sb, rule, mode);

    // 7. SET ATTRIBUTES
    for (TGGElement e : rule.correspondence.rhs) {
      if (e instanceof AttributeAssignment) {
        sb.append("SET ").append(((AttributeAssignment) e).code.replace(":=", "=")).append("\n");
      }
    }

    sb.append("RETURN 'Transformation Successful' AS Status, *;");
    return sb.toString();
  }

  private String findMetaVarForClass(String clsName, TGGRule rule) {
    for (TGGElement e : rule.correspondence.lhs) {
      if (e instanceof CorrDecl && ((CorrDecl) e).corrType.equals(clsName)) return "metaC";
    }
    for (TGGElement e : rule.correspondence.rhs) {
      if (e instanceof CorrDecl && ((CorrDecl) e).corrType.equals(clsName)) return "metaC";
    }

    for (TGGElement e : rule.target.lhs) {
      if (e instanceof NodeDecl && ((NodeDecl) e).type.equals(clsName)) return "metaT";
    }
    for (TGGElement e : rule.target.rhs) {
      if (e instanceof NodeDecl && ((NodeDecl) e).type.equals(clsName)) return "metaT";
    }

    return "metaS";
  }

  private Set<String> collectAllClassesInRule(TGGRule rule) {
    Set<String> classes = new HashSet<>();
    List<TGGBlock> all = Arrays.asList(rule.source, rule.target, rule.correspondence);
    for (TGGBlock b : all) {
      for (TGGElement e : b.lhs) {
        if (e instanceof NodeDecl) classes.add(((NodeDecl)e).type);
        if (e instanceof CorrDecl) classes.add(((CorrDecl)e).corrType);
      }
      for (TGGElement e : b.rhs) {
        if (e instanceof NodeDecl) classes.add(((NodeDecl)e).type);
        if (e instanceof CorrDecl) classes.add(((CorrDecl)e).corrType);
      }
    }
    return classes;
  }



  private String getModeName(int mode) {
    return (mode == 0) ? "FORWARD" : (mode == 1) ? "BACKWARD" : "INTEGRATION";
  }

  public String generateFindMatchQueryData(TGGRule rule, String srcM, String trgM, int mode) {
    edgeCounter = 0;
    StringBuilder sb = new StringBuilder();
    String corrM = srcM + "2" + trgM + "_Corr";

    sb.append("// --- STEP 1: FETCH MATCHING TUPLES (FIXED FOR REAL DATA) --- \n");

    sb.append(String.format("MATCH (mSrc:ManageModel {name: '%s'})-[:DefineMetamodels]->(metaS:MetaNode {name: 'NodeConcreteClass'})\n", srcM));
    sb.append(String.format("MATCH (mTrg:ManageModel {name: '%s'})-[:DefineMetamodels]->(metaT:MetaNode {name: 'NodeConcreteClass'})\n", trgM));
    sb.append(String.format("MATCH (mCorr:ManageModel {name: '%s'})-[:DefineMetamodels]->(metaC:MetaNode {name: 'NodeConcreteClass'})\n", corrM));

    List<String> patterns = new ArrayList<>();
    List<String> filters = new ArrayList<>();
    List<String> returnItems = new ArrayList<>();

    processObjectBlock(rule.source.lhs, patterns, filters, returnItems, "metaS");
    if (mode == 0 || mode == 2) processObjectBlock(rule.source.rhs, patterns, filters, returnItems, "metaS");


    processObjectBlock(rule.target.lhs, patterns, filters, returnItems, "metaT");
    if (mode == 1 || mode == 2) processObjectBlock(rule.target.rhs, patterns, filters, returnItems, "metaT");
    if (patterns.isEmpty()) {
      return "RETURN 'Error: Rule has no input elements' AS Error";
    }

    // corr link fetching
    for (TGGElement e : rule.correspondence.lhs) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNode = c.nodeARef.replace("*", "");
        String tNode = c.nodeBRef;
        String e1 = "rel" + (edgeCounter++);
        String e2 = "rel" + (edgeCounter++);
        String clsVar = "cls_" + c.corrName;

        patterns.add(String.format("(%s)-[%s]-(%s)-[%s]-(%s)", sNode, e1, c.corrName, e2, tNode));
        filters.add(String.format("type(%s) = 'LinkAssociateWith'", e1));
        filters.add(String.format("type(%s) = 'LinkAssociateWith'", e2));

        patterns.add(String.format("(%s)-[:ObjectInstanceOf]->(%s)-[:InstanceOf]->(metaC)", c.corrName, clsVar));
        filters.add(String.format("%s.name = '%s'", clsVar, c.corrType));

        returnItems.add(c.corrName + ".use_id AS " + c.corrName);
        returnItems.add(String.format("elementId(%s) AS %s", e1, e1));
        returnItems.add(String.format("elementId(%s) AS %s", e2, e2));
      }
    }

    sb.append("  MATCH ").append(String.join(",\n    ", patterns)).append("\n");
    sb.append("  WHERE ").append(String.join("\n    AND ", filters)).append("\n");

    sb.append("  AND NOT EXISTS {\n");
    for (TGGElement e : rule.correspondence.rhs) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNode = c.nodeARef.replace("*", "");
        sb.append(String.format("    MATCH (%s)-[:LinkAssociateWith]-(existing)-[:LinkAssociateWith]-(%s)\n", sNode, c.nodeBRef));
        sb.append(String.format("    MATCH (existing)-[:ObjectInstanceOf]->(exCls) WHERE exCls.name = '%s'\n", c.corrType));
      }
    }
    sb.append("  }\n");

    sb.append("RETURN DISTINCT ").append(String.join(", ", returnItems));

    return sb.toString();
  }


  private void processObjectBlock(List<TGGElement> elements, List<String> patterns, List<String> filters, List<String> returnItems, String metaVar) {
    for (TGGElement e : elements) {
      if (e instanceof NodeDecl) {
        NodeDecl n = (NodeDecl) e;
        String clsVar = "cls_" + n.name;
        patterns.add(String.format("(%s:`%s`)-[:ObjectInstanceOf]->(%s)-[:InstanceOf]->(%s)", n.name, n.type, clsVar, metaVar));
        filters.add(String.format("%s.name = '%s'", clsVar, n.type));
        returnItems.add(String.format("%s.use_id AS %s", n.name, n.name));
      } else if (e instanceof EdgeDecl) {
        EdgeDecl ed = (EdgeDecl) e;
        String eVar = "e" + (edgeCounter++);
        // undirected link of Object
        patterns.add(String.format("(%s)-[%s]-(%s)", ed.src, eVar, ed.target));
        filters.add(String.format("%s.name = '%s' AND type(%s) STARTS WITH 'Link'", eVar, ed.type, eVar));
        returnItems.add(String.format("elementId(%s) AS %s", eVar, eVar));
      }
    }
  }


  public String generateExecuteMatchQuery(TGGRule rule, String srcM, String trgM, int mode, Map<String, String> candidate) {
    StringBuilder sb = new StringBuilder();
    String corrM = srcM + "2" + trgM + "_Corr";

    sb.append(String.format("// --- EXECUTION (%s) FOR RULE: %s ---\n", getModeName(mode), rule.name));

    // 1. MATCH các ManageModel mốc
    sb.append(String.format("MATCH (mSrc:ManageModel {name: '%s'})\n", srcM));
    sb.append(String.format("MATCH (mTrg:ManageModel {name: '%s'})\n", trgM));
    sb.append(String.format("MATCH (mCorr:ManageModel {name: '%s'})\n", corrM));

    // 2. MATCH tất cả các Class Node liên quan trong luật (để nối ObjectInstanceOf)
    sb.append("// Match Classes from Metamodels\n");
    Set<String> allClassNames = collectAllClassesInRule(rule);
    for (String cls : allClassNames) {
      String mVar = findModelVarNameForClass(cls, rule);
      sb.append(String.format("MATCH (%s)-[:DefineMetamodels]->(cls_%s {name: '%s'})\n", mVar, cls, cls));
    }

    sb.append("WITH * \n");

    // 3. MATCH các Object & Link đã chọn từ bước Matching (Candidate)
    sb.append("// Bind Candidate Objects and Links\n");
    for (Map.Entry<String, String> entry : candidate.entrySet()) {
      String varName = entry.getKey();
      String idValue = entry.getValue();

      if (varName.startsWith("e") || varName.startsWith("rel")) {
        sb.append(String.format("MATCH ()-[%s]-() WHERE elementId(%s) = '%s'\n", varName, varName, idValue));
      } else {
        sb.append(String.format("MATCH (%s {use_id: '%s'})\n", varName, idValue));
      }
    }

    // 4. CREATE các thành phần mới (RHS)
    sb.append("CREATE \n");
    List<String> createStmts = new ArrayList<>();

    if (mode == 0) addCreateObjectStmts(createStmts, rule.target.rhs, rule);
    if (mode == 1) addCreateObjectStmts(createStmts, rule.source.rhs, rule);

    addCorrRhsCreateStmts(createStmts, rule.correspondence.rhs, rule);

    sb.append(String.join(",\n", createStmts)).append("\n");

    // 5. Nối ObjectInstanceOf cho các thực thể mới tạo
    sb.append("// Link new Objects to their Metamodel Classes\n");
    appendObjectInstanceOfLinks(sb, rule, mode);

    sb.append("RETURN 'Transformation Successful' AS Status;");
    return sb.toString();
  }

  private void addCreateObjectStmts(List<String> stmts, List<TGGElement> elements, TGGRule rule) {
    for (TGGElement e : elements) {
      if (e instanceof NodeDecl) {
        NodeDecl n = (NodeDecl) e;
        String newId = n.type + "_" + (System.currentTimeMillis() % 1000000);
        stmts.add(String.format("  (%s:`%s` {use_id: '%s', name: '%s', isCorrespondence: false})",
            n.name, n.type, newId, newId));
      } else if (e instanceof EdgeDecl) {
        EdgeDecl ed = (EdgeDecl) e;
        // Logic nhận diện link: PersonRegistration, Father, Mother, Sons, Daughters là ComposeOf
        String typeLower = ed.type.toLowerCase();
        String label = (typeLower.contains("registration") || typeLower.contains("father") ||
            typeLower.contains("mother") || typeLower.contains("son") ||
            typeLower.contains("daughter")) ? "LinkComposeOf" : "LinkAssociateWith";

        stmts.add(String.format("  (%s)-[:%s {name: '%s'}]->(%s)", ed.src, label, ed.type, ed.target));
      }
    }
  }

  private void addCorrRhsCreateStmts(List<String> stmts, List<TGGElement> elements, TGGRule rule) {
    for (TGGElement e : elements) {
      if (e instanceof CorrDecl) {
        CorrDecl c = (CorrDecl) e;
        String sNode = c.nodeARef.replace("*", "");
        String newId = c.corrType + "_" + (System.currentTimeMillis() % 1000000);

        stmts.add(String.format("  (%s:`%s` {use_id: '%s', name: '%s', isCorrespondence: true})",
            c.corrName, c.corrType, newId, newId));
        // Bridge links luôn là LinkAssociateWith
        stmts.add(String.format("  (%s)-[:LinkAssociateWith {name: '%s'}]->(%s)",
            c.corrName, c.corrType + "_" + findTypeAcrossRule(c.nodeARef, rule), sNode));
        stmts.add(String.format("  (%s)-[:LinkAssociateWith {name: '%s'}]->(%s)",
            c.corrName, c.corrType + "_" + findTypeAcrossRule(c.nodeBRef, rule), c.nodeBRef));
      }
    }
  }

  private void appendObjectInstanceOfLinks(StringBuilder sb, TGGRule rule, int mode) {
    // Chỉ nối cho những biến được tạo mới trong CREATE
    if (mode == 0) { // Forward: tạo Target + Corr
      for (TGGElement e : rule.target.rhs)
        if (e instanceof NodeDecl) sb.append(String.format("MERGE (%s)-[:ObjectInstanceOf]->(cls_%s)\n", ((NodeDecl)e).name, ((NodeDecl)e).type));
    } else if (mode == 1) { // Backward: tạo Source + Corr
      for (TGGElement e : rule.source.rhs)
        if (e instanceof NodeDecl) sb.append(String.format("MERGE (%s)-[:ObjectInstanceOf]->(cls_%s)\n", ((NodeDecl)e).name, ((NodeDecl)e).type));
    }
    for (TGGElement e : rule.correspondence.rhs)
      if (e instanceof CorrDecl) sb.append(String.format("MERGE (%s)-[:ObjectInstanceOf]->(cls_%s)\n", ((CorrDecl)e).corrName, ((CorrDecl)e).corrType));
  }

  private String findModelVarNameForClass(String clsName, TGGRule rule) {
    // Duyệt tìm xem Class thuộc model nào để trả về mSrc, mTrg hoặc mCorr
    for (TGGElement e : rule.source.lhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return "mSrc";
    for (TGGElement e : rule.source.rhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return "mSrc";
    for (TGGElement e : rule.target.lhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return "mTrg";
    for (TGGElement e : rule.target.rhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return "mTrg";
    return "mCorr";
  }



  private String findModelVarForClass(String clsName, TGGRule rule, String s, String t, String c) {
    for (TGGBlock b : Arrays.asList(rule.source)) {
      for (TGGElement e : b.lhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return s;
      for (TGGElement e : b.rhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return s;
    }
    for (TGGBlock b : Arrays.asList(rule.target)) {
      for (TGGElement e : b.lhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return t;
      for (TGGElement e : b.rhs) if (e instanceof NodeDecl && ((NodeDecl)e).type.equals(clsName)) return t;
    }
    return c;
  }
}