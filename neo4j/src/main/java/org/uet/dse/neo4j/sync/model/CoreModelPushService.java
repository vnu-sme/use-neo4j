package org.uet.dse.neo4j.sync.model;

import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.tzi.use.api.UseModelApi;
import org.tzi.use.graph.DirectedGraph;
import org.tzi.use.uml.mm.*;
import org.tzi.use.uml.ocl.expr.VarDecl;
import org.tzi.use.uml.ocl.expr.VarDeclList;
import org.tzi.use.uml.ocl.type.CollectionType;
import org.tzi.use.uml.ocl.type.Type;
import org.uet.dse.neo4j.helper.TypeMapper;
import org.uet.dse.neo4j.loose_coupling.*;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.mm.core.edge.Extends;
import org.uet.dse.neo4j.mm.core.edge.NodeBinaryAssociation;
import org.uet.dse.neo4j.mm.core.node.*;
import org.uet.dse.neo4j.manager.WorkLogManager;
import org.uet.dse.neo4j.repo.Neo4jModelRepository;
import org.uet.dse.neo4j.sync.helper.UmlTypeTranslator;
import org.uet.dse.neo4j.sync.object.ObjectSyncHelper;

import java.util.*;

public class CoreModelPushService {
    private final UseModelApi modelApi;
    private final WorkLogManager logger = WorkLogManager.getInstance();

    private final Neo4jModelRepository repo;

    public CoreModelPushService(UseModelApi modelApi) {
        this.modelApi = modelApi;
        this.repo = new Neo4jModelRepository();
        // Khởi tạo metamodel trước khi push
        this.repo.initializeMetamodel(modelApi.getModel().name());
    }

    public void pushModelToNeo4j() {
        //get session model, the model session model SOT is taken from USE core
        MModel model = modelApi.getModel();
        String currentUser = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

        logger.log("PUSH_START", "User " + currentUser + " initiated sync for model: " + model.name());

        //init meta-meta node, including create management node
        repo.initializeMetamodel(model.name());

        syncCoreEntities(model);
        syncInheritance(model);
        syncStructuralDetails(model);
        syncAssociations(model);

        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            updateModelVersion(session, currentUser);
            logger.log("PUSH_SUCCESS", "Model " + model.name() + " is now synchronized.");

        } catch (Exception e) {
            logger.log("PUSH_ERROR", "Failed to push model: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Sync enums and class
     */
    public void syncCoreEntities(MModel model) {
        // Sync Enums
        UseModelTranslators translators = ModelTranslatorFactory.forUse();

        // Enums → domain → repo
        model.enumTypes().stream()
            .map(translators.enumTranslator()::translate)
            .forEach(node -> {
                repo.upsertEnumeration(node, model.name());
                logger.log("PUSH_ENUM", "Pushed enumeration: " + node.getName());
            });


        // Classes → domain → repo
        model.classes().stream()
            .map(translators.classTranslator()::translate)
            .forEach(node -> repo.upsertClassNode(node, node.getName(), model.name()));
    }

    public void syncInheritance(MModel model) {
        DirectedGraph<MClassifier ,MGeneralization> genGraph = model.generalizationGraph();
        Iterator<MGeneralization> edgeIterator = genGraph.edgeIterator();

        UseModelTranslators translators = ModelTranslatorFactory.forUse();
        while (edgeIterator.hasNext()) {
            Extends edge = translators.generalizationTranslator()
                .translate(edgeIterator.next());
            repo.createExtendsEdge(edge);
        }
    }

    public void syncStructuralDetails(MModel model) {
        for (MClass cls : model.classes()) {
            mapAttributes(cls, model.name());
            mapOperations(cls, model.name());
            mapInvariants(cls, model.name());
            mapOperationConstraintsForAllOperationsInClass(cls, model.name());
        }
    }

    public void syncAssociations(MModel model) {
      UseModelTranslators translators = ModelTranslatorFactory.forUse();
        for (MAssociation assoc : model.associations()) {
            if (assoc.associationEnds().size() > 2) {
              NodeTernaryAssociation ternary = translators.ternaryAssociationTranslator()
                  .translate(new UseTernaryAssociationTranslator.TernaryAssociationContext(assoc, model.name()));
              repo.upsertTernaryAssociation(ternary);
            }
            else if (assoc instanceof MAssociationClass ac) {
              NodeAssociationClass node = translators.associationClassTranslator()
                  .translate(new UseAssociationClassTranslator.AssociationClassContext(ac, model.name()));
              repo.createAssociationClassStructure(node);
            } else {
              NodeBinaryAssociation edge = translators.binaryAssociationTranslator()
                  .translate(assoc);
              repo.createAssociationEdge(edge);
            }
        }
    }



    public void updateModelVersion(org.neo4j.driver.Session session, String user) {
        org.tzi.use.uml.mm.MModel model = modelApi.getModel();

        String modelName = model.name();

        long modelHash = ObjectSyncHelper.calculateModelHash(model);
        long ts = System.currentTimeMillis();

        // ModelHash này sẽ được SyncObjectCoordinator dùng để so sánh trước khi Push Object
        String cypher = "MERGE (v:ModelVersion {id: 'CURRENT'}) " +
                "SET v.timestamp = timestamp(), v.user = $user, " +
                "    v.modelHash = $hash, v.modelName = $name";
        session.run(cypher, Values.parameters("user", user, "hash", modelHash, "name", modelName));

        // 5. Cập nhật tracker local để máy mình không tự báo "Database Updated"
        org.uet.dse.neo4j.sync.ChangeTracker.updateLocalTimestamp(ts);

        logger.log("VERSION_UPDATE", "Model version updated. Hash: " + modelHash);
    }

    public void mapOperations(MClass cls, String modelName) {
        UseModelTranslators translators = ModelTranslatorFactory.forUse();

        for (MOperation op : cls.operations()) {
            if (!op.cls().equals(cls)) continue;

            // Translate operation
            NodeOperation opNode = translators.operationTranslator()
                .translate(new UseOperationTranslator.OperationContext(cls, op));

            repo.createInstanceNode(opNode, "Operation", modelName);
            repo.createStructuralEdge(cls.name(), opNode.getName(), "HasOperation");
            TypeMapper.handleTypeReference(repo, opNode.getName(), op.resultType(), true);

            // Translate params
            int order = 1;
            for (VarDecl param : op.paramList()) {
                NodeParam paramNode = translators.paramTranslator()
                    .translate(new UseParamTranslator.ParamContext(param, opNode.getName(), order++));

                repo.createInstanceNode(paramNode, "Param", modelName);
                repo.createStructuralEdge(opNode.getName(), paramNode.getName(), "HasParam");
                TypeMapper.handleTypeReference(repo, paramNode.getName(), param.type(), false);
            }

            mapOperationConstraintsForOneOperation(op, opNode.getName(), modelName);
        }
    }

    public void mapAttributes(MClass cls, String modelName) {
        UseModelTranslators translators = ModelTranslatorFactory.forUse();

        List<MAttribute> attrs = new ArrayList<>(cls.attributes());
        for (int i = 0; i < attrs.size(); i++) {
            UseAttributeTranslator.AttributeContext ctx = new UseAttributeTranslator.AttributeContext(cls, attrs.get(i), i);
            NodeAttribute nodeAttr = translators.attributeTranslator().translate(ctx);

            repo.createInstanceNode(nodeAttr, "Attribute", modelName);
            repo.createStructuralEdge(cls.name(), nodeAttr.getName(), "HasAttribute");

            // Side effects sau translate — giữ ở service vì liên quan repo
            Type ultimateBaseType = UmlTypeTranslator.getUltimateBaseType(attrs.get(i).type());
            TypeMapper.handleTypeReference(repo, nodeAttr.getName(), ultimateBaseType, false);

            if (nodeAttr.isNestedCollection()) {
                Type elemType = ((CollectionType) attrs.get(i).type()).elemType();
                pushNestedCollectionNodes(nodeAttr.getName(), elemType);
            }
        }
    }

    /**
     * refactored
     * create node nested collection
     */
    public void pushNestedCollectionNodes(String parentId, Type currentType) {
        if (!currentType.isKindOfCollection(Type.VoidHandling.EXCLUDE_VOID)) return;
        Type innerType = ((CollectionType) currentType).elemType();
        boolean stillNested = innerType.isKindOfCollection(Type.VoidHandling.EXCLUDE_VOID);
        String uuidName = "nested_" + java.util.UUID.randomUUID().toString();

        NodeNestedCollection nestedNode = new NodeNestedCollection(uuidName);
        nestedNode.setName(uuidName);
        nestedNode.setCollectionName(UmlTypeTranslator.getCollectionType(currentType));
        nestedNode.setNestedCollection(stillNested);

        repo.createInstanceNode(nestedNode, "NestedCollection", modelApi.getModel().name());

        repo.createStructuralEdge(parentId, nestedNode.getName(), "HasNestedCollection");

        if (stillNested) {
            pushNestedCollectionNodes(nestedNode.getName(), innerType);
        }
    }


    public void mapInvariants(MClass cls, String modelName) {
        UseModelTranslators translators = ModelTranslatorFactory.forUse();

        int index = 0;
        for (MClassInvariant inv : modelApi.getModel().classInvariants(cls)) {
            NodeClassInvariant invNode = translators.invariantTranslator()
                .translate(new UseClassInvariantTranslator.InvariantContext(inv, cls.name(), index++));

            repo.createInstanceNode(invNode, "ClassInvariant", modelName);
            repo.createStructuralEdge(cls.name(), invNode.getName(), "HasInvariant");
        }
    }


    public void mapOperationConstraintsForOneOperation(MOperation op, String opId, String modelName) {
        UseModelTranslators translators = ModelTranslatorFactory.forUse();

        int preIdx = 0;
        for (MPrePostCondition pre : op.preConditions()) {
            NodePreCondition preNode = translators.preConditionTranslator()
                .translate(new UsePreConditionTranslator.PreConditionContext(pre, opId, preIdx++));

            repo.createInstanceNode(preNode, "PreCondition", modelName);
            repo.createStructuralEdge(opId, preNode.getName(), "HasPreCondition");
        }

        int postIdx = 0;
        for (MPrePostCondition post : op.postConditions()) {
            NodePostCondition postNode = translators.postConditionTranslator()
                .translate(new UsePostConditionTranslator.PostConditionContext(post, opId, postIdx++));

            repo.createInstanceNode(postNode, "PostCondition", modelName);
            repo.createStructuralEdge(opId, postNode.getName(), "HasPostCondition");
        }
    }
    /**
     * checked
     */
    public void mapOperationConstraintsForAllOperationsInClass(MClass cls, String modelName) {
        for (MOperation op : cls.operations()) {
            if (op.cls().equals(cls)) {
                String opId = cls.name() + "_" + op.name();
                mapOperationConstraintsForOneOperation(op, opId, modelName);
            }
        }
    }
}
