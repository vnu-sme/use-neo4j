package org.uet.dse.neo4j.phase2;

import org.tzi.use.uml.ocl.type.CollectionType;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.type.Type.VoidHandling;
import org.uet.dse.neo4j.repo.Neo4jRepository;

import java.util.HashMap;
import java.util.Map;

public class TypeMapper {

    // Các hằng số tương ứng với "Sys VarType" và "Sys ReturnType" trong đặc tả của bạn
    public static final String VAR_INT = "Int";
    public static final String VAR_STRING = "String";
    public static final String VAR_BOOLEAN = "Boolean";
    public static final String VAR_DOUBLE = "Double";
    public static final String VAR_OBJECT = "Object";
    public static final String VAR_VOID = "Void";

    /**
     * Ánh xạ Type của USE sang chuỗi đại diện VarType/ReturnType trong Neo4j.
     * Theo đặc tả: Int, Float, Double, String, Object.
     */
    public static String getVarTypeString(Type useType) {
        if (useType == null) return VAR_VOID;

        // Bóc tách kiểu cơ sở (nếu là Collection như Set(Person) thì lấy Person)
        Type baseType = getBaseType(useType);

        if (baseType.isTypeOfInteger()) return VAR_INT;
        if (baseType.isTypeOfString()) return VAR_STRING;
        if (baseType.isTypeOfBoolean()) return VAR_BOOLEAN;
        if (baseType.isTypeOfReal()) return VAR_DOUBLE;

        // Nếu kiểu cơ sở là Class hoặc Enum thì trả về "Object" theo đặc tả
        if (baseType.isKindOfClass(VoidHandling.EXCLUDE_VOID) ||
                baseType.isKindOfEnum(VoidHandling.EXCLUDE_VOID)) {
            return VAR_OBJECT;
        }

        return VAR_OBJECT;
    }

    /**
     * Xử lý tạo quan hệ Reference (Đặc tả cạnh 16 và 17).
     * @param repo Repository thực thi Cypher
     * @param sourceNodeName Tên của Attribute hoặc Operation node
     * @param useType Kiểu dữ liệu cần kiểm tra reference
     * @param isReturnType true nếu là cạnh ReferenceReturnType (17), false nếu là ReferenceType (16)
     */
    public static void handleTypeReference(Neo4jRepository repo, String sourceNodeName, Type useType, boolean isReturnType) {
        if (useType == null) return;

        // Phân tích để lấy thông tin kiểu cơ sở (getBaseType)
        Map<String, Object> tInfo = parseType(useType);

        // Nếu tInfo có chứa 'ref', nghĩa là kiểu này tham chiếu đến một Class hoặc Enum khác
        if (tInfo.containsKey("ref") && tInfo.get("ref") != null) {
            String targetClassName = (String) tInfo.get("ref");

            // Nhãn quan hệ theo đặc tả 16 (ReferenceType) và 17 (ReferenceReturnType)
            String edgeLabel = isReturnType ? "ReferenceReturnType" : "ReferenceType";

            // Thực hiện tạo Edge trên Neo4j
            // Lưu ý: sourceNodeName ở đây là ID của Attribute hoặc Operation
            // targetClassName là tên của Class/Enum đích
            repo.createStructuralEdge(sourceNodeName, targetClassName, edgeLabel);
        }
    }

    /**
     * Lấy kiểu cơ sở (Unwrap collection).
     * Ví dụ: Set(Integer) -> Integer, Person -> Person.
     */
    public static Type getBaseType(Type type) {
        if (type.isKindOfCollection(VoidHandling.EXCLUDE_VOID) && type instanceof CollectionType) {
            return ((CollectionType) type).elemType();
        }
        return type;
    }

    /**
     * Xác định Sys Enum CollectionType (List, Set).
     */
    public static String getCollectionTypeString(Type type) {
        if (!type.isKindOfCollection(VoidHandling.EXCLUDE_VOID)) {
            return "None"; // Hoặc null tùy logic của bạn
        }

        // Theo đặc tả Sys Enum CollectionType của bạn: List, Set
        if (type.isKindOfSet(VoidHandling.EXCLUDE_VOID)) return "Set";
        if (type.isKindOfOrderedSet(VoidHandling.EXCLUDE_VOID)) return "Set";
        if (type.isKindOfSequence(VoidHandling.EXCLUDE_VOID)) return "List";
        if (type.isKindOfBag(VoidHandling.EXCLUDE_VOID)) return "List";

        return "List";
    }

    /**
     * Hàm quan trọng nhất: Phân tích Type của USE thành Map thuộc tính cho Neo4j
     * @param type Đối tượng Type từ USE Java API
     * @return Map chứa các key: type, isCollection, collType, ref
     */
    public static Map<String, Object> parseType(Type type) {
        Map<String, Object> result = new HashMap<>();

        // Trường hợp Type rỗng (Void)
        if (type == null) {
            result.put("type", "Void");
            result.put("isCollection", false);
            result.put("collType", "None");
            return result;
        }

        // 1. Kiểm tra tính tập hợp (Collection)
        // Sử dụng VoidHandling.EXCLUDE_VOID từ interface Type bạn gửi
        boolean isColl = type.isKindOfCollection(VoidHandling.EXCLUDE_VOID);
        result.put("isCollection", isColl);

        Type baseType = type;
        if (isColl && type instanceof CollectionType) {
            // Lấy kiểu phần tử bên trong (ví dụ Set(Person) -> Person)
            baseType = ((CollectionType) type).elemType();

            // Ánh xạ vào Sys Enum CollectionType: List, Set
            if (type.isKindOfSet(VoidHandling.EXCLUDE_VOID) ||
                    type.isKindOfOrderedSet(VoidHandling.EXCLUDE_VOID)) {
                result.put("collType", "Set");
            } else {
                result.put("collType", "List"); // Bao gồm Sequence và Bag
            }
        } else {
            result.put("collType", "None");
        }

        // 2. Ánh xạ vào Sys VarType: Int, Float, Double, String, Object
        if (baseType.isTypeOfInteger()) {
            result.put("type", "Int");
        } else if (baseType.isTypeOfReal()) {
            result.put("type", "Double");
        } else if (baseType.isTypeOfString()) {
            result.put("type", "String");
        } else if (baseType.isTypeOfBoolean()) {
            result.put("type", "Boolean");
        } else if (baseType.isKindOfClass(VoidHandling.EXCLUDE_VOID) ||
                baseType.isKindOfEnum(VoidHandling.EXCLUDE_VOID)) {
            // Nếu là Class hoặc Enum thì đánh nhãn là Object
            result.put("type", "Object");
            // Lưu lại tên Class/Enum để Bridge tạo cạnh ReferenceType sau đó
            result.put("ref", baseType.shortName());
        } else {
            result.put("type", "Object");
            result.put("ref", baseType.shortName());
        }

        return result;
    }

}