# Tóm Tắt Hạn Chế Của Luồng TGG-Neo4j Và Hướng Cải Thiện

## 1. Hiện trạng ngắn gọn

Luồng `TGG-Neo4j` hiện tại mới ở mức nguyên mẫu. Hệ thống đã có thể:

- đọc một phần cấu trúc rule TGG;
- sinh câu truy vấn `Cypher MATCH` để tìm match trên Neo4j;
- lấy kết quả match và tạo một phần object/link trong `USE runtime`.

Tuy nhiên, đây **chưa phải** là một TGG engine hoàn chỉnh chạy thuần trên Neo4j. Hiện tại:

- Neo4j chủ yếu mới tham gia ở bước `tìm match`;
- phần tạo kết quả vẫn đổ về `USE system state`;
- phần hiển thị object diagram vẫn dùng view cũ của USE/RTL.

## 2. Các hạn chế chính

- Chưa thực thi đầy đủ các biểu thức `guard/OCL` của rule.
- Chưa gán đúng giá trị thuộc tính cho object đích theo các lệnh assignment trong TGG.
- Chưa bảo đảm sinh đầy đủ và chính xác toàn bộ object/link/correspondence theo rule.
- Chưa xử lý tốt trường hợp có nhiều match, dễ phát sinh tạo trùng hoặc ghi đè object.
- Kết quả hiện tại còn phụ thuộc mạnh vào ví dụ `Families2Persons`, chưa chứng minh được tính tổng quát.
- Luồng `forward` mới ở mức thử nghiệm; `backward` và `co-evolution` hầu như chưa được hoàn thiện.
- Chưa có cơ chế tách biệt rõ kết quả giữa luồng TGG cũ trên RAM và luồng TGG-Neo4j.
- Chưa có đánh giá hiệu năng trên dữ liệu lớn.

## 3. Minh họa hiện trạng

Chèn ảnh object diagram hiện tại của luồng `TGG-Neo4j` tại đây để minh họa rằng:

- kết quả mới dừng nhiều ở mức cấu trúc object/link;
- phần thuộc tính và tính đầy đủ của đối tượng vẫn chưa tương đương bản TGG cũ.

## 4. Hướng cải thiện đề xuất

- Bổ sung cơ chế dịch và thực thi `guard/OCL`.
- Hoàn thiện phần bind match và sinh ID duy nhất cho object đích.
- Bổ sung bước gán thuộc tính sau khi tạo object/link.
- Bổ sung traceability để tránh tạo lặp khi chạy nhiều lần.
- Hoàn thiện đầy đủ các chiều `forward`, `backward`, `co-evolution`.
- Tách riêng luồng hiển thị hoặc cơ chế kiểm chứng kết quả giữa TGG cũ và TGG-Neo4j.
- Kiểm thử trên nhiều bộ rule khác ngoài `Families2Persons`.
- Đánh giá hiệu năng trên dataset lớn để chứng minh lợi thế của Neo4j.

## 5. Kết luận một câu

Hiện tại, `TGG-Neo4j` mới là bước đầu thay thế phần tìm match bằng Neo4j; để trở thành một công cụ hoàn chỉnh, vẫn cần hoàn thiện phần thực thi rule, gán thuộc tính, hỗ trợ nhiều chiều biến đổi và đánh giá trên quy mô lớn.
