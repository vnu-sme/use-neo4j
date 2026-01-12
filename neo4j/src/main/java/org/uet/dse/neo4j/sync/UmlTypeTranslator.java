package org.uet.dse.neo4j.sync;

import org.tzi.use.uml.ocl.type.CollectionType;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.type.Type.VoidHandling;
import java.util.List;

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
}