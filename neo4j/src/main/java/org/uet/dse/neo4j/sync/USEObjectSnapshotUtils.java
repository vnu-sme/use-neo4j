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

        // 1. Quét Objects
        for (MObject obj : state.allObjects()) {
            snapshot.objects.put(obj.name(), buildObjectState(obj, state));
        }

        // 2. Quét Links
        for (MLink link : state.allLinks()) {
            LinkState ls = buildLinkState(link);
            snapshot.links.put(ls.getIdentity(), ls);
        }

        return snapshot;
    }

    private ObjectState buildObjectState(MObject obj, MSystemState state) {
        ObjectState os = new ObjectState();
        os.name = obj.name();
        os.className = obj.cls().name();

        for (MAttribute attr : obj.cls().allAttributes()) {
            Value useVal = obj.state(state).attributeValue(attr);

            String dbType = UmlTypeTranslator.toDatabaseType(attr.type());
            os.attributeTypes.put(attr.name(), dbType);

            Object mapped = ValueMapper.mapUseValue(useVal);

            if (isObjectReferenceType(attr.type())) {
                if (mapped instanceof List) os.objectReferences.put(attr.name(), (List<String>) mapped);
                else if (mapped != null) os.objectReferences.put(attr.name(), Collections.singletonList(mapped.toString()));
            } else {
                os.primitiveValues.put(attr.name(), mapped);
            }
        }
        return os;
    }

    private boolean isObjectReferenceType(org.tzi.use.uml.ocl.type.Type type) {
        if (type.isKindOfClass(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) return true;
        if (type.isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
            return ((org.tzi.use.uml.ocl.type.CollectionType) type).elemType().isKindOfClass(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID);
        }
        return false;
    }

    private LinkState buildLinkState(MLink link) {
        LinkState ls = new LinkState();
        ls.assocName = link.association().name();
        link.linkedObjects().forEach(o -> ls.participants.add(o.name()));
        if (link instanceof MLinkObject) ls.linkObjectName = ((MLinkObject) link).name();
        return ls;
    }

    private void collectObjects(MSystemState state, FullObjectSnapshot snapshot) {
        for (MObject obj : state.allObjects()) {
            snapshot.objects.put(obj.name(), buildObjectState(obj, state));
        }
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


    private void collectLinks(MSystemState state, FullObjectSnapshot snapshot) {
        for (MLink link : state.allLinks()) {
            LinkState ls = buildLinkState(link);
            snapshot.links.put(ls.getIdentity(), ls);
        }
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
