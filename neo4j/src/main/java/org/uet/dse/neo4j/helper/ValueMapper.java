package org.uet.dse.neo4j.helper;

import org.tzi.use.uml.ocl.value.*;
import java.util.*;

public class ValueMapper {

    /**
     * Chuyển đổi Value của USE thành Object Java cơ bản hoặc danh sách tham chiếu
     */
    public static Object mapUseValue(Value value) {
        // Trường hợp giá trị chưa xác định (Undefined)
        if (value == null || value.isUndefined()) {
            return null;
        }

        // 1. Xử lý kiểu Tập hợp (Collection: Set, Sequence, Bag)
        if (value instanceof CollectionValue) {
            CollectionValue cv = (CollectionValue) value;
            List<Object> resultList = new ArrayList<>();

            // Trong USE, CollectionValue chứa một OclCollection
            // Ta lấy iterator để duyệt qua các phần tử Value bên trong
            Iterator<Value> it = cv.collection().iterator();
            while (it.hasNext()) {
                Value element = it.next();
                Object mappedElement = mapUseValue(element); // Đệ quy
                if (mappedElement != null) {
                    resultList.add(mappedElement);
                }
            }
            return resultList;
        }

        // 2. Xử lý kiểu Tham chiếu đối tượng (ObjectValue)
        if (value instanceof ObjectValue) {
            // value() trả về đối tượng MObject
            // Chúng ta lấy tên của Object để làm định danh tham chiếu trên Neo4j
            return ((ObjectValue) value).value().name();
        }

        // 3. Xử lý các kiểu nguyên thủy (Primitive Types)
        // USE có các lớp: IntegerValue, StringValue, BooleanValue, RealValue
        if (value instanceof IntegerValue) {
            return ((IntegerValue) value).value(); // Trả về Long/Integer
        }
        if (value instanceof StringValue) {
            return ((StringValue) value).value(); // Trả về String
        }
        if (value instanceof BooleanValue) {
            return ((BooleanValue) value).value(); // Trả về Boolean
        }
        if (value instanceof RealValue) {
            return ((RealValue) value).value(); // Trả về Double
        }

        // Mặc định trả về chuỗi đại diện nếu không khớp kiểu nào ở trên
        return value.toString();
    }
}