package org.uet.dse.neo4j.sync.model;

import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.tzi.use.api.UseModelApi;
import org.tzi.use.graph.DirectedGraph;
import org.tzi.use.uml.mm.*;
import org.tzi.use.uml.ocl.type.CollectionType;
import org.tzi.use.uml.ocl.type.Type;
import org.uet.dse.neo4j.helper.TypeMapper;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.manager.WorkLogManager;
import org.uet.dse.neo4j.mm.core.common.NCollectionType;
import org.uet.dse.neo4j.mm.core.common.NType;
import org.uet.dse.neo4j.mm.core.node.*;
import org.uet.dse.neo4j.query.builder.CypherQueryBuilder;
import org.uet.dse.neo4j.repo.Neo4jModelRepository;
import org.uet.dse.neo4j.sync.helper.UmlTypeTranslator;
import org.uet.dse.neo4j.sync.model.simpleDomain.KusakabeModelDiff;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class IncrementalModelPushService extends CoreModelPushService implements ModelPushService {
  private final UseModelApi modelApi;
  private final WorkLogManager logger = WorkLogManager.getInstance();

  private final Neo4jModelRepository repo;

  public IncrementalModelPushService(UseModelApi modelApi) {
    super(modelApi);
    this.modelApi = modelApi;
    this.repo = new Neo4jModelRepository();
    // Khởi tạo metamodel trước khi push
    this.repo.initializeMetamodel(modelApi.getModel().name());
  }

  @Override
  public void pushModelToNeo4j(ModelDiff modelDiff, MModel inputModel) {
    KusakabeModelDiff kusakabeModelDiff = (KusakabeModelDiff) modelDiff;
    MModel allUseModel;
    if (inputModel == null) {
      allUseModel = modelApi.getModel();
    } else {
      allUseModel = inputModel;
    }

    String currentUser = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

    logger.log("PUSH_START", "User " + currentUser + " initiated sync for model: " + allUseModel.name());

    try (Session session = Neo4jDriverManager.getInstance().openSession()) {
      repo.initializeMetamodel(inputModel.name());

      this.syncCoreEntities(session, allUseModel, kusakabeModelDiff);
      this.syncInheritance(session, allUseModel, kusakabeModelDiff);
      this.syncStructuralDetails(session, allUseModel, kusakabeModelDiff);
      this.syncAssociations(session, allUseModel, kusakabeModelDiff);

      updateModelVersion(session, currentUser);
      logger.log("PUSH_SUCCESS", "Model " + allUseModel.name() + " is now synchronized.");

    } catch (Exception e) {
      logger.log("PUSH_ERROR", "Failed to push model: " + e.getMessage());
      throw e;
    }

  }

  public void syncCoreEntities(Session session, MModel model, KusakabeModelDiff diff) {
    for (org.tzi.use.uml.ocl.type.EnumType e : model.enumTypes()) {
      if (isContaining(e.name(), diff.classDiff.isolated)) {
        repo.upsertEnumerationTC(e.name(), e.getLiterals(), model.name());
        logger.log("PUSH_ENUM", "Pushed enumeration: " + e.name());
      }
    }

    // Sync Classes (Concrete, Abstract, AssociationClass)
    for (MClass cls : model.classes()) {

      if (isContaining(cls.name(), diff.classDiff.isolated)) {
        createClassNodeTC(session, cls);
      }
    }
  }

  //may meet bug, added rules to avoid conflict
  public void syncInheritance(Session session, MModel model, KusakabeModelDiff kusakabeModelDiff) {
    DirectedGraph<MClassifier, MGeneralization> genGraph = model.generalizationGraph();
    Iterator<MGeneralization> edgeIterator = genGraph.edgeIterator();

    while (edgeIterator.hasNext()) {
      MGeneralization gen = edgeIterator.next();
      String parent = gen.parent().name();
      String child = gen.child().name();

      boolean isParentNew = isContaining(parent, kusakabeModelDiff.classDiff.isolated);
      boolean isChildNew = isContaining(child, kusakabeModelDiff.classDiff.isolated);
      if ((isChildNew && isParentNew)
          || (isChildNew && !isParentNew)
      ) {
        createExtendsEdge(session, gen.child(), gen.parent());
      }
    }
  }

  /**
   * refactored
   * @param session
   * @param child
   * @param parent
   */
  public void createExtendsEdge(Session session, MClassifier child, MClassifier parent) {
    String cypher = CypherQueryBuilder.buildExtendsEdgeQuery();

    session.run(cypher, Values.parameters(
        "srcName", child.name(),
        "tgtName", parent.name()
    ));
  }



  public void createClassNodeTC(Session session, MClass cls) {
    AbstractClassNode classNode;

    if (cls instanceof MAssociationClass) {
      classNode = new NodeAssociationClass(cls.name());
    } else if (cls.isAbstract()) {
      classNode = new NodeAbstractClass(cls.name());
    } else {
      classNode = new NodeConcreteClass(cls.name());
    }
    boolean isAbstract = cls.isAbstract();
    classNode.setName(cls.name());
    classNode.setHasParent(!cls.parents().isEmpty());
    classNode.setHasChildren(!cls.children().isEmpty());
    classNode.setAbstract(isAbstract);


    repo.upsertClassNode(classNode, cls.name(), modelApi.getModel().name());
  }

  public void syncStructuralDetails(Session session, MModel model, KusakabeModelDiff kusakabeModelDiff) {
    for (MClass cls : model.classes()) {
      if (isContaining(cls.name(), kusakabeModelDiff.classDiff.isolated)) {
        mapAttributes(session, cls);
        mapOperations(session, cls);
        mapInvariants(session, cls);
        mapOperationConstraintsForAllOperationsInClass(session, cls);
      }
    }
  }

  public void mapOperationConstraintsForAllOperationsInClass(Session session, MClass cls) {
    for (MOperation op : cls.operations()) {
      if (op.cls().equals(cls)) {
        String opId = cls.name() + "_" + op.name();
        mapOperationConstraintsForOneOperation(op, opId);
      }
    }
  }

  public void mapOperations(Session session, MClass cls) {
    for (MOperation op : cls.operations()) {
      if (!op.cls().equals(cls)) continue;

      String opId = cls.name() + "_" + op.name();
      Map<String, Object> rInfo = TypeMapper.parseTypeUSE2Neo4j(op.resultType());

      NodeOperation opNode = new NodeOperation(op.name());
      opNode.setName(opId);
      opNode.setOpName(op.name());
      String rTypeStr = (String) rInfo.get("type");
      opNode.setReturnType(NType.fromString(rTypeStr));
      opNode.setReturnCollection((Boolean) rInfo.get("isCollection"));
      opNode.setAbstract(cls.isAbstract());

      String body = null;
      boolean isQuery = true;
      if (op.hasExpression()) {
        body = op.expression().toString();
        isQuery = true;
      } else if (op.hasStatement()) {
        body = op.getStatement().toString();
        isQuery = false;
      }
      opNode.setBody(body);
      opNode.setQuery(isQuery);

      repo.createInstanceNode(opNode, "Operation", modelApi.getModel().name());
      repo.createStructuralEdge(cls.name(), opNode.getName(), "HasOperation");

      TypeMapper.handleTypeReference(repo, opNode.getName(), op.resultType(), true);

      int order = 1;
      for (org.tzi.use.uml.ocl.expr.VarDecl param : op.paramList()) {
        Map<String, Object> pInfo = TypeMapper.parseTypeUSE2Neo4j(param.type());
        String paramId = opId + "_p_" + param.name();

        NodeParam paramNode = new NodeParam(paramId);
        paramNode.setName(param.name());
        paramNode.setpName(param.name());
        paramNode.setpOrder(order++);
        paramNode.setCollection((Boolean) pInfo.get("isCollection"));
        String pTypeStr = (String) pInfo.get("type");
        paramNode.setType(NType.fromString(pTypeStr));

        if ((Boolean) pInfo.get("isCollection")) {
          String pColTypeStr = (String) pInfo.get("collType");
          paramNode.setCollectionType(NCollectionType.valueOf(pColTypeStr));
        } else {
          paramNode.setCollectionType(NCollectionType.None);
        }

        repo.createInstanceNode(paramNode, "Param", modelApi.getModel().name());
        repo.createStructuralEdge(opNode.getName(), paramNode.getName(), "HasParam");

        TypeMapper.handleTypeReference(repo, paramNode.getName(), param.type(), false);
      }

      mapOperationConstraintsForOneOperation(op, opNode.getName());
    }
  }
  /**
   * refactored
   * pre/post
   */
  public void mapOperationConstraintsForOneOperation(MOperation op, String opId) {
    int preIdx = 0;
    for (MPrePostCondition pre : op.preConditions()) {
      NodePreCondition preNode = new NodePreCondition(pre.name());

      preNode.setName(opId + "_pre_" + preIdx);
      preNode.setCondName(pre.name());
      preNode.setExpression(pre.expression().toString());
      preNode.setIndex(preIdx++);

      repo.createInstanceNode(preNode, "PreCondition", modelApi.getModel().name());
      repo.createStructuralEdge(opId, preNode.getName(), "HasPreCondition");
    }

    int postIdx = 0;
    for (MPrePostCondition post : op.postConditions()) {
      NodePostCondition postNode = new NodePostCondition(post.name());

      postNode.setName(opId + "_post_" + postIdx);
      postNode.setCondName(post.name());
      postNode.setExpression(post.expression().toString());
      postNode.setIndex(postIdx++);

      repo.createInstanceNode(postNode, "PostCondition", modelApi.getModel().name());
      repo.createStructuralEdge(opId, postNode.getName(), "HasPostCondition");
    }
  }
  /**
   * refactored
   * @param session
   * @param cls
   */
  public void mapAttributes(Session session, MClass cls) {
    int index = 0;
    for (MAttribute attr : cls.attributes()) {
      Type fullType = attr.type();
      String instanceId = cls.name() + "_" + attr.name();

      Type ultimateBaseType = UmlTypeTranslator.getUltimateBaseType(fullType);
      int depth = UmlTypeTranslator.getCollectionDepth(fullType);
      boolean isCollection = depth > 0;
      boolean isNested = depth > 1;

      NodeAttribute nodeAttr = new NodeAttribute(instanceId);
      nodeAttr.setName(instanceId);
      nodeAttr.setAttrName(attr.name());
      nodeAttr.setIndex(index++);
      nodeAttr.setNestedCollection(isNested);
      nodeAttr.setCollection(isCollection);
      nodeAttr.setType(UmlTypeTranslator.toDatabaseType(ultimateBaseType));
      nodeAttr.setDerived(attr.isDerived());
      if (isCollection) {
        String colTypeStr = UmlTypeTranslator.getCollectionType(fullType);
        try {
          nodeAttr.setCollectionType(NCollectionType.valueOf(colTypeStr));
        } catch (IllegalArgumentException e) {
          nodeAttr.setCollectionType(NCollectionType.None);
        }
      } else {
        nodeAttr.setCollectionType(NCollectionType.None);
      }


      repo.createInstanceNode(nodeAttr, "Attribute", modelApi.getModel().name());
      repo.createStructuralEdge(cls.name(), nodeAttr.getName(), "HasAttribute");

      TypeMapper.handleTypeReference(repo, nodeAttr.getName(), ultimateBaseType, false);

      if (isNested) {
        pushNestedCollectionNodes(nodeAttr.getName(), ((CollectionType) fullType).elemType());
      }
    }
  }

  public void syncAssociations(Session session, MModel allUseModel, KusakabeModelDiff kusakabeModelDiff) {
    for (MAssociation assoc : allUseModel.associations()) {
      if (isContaining(assoc.name(), kusakabeModelDiff.associationDiff.isolated)) {
        if (assoc.associationEnds().size() > 2) {
          repo.upsertTernaryAssociation(assoc, modelApi.getModel().name());
        } else if (assoc instanceof MAssociationClass) {
          repo.createAssociationClassStructure((MAssociationClass) assoc, modelApi.getModel().name());
        } else {
          repo.createAssociationEdge(assoc);
        }
      }
    }
  }

  public boolean isContaining(String value, List<String> collection) {
    return collection.stream()
        .anyMatch(ei -> ei.equals(value));
  }

}
