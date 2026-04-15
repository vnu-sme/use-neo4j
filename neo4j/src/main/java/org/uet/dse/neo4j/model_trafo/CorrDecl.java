package org.uet.dse.neo4j.model_trafo;

public class CorrDecl implements TGGElement {
    public String nodeARef;   // Ví dụ: fr hoặc fm*
    public String nodeBRef;   // Ví dụ: pr hoặc mp
    public String aliasA;     // Ví dụ: f
    public String aliasB;     // Ví dụ: mp
    public String corrName;   // Ví dụ: f2mp
    public String corrType;   // Ví dụ: F2MP

    // Hai trường này dùng để lưu kết quả phân tích sau này (Resolve Type)
    public String sourceClassType;
    public String targetClassType;
}