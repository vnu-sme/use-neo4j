package org.uet.dse.neo4j.helper;

import org.tzi.use.uml.ocl.type.CollectionType;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.type.Type.VoidHandling;
import org.uet.dse.neo4j.repo.Neo4jModelRepository;

import java.util.HashMap;
import java.util.Map;

public class TypeMapper {
    /**
     * Xử lý tạo quan hệ Reference (Đặc tả cạnh 16 và 17).
     * @param repo Repository thực thi Cypher
     * @param sourceNodeName Tên của Attribute hoặc Operation node
     * @param useType Kiểu dữ liệu cần kiểm tra reference
     * @param isReturnType true nếu là cạnh ReferenceReturnType (17), false nếu là ReferenceType (16)
     */
    public static void handleTypeReference(Neo4jModelRepository repo, String sourceNodeName, Type useType, boolean isReturnType) {
        if (useType == null) return;

        Map<String, Object> tInfo = parseType(useType);

        if (tInfo.containsKey("ref") && tInfo.get("ref") != null) {
            String targetClassName = (String) tInfo.get("ref");

            String edgeLabel = isReturnType ? "ReferenceReturnType" : "ReferenceType";

            repo.createStructuralEdge(sourceNodeName, targetClassName, edgeLabel);
        }
    }

    public static void handleTypeReference(org.uet.dse.neo4j.repo.archive.Neo4jModelRepository repo, String sourceNodeName, Type useType, boolean isReturnType) {
        if (useType == null) return;

        Map<String, Object> tInfo = parseType(useType);

        if (tInfo.containsKey("ref") && tInfo.get("ref") != null) {
            String targetClassName = (String) tInfo.get("ref");

            String edgeLabel = isReturnType ? "ReferenceReturnType" : "ReferenceType";

            repo.createStructuralEdge(sourceNodeName, targetClassName, edgeLabel);
        }
    }


    /**
     * phân tích Type của USE thành Map thuộc tính cho Neo4j
     * @param type Đối tượng Type từ USE Java API
     * @return Map chứa các key: type, isCollection, collType, ref
     */
    public static Map<String, Object> parseType(Type type) {
        Map<String, Object> result = new HashMap<>();
        if (type == null) {
            result.put("type", "Void");
            result.put("isCollection", false);
            result.put("collType", "None");
            return result;
        }

        boolean isColl = type.isKindOfCollection(VoidHandling.EXCLUDE_VOID);
        result.put("isCollection", isColl);

        Type baseType = type;
        if (isColl && type instanceof CollectionType) {
            baseType = ((CollectionType) type).elemType();
            if (type.isKindOfSet(VoidHandling.EXCLUDE_VOID) ||
                    type.isKindOfOrderedSet(VoidHandling.EXCLUDE_VOID)) {
                result.put("collType", "Set");
            } else {
                result.put("collType", "List");
            }
        } else {
            result.put("collType", "None");
        }
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
            result.put("type", "Object");
            result.put("ref", baseType.shortName());
        } else {
            result.put("type", "Object");
            result.put("ref", baseType.shortName());
        }

        return result;
    }

    public static Map<String, Object> parseTypeUSE2Neo4j(Type type) {
        Map<String, Object> map = new HashMap<>();
        if (type == null) {
            map.put("type", "Void");
            map.put("isCollection", false);
            map.put("collType", "None");
            return map;
        }

        boolean isColl = type.isKindOfCollection(Type.VoidHandling.EXCLUDE_VOID);
        map.put("isCollection", isColl);

        Type baseType = type;
        if (isColl && type instanceof CollectionType) {
            baseType = ((CollectionType) type).elemType();
            if (type.isKindOfSet(Type.VoidHandling.EXCLUDE_VOID)) map.put("collType", "Set");
            else if (type.isKindOfBag(Type.VoidHandling.EXCLUDE_VOID)) map.put("collType", "Bag");
            else map.put("collType", "List");
        } else {
            map.put("collType", "None");
        }

        if (baseType.isTypeOfInteger()) map.put("type", "Int");
        else if (baseType.isTypeOfString()) map.put("type", "String");
        else if (baseType.isTypeOfReal()) map.put("type", "Double");
        else if (baseType.isTypeOfBoolean()) map.put("type", "Boolean");
        else if (baseType.isKindOfEnum(Type.VoidHandling.EXCLUDE_VOID) || baseType.isKindOfClass(Type.VoidHandling.EXCLUDE_VOID)) {
            map.put("type", "Object");
            map.put("ref", baseType.shortName());
        } else {
            map.put("type", "Object");
            map.put("ref", baseType.shortName());
        }
        return map;
    }

}