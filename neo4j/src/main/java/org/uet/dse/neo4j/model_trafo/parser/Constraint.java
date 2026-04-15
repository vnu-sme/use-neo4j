package org.uet.dse.neo4j.model_trafo.parser;

public class Constraint implements TGGElement {
    public String label; // Optional
    public String expression; // content inside []
}