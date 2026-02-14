package org.uet.dse.neo4j.sync;

import org.tzi.use.api.UseSystemApi;
import org.tzi.use.uml.mm.MAssociation;
import org.tzi.use.uml.mm.MAssociationEnd;
import org.tzi.use.uml.mm.MAttribute;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.sys.*;
import org.uet.dse.neo4j.helper.ValueMapper;
import org.uet.dse.neo4j.model.FullObjectSnapshot;
import org.uet.dse.neo4j.model.LinkState;
import org.uet.dse.neo4j.model.ObjectState;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class USEObjectSnapshotUtils {
    private MSystem system;

    public USEObjectSnapshotUtils(MSystem system) {
        this.system = system;
    }
    public FullObjectSnapshot getJavaObjectSnapshot() {
        FullObjectSnapshot snapshot = new FullObjectSnapshot();
        MSystemState state = system.state();

        collectObjects(state, snapshot);
        collectLinks(state, snapshot);

        return snapshot;
    }

    private void collectObjects(MSystemState state, FullObjectSnapshot snapshot) {
        for (MObject obj : state.allObjects()) {
            snapshot.objects.put(obj.name(), buildObjectState(obj, state));
        }
    }

    private ObjectState buildObjectState(MObject obj, MSystemState state) {
        ObjectState os = new ObjectState();
        os.name      = obj.name();
        os.className = obj.cls().name();

        for (Map.Entry<MAttribute, Value> entry : obj.state(state).attributeValueMap().entrySet()) {
            MAttribute attr      = entry.getKey();
            Object     mapped    = ValueMapper.mapUseValue(entry.getValue());
            String     dbType    = UmlTypeTranslator.toDatabaseType(attr.type());

            os.attributeTypes.put(attr.name(), dbType);
            classifyAttributeValue(os, attr, mapped);
        }

        return os;
    }

    /**
     * Routes a mapped attribute value into either objectReferences or primitiveValues
     * depending on whether the attribute's UML type resolves to a class (or collection
     * of classes) at the M1 level.
     */
    private void classifyAttributeValue(ObjectState os, MAttribute attr, Object mapped) {
        if (isObjectReferenceType(attr.type())) {
            putObjectReference(os, attr.name(), mapped);
        } else {
            os.primitiveValues.put(attr.name(), mapped);
        }
    }

    private void putObjectReference(ObjectState os, String attrName, Object mapped) {
        if (mapped instanceof List) {
            os.objectReferences.put(attrName, (List<String>) mapped);
        } else if (mapped != null) {
            os.objectReferences.put(attrName, Collections.singletonList(mapped.toString()));
        }
    }

    /**
     * Returns true when a UML type is a class reference, or a collection whose
     * element type is a class reference — both map to object references at M0.
     */
    private boolean isObjectReferenceType(org.tzi.use.uml.ocl.type.Type type) {
        if (type.isKindOfClass(Type.VoidHandling.EXCLUDE_VOID)) {
            return true;
        }
        if (type.isKindOfCollection(Type.VoidHandling.EXCLUDE_VOID)) {
            org.tzi.use.uml.ocl.type.CollectionType coll =
                    (org.tzi.use.uml.ocl.type.CollectionType) type;
            return coll.elemType().isKindOfClass(Type.VoidHandling.EXCLUDE_VOID);
        }
        return false;
    }

    private void collectLinks(MSystemState state, FullObjectSnapshot snapshot) {
        for (MLink link : state.allLinks()) {
            LinkState ls = buildLinkState(link);
            snapshot.links.put(ls.getIdentity(), ls);
        }
    }

    private LinkState buildLinkState(MLink link) {
        LinkState ls = new LinkState();
        ls.assocName = link.association().name();
        link.linkedObjects().forEach(o -> ls.participants.add(o.name()));
        ls.edgeLabel = resolveLinkEdgeLabel(link);

        if (link instanceof MLinkObject) {
            ls.linkObjectName = ((MLinkObject) link).name();
        } else if (link.linkedObjects().size() > 2) {
            ls.isTernary = true;
        }

        return ls;
    }

    /**
     * Resolves the edge label for a link.
     * LinkObjects and ternary links always use the plain association label.
     * Binary links are promoted to Aggregates or ComposeOf when the association
     * ends carry an aggregation kind.
     */
    private String resolveLinkEdgeLabel(MLink link) {
        if (link instanceof MLinkObject || link.linkedObjects().size() > 2) {
            return "LinkAssociateWith";
        }
        return resolveBinaryEdgeLabel(link.association());
    }

    private String resolveBinaryEdgeLabel(MAssociation assoc) {
        int maxKind = assoc.associationEnds().stream()
                .mapToInt(MAssociationEnd::aggregationKind)
                .max()
                .orElse(0);

        if (maxKind == 2) return "LinkComposeOf";
        if (maxKind == 1) return "LinkAggregates";
        return "LinkAssociateWith";
    }
}
