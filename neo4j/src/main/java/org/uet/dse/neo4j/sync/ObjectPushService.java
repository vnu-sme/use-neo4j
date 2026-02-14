package org.uet.dse.neo4j.sync;

import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.tzi.use.uml.mm.MAssociationEnd;
import org.tzi.use.uml.mm.MAttribute;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.sys.*;
import org.uet.dse.neo4j.helper.ValueMapper;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.phase2.WorkLogManager;
import org.uet.dse.neo4j.repo.Neo4jObjectRepository;
import org.uet.dse.neo4j.repo.Neo4jSideRepository;

import java.util.*;
import java.util.stream.Stream;

public class ObjectPushService {
    private final Neo4jObjectRepository objectRepository;
    private final MSystem system;

    public ObjectPushService(Neo4jObjectRepository objectRepository, MSystem system) {
        this.objectRepository = objectRepository;
        this.system = system;
    }

    public void executePush(ObjectDiff diff) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            session.executeWrite(tx -> {
                deleteStaleObjects(tx, diff.neo4jOnlyObjects);
                deleteStaleLinks(tx, diff.neo4jOnlyLinks);
                pushModifiedObjects(tx, diff);
                pushAllLinks(tx);
                return null;
            });

            Neo4jSideRepository.updateObjectVersionOnServer();
            WorkLogManager.getInstance().log("SYNC", "Object & Link sync completed.");
        }
    }

    private void deleteStaleObjects(TransactionContext tx, Collection<String> objectNames) {
        for (String name : objectNames) {
            objectRepository.deleteObjectDeeply(tx, name);
            WorkLogManager.getInstance().log("CLEANUP", "Deep deleted object: " + name);
        }
    }

    private void deleteStaleLinks(TransactionContext tx, Collection<String> linkNames) {
        for (String name : linkNames) {
            tx.run("MATCH ()-[r {name: $name}]-() WHERE type(r) STARTS WITH 'Link' DELETE r",
                    Map.of("name", name));
            tx.run("MATCH (h:LinkHub {name: $name}) DETACH DELETE h",
                    Map.of("name", name));
        }
    }
    private void pushModifiedObjects(TransactionContext tx, ObjectDiff diff) {
        MSystemState state = system.state();

        Stream.concat(diff.javaOnlyObjects.stream(), diff.mismatchedObjects.stream())
                .map(state::objectByName)
                .forEach(obj -> pushValidatedObject(tx, obj, state));
    }

    private void pushValidatedObject(TransactionContext tx, MObject obj, MSystemState state) {
        String className = obj.cls().name();
        if (!objectRepository.checkClassExistsInDb(tx, className)) {
            throw new RuntimeException("Class '" + className + "' missing in DB. Push Model first.");
        }
        pushObject(tx, obj, state);
    }

    private void pushAllLinks(TransactionContext tx) {
        system.state().allLinks().forEach(link -> pushLink(tx, link));
    }

    private void pushLink(TransactionContext tx, MLink link) {
        if (link instanceof MLinkObject) {
            processPushLinkObject(tx, (MLinkObject) link);
        } else if (link.linkedObjects().size() > 2) {
            processPushTernaryLink(tx, link);
        } else {
            processPushBinaryLink(tx, link);
        }
    }

    private void processPushBinaryLink(TransactionContext tx, MLink link) {
        MObject s = link.linkedObjects().get(0);
        MObject t = link.linkedObjects().get(1);
        MAssociationEnd endS = link.association().associationEnds().get(0);
        MAssociationEnd endT = link.association().associationEnds().get(1);

        // Xác định nhãn dựa trên aggregationKind (0:None, 1:Agg, 2:Comp)
        String label = "LinkAssociateWith";
        if (endT.aggregationKind() == 2 || endS.aggregationKind() == 2) label = "LinkComposeOf";
        else if (endT.aggregationKind() == 1 || endS.aggregationKind() == 1) label = "LinkAggregates";

        objectRepository.upsertBinaryLink(tx, s.name(), t.name(), link.association().name(),
                label, endS.name(), endT.name());
    }

    private void processPushTernaryLink(TransactionContext tx, MLink link) {
        List<Map<String, Object>> participants = new ArrayList<>();
        for (int i = 0; i < link.linkedObjects().size(); i++) {
            MObject obj = link.linkedObjects().get(i);
            MAssociationEnd end = link.association().associationEnds().get(i);

            Map<String, Object> p = new HashMap<>();
            p.put("objName", obj.name());
            p.put("role", end.name());
            p.put("index", i);
            p.put("label", "LinkAssociateWith"); // Ternary thường là Association
            participants.add(p);
        }
        objectRepository.upsertTernaryLink(tx, link.association().name(), participants);
    }

    private void processPushLinkObject(TransactionContext tx, MLinkObject lo) {
        List<Map<String, Object>> participants = new ArrayList<>();
        for (int i = 0; i < lo.linkedObjects().size(); i++) {
            Map<String, Object> p = new HashMap<>();
            p.put("objName", lo.linkedObjects().get(i).name());
            p.put("role", lo.association().associationEnds().get(i).name());
            p.put("label", "LinkAssociateWith");
            participants.add(p);
        }
        // Vừa tạo Node cho LinkObject, vừa nối cạnh tới các participant
        objectRepository.upsertLinkObject(tx, lo.name(), lo.cls().name(), participants);
    }

    public void pushObject(org.neo4j.driver.TransactionContext tx, MObject obj, MSystemState state) {
        String objName = obj.name();
        String clsName = obj.cls().name();
        objectRepository.upsertObjectNode(tx, objName, clsName);

        Map<MAttribute, Value> attrValues = obj.state(state).attributeValueMap();
        for (Map.Entry<MAttribute, Value> entry : attrValues.entrySet()) {
            MAttribute attr = entry.getKey();
            Value useVal = entry.getValue();

            if (useVal.isUndefined()) {
                objectRepository.removeAttributeValueNode(tx, objName, attr.name());
                continue;
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", UmlTypeTranslator.toDatabaseType(attr.type()));
            metadata.put("isCollection", attr.type().isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID));
            metadata.put("collectionType", UmlTypeTranslator.getCollectionType(attr.type()));

            Object mappedValue = ValueMapper.mapUseValue(useVal);
            objectRepository.setAttributeValueNode(tx, objName, clsName, attr.name(), mappedValue, metadata);
        }
    }
}
