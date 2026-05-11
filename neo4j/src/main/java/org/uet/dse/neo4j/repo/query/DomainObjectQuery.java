package org.uet.dse.neo4j.repo.query;

public final class DomainObjectQuery {

    private DomainObjectQuery() {}

    public static final String FIND_OBJECT_WITH_ATTRIBUTES =
            "MATCH (n {use_id: $id}) " +
            "OPTIONAL MATCH (n)-[:ObjectHasAttribute]->(m:AttributeValue) " +
            "RETURN n, m";

}
