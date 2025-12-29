package org.uet.dse.neo4j.manager;

import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.tzi.use.api.UseSystemApi;
import org.tzi.use.uml.mm.*;
import org.tzi.use.uml.ocl.type.EnumType;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.type.TypeFactory;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.sys.MSystem;
import org.tzi.use.util.StringUtil;

import javax.swing.*;
import java.util.*;

public class Neo4jImporter {

    private final Session neoSession;
    private final JTextArea logArea;
    private MModel constructedModel;
    private org.tzi.use.main.Session useSession;

    public Neo4jImporter(Session neoSession, org.tzi.use.main.Session useSession, JTextArea logArea) {
        this.neoSession = neoSession;
        this.useSession = useSession;
        this.logArea = logArea;
    }

    // ========================================================================
    // PHẦN 1: IMPORT METAMODEL (Giữ nguyên logic cũ, chỉ thêm try-catch an toàn)
    // ========================================================================

    public MModel importMetamodel() {
        log("--- [Start] Import Metamodel ---");
        ModelFactory factory = new ModelFactory();
        constructedModel = factory.createModel("ImportedFromNeo4j");

        // 1. Tạo Classes
        neoSession.executeRead(tx -> {
            log("Reading MetaClasses...");
            Result res = tx.run("MATCH (c:MetaClass) RETURN c.name as name, labels(c) as labels");
            while (res.hasNext()) {
                Record record = res.next();
                String className = record.get("name").asString();
                List<String> labels = record.get("labels").asList(o -> o.toString());
                boolean isAbstract = labels.contains("Abstract");
                boolean isAssocClass = labels.contains("AssociationClass");

                try {
                    if (isAssocClass) {
                        constructedModel.addClass(factory.createAssociationClass(className, isAbstract));
                    } else {
                        constructedModel.addClass(factory.createClass(className, isAbstract));
                    }
                } catch (Exception e) {
                    log("ERR: Không thể tạo class " + className + ": " + e.getMessage());
                }
            }
            return null;
        });

        // 2. Tạo Attributes
        neoSession.executeRead(tx -> {
            log("Reading Attributes...");
            Result res = tx.run("MATCH (c:MetaClass)-[:HAS_ATTRIBUTE]->(a:MetaAttribute) " +
                    "RETURN c.name as className, a.name as attrName, a.type as type");
            while (res.hasNext()) {
                Record r = res.next();
                String className = r.get("className").asString();
                String attrName = r.get("attrName").asString();
                String typeStr = r.get("type").asString();

                MClass cls = constructedModel.getClass(className);
                if (cls != null) {
                    try {
                        cls.addAttribute(factory.createAttribute(attrName, parseType(typeStr)));
                    } catch (Exception e) {
                        log("ERR: Lỗi thêm attribute " + attrName + " vào " + className);
                    }
                }
            }
            return null;
        });

        // 3. Tạo Kế thừa
        neoSession.executeRead(tx -> {
            log("Reading Inheritance...");
            Result res = tx.run("MATCH (child:MetaClass)-[:INHERITS_FROM]->(parent:MetaClass) " +
                    "RETURN child.name as child, parent.name as parent");
            while (res.hasNext()) {
                Record r = res.next();
                MClass child = constructedModel.getClass(r.get("child").asString());
                MClass parent = constructedModel.getClass(r.get("parent").asString());
                if (child != null && parent != null) {
                    try {
                        constructedModel.addGeneralization(factory.createGeneralization(child, parent));
                    } catch (Exception e) { }
                }
            }
            return null;
        });

        // 4. Tạo Association
        neoSession.executeRead(tx -> {
            log("Reading Associations...");
            String query = "MATCH (c1:MetaClass)-[r:META_ASSOCIATION]->(c2:MetaClass) " +
                    "RETURN c1.name as cls1, c2.name as cls2, properties(r) as props";
            Result res = tx.run(query);
            while (res.hasNext()) {
                Record r = res.next();
                Map<String, Object> props = r.get("props").asMap();
                String assocName = (String) props.get("name");

                try {
                    MClass c1 = constructedModel.getClass(r.get("cls1").asString());
                    MClass c2 = constructedModel.getClass(r.get("cls2").asString());

                    MAssociationEnd end1 = factory.createAssociationEnd(
                            c1, (String) props.get("role1"),
                            parseMultiplicity((String) props.get("mult1")),
                            parseAggregation((String) props.get("kind1")), false, new ArrayList<>()
                    );

                    MAssociationEnd end2 = factory.createAssociationEnd(
                            c2, (String) props.get("role2"),
                            parseMultiplicity((String) props.get("mult2")),
                            parseAggregation((String) props.get("kind2")), false, new ArrayList<>()
                    );

                    MAssociation assoc = factory.createAssociation(assocName);
                    assoc.addAssociationEnd(end1);
                    assoc.addAssociationEnd(end2);
                    constructedModel.addAssociation(assoc);
                } catch (Exception e) {
                    log("ERR: Lỗi tạo Association " + assocName + ": " + e.getMessage());
                }
            }
            return null;
        });

        log("--- [End] Metamodel Import ---");
        return constructedModel;
    }

    // ========================================================================
    // PHẦN 2: IMPORT DATA (Đã sửa lỗi tạo Object)
    // ========================================================================

    // ... các imports giữ nguyên ...

    public void importData() {
        if (useSession.system() == null) {
            log("ERROR: Chưa load Model. Vui lòng Import Metamodel trước.");
            return;
        }

        log("--- [Start] Import Data (3 Phases) ---");
        UseSystemApi api = UseSystemApi.create(useSession);
        MModel model = useSession.system().model();

        // ========================================================================
        // PHASE 1: TẠO OBJECT THƯỜNG (Standard Classes)
        // Sử dụng api.createObject() như bạn yêu cầu
        // ========================================================================
        neoSession.executeRead(tx -> {
            log("Phase 1: Importing Standard Objects...");

            // Lọc: Chỉ lấy các Node KHÔNG phải là AssociationClass và KHÔNG phải MetaClass
            String query = "MATCH (n) " +
                    "WHERE n.use_id IS NOT NULL " +
                    "AND NOT 'MetaClass' IN labels(n) " +
                    "AND NOT 'AssociationClass' IN labels(n) " +
                    "RETURN n.use_id as id, labels(n) as labels, properties(n) as props";

            Result res = tx.run(query);
            int count = 0;

            while (res.hasNext()) {
                Record r = res.next();
                String objId = r.get("id").asString();
                List<String> labels = r.get("labels").asList(o -> o.toString());
                Map<String, Object> props = r.get("props").asMap();

                // Tìm Class trong Model
                MClass cls = findConcreteClass(model, labels);

                if (cls != null && !(cls instanceof MAssociationClass)) { // Check kỹ lại
                    try {
                        // --- TẬN DỤNG CODE CỦA BẠN Ở ĐÂY ---
                        api.createObject(cls.name(), objId);
                        count++;

                        // Set Attributes
                        setAttributes(api, cls, objId, props);

                    } catch (Exception e) {
                        log("ERR Phase 1: Lỗi tạo object '" + objId + "': " + e.getMessage());
                    }
                }
            }
            log("-> Đã tạo " + count + " standard objects.");
            return null;
        });

        // ========================================================================
        // PHASE 2: TẠO ASSOCIATION CLASS INSTANCES (Link Objects)
        // Dùng api.createLinkObject() - Vì createObject() sẽ lỗi với loại này
        // ========================================================================
        neoSession.executeRead(tx -> {
            log("Phase 2: Importing Association Class Objects...");

            // Lấy các Node là AssociationClass
            // Cần tìm thêm các Node mà nó kết nối tới để tạo LinkObject hợp lệ
            String query = "MATCH (center:AssociationClass) " +
                    "WHERE center.use_id IS NOT NULL " +
                    "RETURN center";

            Result res = tx.run(query);
            int count = 0;

            while (res.hasNext()) {
                org.neo4j.driver.types.Node node = res.next().get("center").asNode();
                String centerId = node.get("use_id").asString();
                List<String> labels = new ArrayList<>();
                node.labels().forEach(labels::add);
                Map<String, Object> props = node.asMap();

                MClass cls = findConcreteClass(model, labels);

                if (cls instanceof MAssociationClass) {
                    MAssociationClass assocClass = (MAssociationClass) cls;
                    try {
                        // Cần tìm các object tham gia (Participants) của LinkObject này
                        // Query ngược lại Neo4j để tìm các node nối với center
                        // Giả sử export theo mô hình: (center)-[:ROLE]->(part)
                        List<String> participants = new ArrayList<>();

                        // Duyệt qua các Association End để tìm đúng thứ tự
                        for (MAssociationEnd end : assocClass.associationEnds()) {
                            String roleName = end.nameAsRolename();

                            // Tìm node nối với center qua roleName
                            String findPart = "MATCH (center {use_id: $cid})-[:`" + roleName + "`]-(part) " +
                                    "RETURN part.use_id as pid LIMIT 1";

                            Result partRes = tx.run(findPart, Map.of("cid", centerId));
                            if (partRes.hasNext()) {
                                participants.add(partRes.next().get("pid").asString());
                            } else {
                                throw new RuntimeException("Thiếu thành phần kết nối cho Role: " + roleName);
                            }
                        }

                        // Gọi API tạo Link Object
                        // createLinkObject(String assocClassName, String objName, String[] connectedObjNames)
                        api.createLinkObject(assocClass.name(), centerId, participants.toArray(new String[0]));
                        count++;

                        // Set Attributes (Link Object cũng có thuộc tính)
                        setAttributes(api, assocClass, centerId, props);

                    } catch (Exception e) {
                        log("ERR Phase 2: Lỗi tạo LinkObject '" + centerId + "': " + e.getMessage());
                    }
                }
            }
            log("-> Đã tạo " + count + " association class objects.");
            return null;
        });

        // ========================================================================
        // PHASE 3: TẠO LINKS THƯỜNG (Binary / N-ary)
        // ========================================================================
        neoSession.executeRead(tx -> {
            log("Phase 3: Importing Standard Links...");
            // Chỉ lấy link giữa các node data, bỏ qua link cấu trúc (Meta, AssocClass connect)
            String query = "MATCH (a)-[r]->(b) " +
                    "WHERE a.use_id IS NOT NULL AND b.use_id IS NOT NULL " +
                    "AND NOT 'MetaClass' IN labels(a) " +
                    "AND NOT 'AssociationClass' IN labels(a) " + // Bỏ qua link từ AssocClass
                    "AND NOT type(r) STARTS WITH 'META_' " +
                    "RETURN type(r) as assocName, a.use_id as src, b.use_id as dest";

            Result res = tx.run(query);
            int count = 0;
            while (res.hasNext()) {
                Record r = res.next();
                String assocName = r.get("assocName").asString();
                String srcId = r.get("src").asString();
                String destId = r.get("dest").asString();

                try {
                    // Kiểm tra xem đây có phải Association thường không
                    if (model.getAssociation(assocName) != null && !(model.getAssociation(assocName) instanceof MAssociationClass)) {
                        api.createLink(assocName, new String[]{srcId, destId});
                        count++;
                    }
                } catch (Exception e) {
                    // Ignore lỗi duplicate hoặc sai chiều
                }
            }
            log("-> Đã tạo " + count + " standard links.");
            return null;
        });

        log("--- [End] Import Data Complete ---");
    }

    // --- Helper tách riêng để code gọn ---
    private void setAttributes(UseSystemApi api, MClass cls, String objId, Map<String, Object> props) {
        for (MAttribute attr : cls.allAttributes()) {
            if (props.containsKey(attr.name())) {
                Object rawVal = props.get(attr.name());
                if (rawVal == null) continue;

                String valStr = rawVal.toString();
                try {
                    // 1. Xử lý String
                    if (attr.type().equals(TypeFactory.mkString())) {
                        valStr = "'" + valStr.replace("'", "\\'") + "'";
                    }
                    // 2. Xử lý Enum (SỬA LẠI DÒNG NÀY)
                    else if (attr.type() instanceof EnumType) {
                        // USE OCL yêu cầu Enum phải có dấu # phía trước (Ví dụ: #Red)
                        if (!valStr.startsWith("#")) {
                            valStr = "#" + valStr;
                        }
                        // (Tuỳ chọn) Kiểm tra xem literal này có tồn tại trong EnumType không để tránh lỗi
                        // EnumType et = (EnumType) attr.type();
                        // if (!et.contains(valStr.substring(1))) { ... handle error ... }
                    }
                    api.setAttributeValue(objId, attr.name(), valStr);
                } catch (Exception e) {
                    // Log warning attribute
                }
            }
        }
    }

    // --- HELPER METHODS ---

    /**
     * Tìm class cụ thể (không phải abstract) trong model dựa trên danh sách label.
     */
    /**
     * Tìm Class cụ thể nhất (Leaf Class) từ danh sách Label.
     * Giải thuật: Lấy tất cả class khớp tên, loại bỏ Abstract,
     * sau đó chọn class nằm thấp nhất trong cây kế thừa.
     */
    private MClass findConcreteClass(MModel model, List<String> labels) {
        List<MClass> candidates = new ArrayList<>();

        // 1. Lọc tất cả các Class có tên khớp với Label
        for (String rawLabel : labels) {
            // --- SỬA LỖI TẠI ĐÂY ---
            // Loại bỏ dấu nháy kép thừa: ""Family"" -> "Family"
            String label = removeQuotes(rawLabel);
            // Bỏ qua các label hệ thống
            if (label.equals("MetaClass") || label.equals("AssociationClass") || label.startsWith("constraint_"))
                continue;

            MClass cls = model.getClass(label);
            if (cls != null) {
                candidates.add(cls);
            }
        }

        if (candidates.isEmpty()) {
            // Log debug để biết tại sao không tìm thấy
            // System.out.println("DEBUG: Không tìm thấy class nào trong model khớp với labels: " + labels);
            return null;
        }

        // 2. Tìm ứng viên tốt nhất (Class con cùng - Most Specific)
        MClass bestCandidate = null;

        for (MClass candidate : candidates) {
            // Chỉ quan tâm class cụ thể (trừ trường hợp AssociationClass thì có thể đặc biệt)
            if (candidate.isAbstract()) continue;

            if (bestCandidate == null) {
                bestCandidate = candidate;
            } else {
                // Nếu candidate là con của bestCandidate -> candidate cụ thể hơn -> chọn candidate
                if (candidate.isSubClassifierOf(bestCandidate)) {
                    bestCandidate = candidate;
                }
                // Ngược lại nếu bestCandidate là con của candidate -> giữ nguyên bestCandidate
            }
        }

        return bestCandidate;
    }

    /**
     * Loại bỏ dấu ngoặc kép bao quanh chuỗi nếu có.
     * Ví dụ: "Family" -> Family
     */
    private String removeQuotes(String str) {
        if (str == null) return null;
        String clean = str.trim();
        if (clean.startsWith("\"") && clean.endsWith("\"")) {
            return clean.substring(1, clean.length() - 1);
        }
        return clean;
    }

    private Type parseType(String typeStr) {
        if (typeStr == null) return TypeFactory.mkString();
        if (typeStr.endsWith("Integer")) return TypeFactory.mkInteger();
        if (typeStr.endsWith("String")) return TypeFactory.mkString();
        if (typeStr.endsWith("Boolean")) return TypeFactory.mkBoolean();
        if (typeStr.endsWith("Real")) return TypeFactory.mkReal();
        // Mặc định fallback
        return TypeFactory.mkString();
    }

    private MMultiplicity parseMultiplicity(String multStr) {
        if (multStr == null || multStr.isEmpty()) return new MMultiplicity(0, -1);
        try {
            if (multStr.contains("..")) {
                String[] parts = multStr.split("\\.\\.");
                int min = parts[0].equals("*") ? -1 : Integer.parseInt(parts[0]);
                int max = parts[1].equals("*") ? -1 : Integer.parseInt(parts[1]);
                return new MMultiplicity(min, max);
            } else if (multStr.equals("*")) {
                return new MMultiplicity(0, -1);
            } else {
                int val = Integer.parseInt(multStr);
                return new MMultiplicity(val, val);
            }
        } catch (NumberFormatException e) {
            return new MMultiplicity(0, -1);
        }
    }

    private int parseAggregation(String kind) {
        if ("composition".equalsIgnoreCase(kind)) return MAggregationKind.COMPOSITION;
        if ("shared".equalsIgnoreCase(kind)) return MAggregationKind.AGGREGATION;
        return MAggregationKind.NONE;
    }

    private void log(String msg) {
        System.out.println("[Neo4j Import] " + msg);
        if (logArea != null) {
            SwingUtilities.invokeLater(() -> logArea.append(msg + "\n"));
        }
    }
}