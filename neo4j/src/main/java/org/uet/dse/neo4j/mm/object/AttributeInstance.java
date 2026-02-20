package org.uet.dse.neo4j.mm.object;

import java.util.List;

public final class AttributeInstance {

    private final String attributeName;
    private final AttributeType type;
    private final boolean collection;
    private final boolean nestedCollection;
    private final String collectionType;
    private final List<NestedNode> nestedStructure;

    public AttributeInstance(
            String attributeName,
            AttributeType type,
            boolean collection,
            boolean nestedCollection,
            String collectionType,
            List<NestedNode> nestedStructure
    ) {
        this.attributeName = attributeName;
        this.type = type;
        this.collection = collection;
        this.nestedCollection = nestedCollection;
        this.collectionType = collectionType;
        this.nestedStructure = nestedStructure;
    }

    public String getAttributeName() {
        return attributeName;
    }

    public AttributeType getType() {
        return type;
    }

    public boolean isCollection() {
        return collection;
    }

    public boolean isNestedCollection() {
        return nestedCollection;
    }

    public String getCollectionType() {
        return collectionType;
    }

    public List<NestedNode> getNestedStructure() {
        return nestedStructure;
    }
}
