package org.uet.dse.neo4j.sync.helper;

import org.tzi.use.uml.ocl.type.CollectionType;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.type.Type.VoidHandling;
import java.util.List;
import java.util.Map;

public class UmlTypeTranslator {

    /**
     * ĐỊNH DẠNG GIÁ TRỊ (VALUE -> OCL): Chuyển giá trị thô sang biểu thức OCL hợp lệ
     */
    public static String toOclLiteral(Object value) {
        if (value == null || value.toString().equals("null")) return "Undefined";

        // Xử lý danh sách (Set{...})
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) return "Set{}";
            StringBuilder sb = new StringBuilder("Set{");
            for (int i = 0; i < list.size(); i++) {
                sb.append(toOclLiteral(list.get(i)));
                if (i < list.size() - 1) sb.append(", ");
            }
            return sb.append("}").toString();
        }

        // Xử lý số thực (Double -> Real)
        if (value instanceof Double || value instanceof Float) {
            String s = value.toString();
            return s.contains(".") ? s : s + ".0";
        }

        // Xử lý Boolean
        if (value instanceof Boolean) return value.toString().toLowerCase();

        // Xử lý Số nguyên
        if (value instanceof Integer || value instanceof Long) return value.toString();

        // Xử lý String (Bọc nháy đơn) - Kiểm tra tránh bọc 2 lần
        String s = value.toString();
        if (s.startsWith("'") && s.endsWith("'")) return s;
        return "'" + s + "'";
    }

    /**
     * JAVA -> NEO4J: Dùng các hàm isTypeOf... của interface Type
     */
    public static String toDatabaseType(Type type) {
        if (type == null || type.isTypeOfVoidType()) return "Void";

        // Nếu là tập hợp, ta lấy kiểu phần tử bên trong để lưu type định nghĩa
        if (type.isKindOfCollection(VoidHandling.EXCLUDE_VOID) && type instanceof CollectionType) {
            return toDatabaseType(((CollectionType) type).elemType());
        }

        if (type.isTypeOfInteger()) return "Int";
        if (type.isTypeOfReal())    return "Double";
        if (type.isTypeOfString())  return "String";
        if (type.isTypeOfBoolean()) return "Boolean";

        // Nếu là Class hoặc Enum, shortName() sẽ trả về tên định danh (vd: "Person")
        return type.shortName();
    }

    /**
     * NEO4J -> JAVA: Ánh xạ chuỗi từ DB về từ khóa mà OCLCompiler hiểu
     */
    public static String toUmlTypeString(String dbType) {
        if (dbType == null || dbType.equalsIgnoreCase("Void")) {
            // TRẢ VỀ NULL: Trong USE API, để tạo hàm Void, tham số returnType phải truyền vào null
            return null;
        }

        switch (dbType) {
            case "Int":    return "Integer";
            case "Double": return "Real";
            case "String": return "String";
            case "Boolean": return "Boolean";
            default:       return dbType; // Trả về tên Class/Enum nguyên bản
        }
    }

    /**
     * MỚI: Tái tạo chuỗi OCL Type đầy đủ (xử lý cả Collection)
     * @param props Map thuộc tính của Attribute/Param lấy từ Neo4j
     */
    public static String reconstructFullTypeString(Map<String, Object> props) {
        Object typeObj = props.get("type");
        if (typeObj == null) return "String";

        String umlBase = toUmlTypeString(typeObj.toString());
        if (umlBase == null) return null;

        boolean isColl = (boolean) props.getOrDefault("isCollection", false);
        if (!isColl) return umlBase;

        boolean isNested = (boolean) props.getOrDefault("isNestedCollection", false);
        String rootCollType = (String) props.getOrDefault("collectionType", "Set");

        // TRƯỜNG HỢP: LỒNG NHAU (Bất kể độ sâu)
        if (isNested) {
            Object chainObj = props.get("nestedChain");
            if (chainObj instanceof List) {
                List<String> chain = (List<String>) chainObj;

                // Bắt đầu từ lõi: Integer
                String result = umlBase;

                // Duyệt ngược từ phần tử cuối cùng của xích (lớp lồng sâu nhất) về đầu
                // Ví dụ: Set(Sequence(Bag(Integer)))
                // chain sẽ là ["Sequence", "Bag"]
                // Vòng 1: Bag(Integer)
                // Vòng 2: Sequence(Bag(Integer))
                for (int i = chain.size() - 1; i >= 0; i--) {
                    String collName = chain.get(i);
                    if (collName != null && !collName.isEmpty()) {
                        result = String.format("%s(%s)", collName, result);
                    }
                }

                // Cuối cùng bọc bởi lớp ngoài cùng (rootCollType)
                return String.format("%s(%s)", rootCollType, result);
            }
        }

        // TRƯỜNG HỢP: TẬP HỢP ĐƠN
        return String.format("%s(%s)", rootCollType, umlBase);
    }
    public static String reconstructNestedTypeString(Map<String, Object> props, List<String> nestedTypes) {
        String baseType = (String) props.get("type");
        String umlBase = toUmlTypeString(baseType);

        boolean isColl = (boolean) props.get("isCollection");
        boolean isNested = (boolean) props.get("isNestedCollection");

        if (!isColl) return umlBase;

        // Nếu không lồng nhau
        if (!isNested) {
            return props.get("collectionType") + "(" + umlBase + ")";
        }

        // Nếu lồng nhau: Duyệt ngược từ trong ra ngoài
        // Ví dụ nestedTypes: ["Sequence"], rootCollType: "Set" -> Set(Sequence(Integer))
        String result = umlBase;
        for (int i = nestedTypes.size() - 1; i >= 0; i--) {
            result = nestedTypes.get(i) + "(" + result + ")";
        }
        result = props.get("collectionType") + "(" + result + ")";

        return result;
    }

    /**
     * Lấy loại tập hợp cụ thể (Set, Sequence, Bag, OrderedSet)
     */
    public static String getCollectionType(Type type) {
        if (type == null || !type.isKindOfCollection(VoidHandling.EXCLUDE_VOID)) {
            return "None";
        }
        if (type.isKindOfOrderedSet(VoidHandling.EXCLUDE_VOID)) return "OrderedSet";
        if (type.isKindOfSet(VoidHandling.EXCLUDE_VOID)) return "Set";
        if (type.isKindOfSequence(VoidHandling.EXCLUDE_VOID)) return "Sequence";
        if (type.isKindOfBag(VoidHandling.EXCLUDE_VOID)) return "Bag";
        return "Set";
    }

    /**
     * Bóc tách đệ quy để lấy kiểu dữ liệu cuối cùng bên trong các lớp tập hợp
     * Ví dụ: Set(Sequence(Person)) -> Person
     */
    public static Type getUltimateBaseType(Type type) {
        if (type.isKindOfCollection(VoidHandling.EXCLUDE_VOID) && type instanceof CollectionType) {
            return getUltimateBaseType(((CollectionType) type).elemType());
        }
        return type;
    }

    /**
     * Tính độ sâu của tập hợp
     */
    public static int getCollectionDepth(Type type) {
        int depth = 0;
        Type current = type;
        while (current.isKindOfCollection(VoidHandling.EXCLUDE_VOID) && current instanceof CollectionType) {
            depth++;
            current = ((CollectionType) current).elemType();
        }
        return depth;
    }
}