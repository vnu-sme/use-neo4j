package org.uet.dse.neo4j.sync;

import java.util.*;

public class ModelDiff {
    public List<String> getJavaOnlyClasses() {
        return javaOnlyClasses;
    }

    public void setJavaOnlyClasses(List<String> javaOnlyClasses) {
        this.javaOnlyClasses = javaOnlyClasses;
    }

    public List<String> getNeo4jOnlyClasses() {
        return neo4jOnlyClasses;
    }

    public void setNeo4jOnlyClasses(List<String> neo4jOnlyClasses) {
        this.neo4jOnlyClasses = neo4jOnlyClasses;
    }

    public List<String> getMismatchedClasses() {
        return mismatchedClasses;
    }

    public void setMismatchedClasses(List<String> mismatchedClasses) {
        this.mismatchedClasses = mismatchedClasses;
    }

    public Map<String, List<String>> getJavaOnlyAttributes() {
        return javaOnlyAttributes;
    }

    public void setJavaOnlyAttributes(Map<String, List<String>> javaOnlyAttributes) {
        this.javaOnlyAttributes = javaOnlyAttributes;
    }

    public Map<String, List<String>> getNeo4jOnlyAttributes() {
        return neo4jOnlyAttributes;
    }

    public void setNeo4jOnlyAttributes(Map<String, List<String>> neo4jOnlyAttributes) {
        this.neo4jOnlyAttributes = neo4jOnlyAttributes;
    }

    // Những thứ chỉ có ở Java (USE)
    public List<String> javaOnlyClasses = new ArrayList<>();
    // Những thứ chỉ có ở Neo4j (DB)
    public List<String> neo4jOnlyClasses = new ArrayList<>();
    // Những thứ có cả 2 nhưng khác nhau về thuộc tính/phương thức
    public List<String> mismatchedClasses = new ArrayList<>();

    public Map<String, List<String>> javaOnlyAttributes = new HashMap<>();
    public Map<String, List<String>> neo4jOnlyAttributes = new HashMap<>();

    public boolean hasDifference() {
        return !javaOnlyClasses.isEmpty() || !neo4jOnlyClasses.isEmpty() || !mismatchedClasses.isEmpty();
    }

    /**
     * Báo cáo cho chiều XUÔI (Java -> DB)
     */
    public String generateForwardReport() {
        StringBuilder sb = new StringBuilder("--- SYNC FORWARD (PUSH TO DB) ---\n");
        if (!javaOnlyClasses.isEmpty()) sb.append("[+] WILL ADD TO DB: ").append(javaOnlyClasses).append("\n");
        if (!neo4jOnlyClasses.isEmpty()) sb.append("[!] WILL DELETE FROM DB (NOT IN USE): ").append(neo4jOnlyClasses).append("\n");
        if (!mismatchedClasses.isEmpty()) sb.append("[*] WILL UPDATE SCHEMA IN DB: ").append(mismatchedClasses).append("\n");
        return sb.toString();
    }

    /**
     * Báo cáo cho chiều NGƯỢC (DB -> Java)
     */
    public String generateBackwardReport() {
        StringBuilder sb = new StringBuilder("--- SYNC BACKWARD (PULL FROM DB) ---\n");
        if (!neo4jOnlyClasses.isEmpty()) sb.append("[+] NEW FROM DB (WILL ADD TO USE): ").append(neo4jOnlyClasses).append("\n");
        if (!javaOnlyClasses.isEmpty()) sb.append("[-] NOT IN DB (WILL REMOVE FROM USE): ").append(javaOnlyClasses).append("\n");
        if (!mismatchedClasses.isEmpty()) sb.append("[*] MISMATCH (WILL UPDATE USE TO MATCH DB): ").append(mismatchedClasses).append("\n");
        return sb.toString();
    }
}