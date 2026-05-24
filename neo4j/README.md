# USE Neo4j Plugin

Plugin Neo4j cho USE 7.1.0, hỗ trợ:

- Đồng bộ UML model từ USE sang Neo4j và ngược lại
- Đồng bộ object/runtime state giữa USE và Neo4j
- Đánh giá OCL/OCLite trên dữ liệu trong Neo4j
- Hỗ trợ TGG/correspondence model và rule matching

## 1. Yêu cầu môi trường

Cần cài đặt:

- JDK 17 trở lên
- Maven
- Neo4j Server hoặc Neo4j Desktop
- Project USE trong repository này

Cấu hình Neo4j mặc định trong plugin:

  ```text
  URI      = bolt://127.0.0.1:7687
  User     = neo4j
  Password = 12345678
  Database = uml1
  ```

Có thể thay đổi thông tin này trong cửa sổ kết nối của plugin.

## 2. Build plugin Neo4j

Từ thư mục gốc của repository:

  ```bash
  mvn -pl neo4j -am clean package
  ```

Sau khi build thành công, file plugin được tạo tại:

  ```text
  neo4j/target/neo4j-7.1.0.jar
  ```

Copy file này vào thư mục plugin của USE:

  ```text
  use-assembly/src/main/resources/plugins/officalVersion/neo4j-7.1.0.jar
  ```

## 3. Chạy USE với Neo4j Plugin

Các bước sử dụng cơ bản:

1. Khởi động Neo4j.
2. Chạy USE GUI.
3. Load một file model `.use`.
4. Mở menu:

  ```text
  Plugins -> Neo4j Plugin -> Open DB Connection
  ```

5. Nhập thông tin kết nối Neo4j.
6. Chọn database cần dùng.
7. Bấm `Connect & Open Dashboard`.

Sau khi kết nối thành công, dashboard của plugin sẽ được mở.

## 4. Các chức năng chính

Plugin đăng ký các chức năng sau trong menu `Neo4j Plugin`:

  ```text
  DB Connection
  Sync model forward
  Sync model backward
  Sync object forward
  Sync object backward
  OCL evaluator
  TGG model trafo
  ```

## 6. Đồng bộ model từ USE sang Neo4j

Chức năng này dùng để đẩy UML model hiện tại trong USE sang Neo4j.

Các bước:

1. Load file `.use` trong USE.
2. Kết nối Neo4j bằng `Open DB Connection`.
3. Chọn:

  ```text
  Neo4j Plugin -> Sync model forward
  ```

4. Xác nhận nếu plugin hiển thị preview/diff.
5. Kiểm tra dữ liệu model trong Neo4j.


## 7. Đồng bộ model từ Neo4j về USE

Chức năng này dùng để dựng lại USE model từ dữ liệu đang có trong Neo4j.

Các bước:

1. Kết nối Neo4j.
2. Chọn:

  ```text
  Neo4j Plugin -> Sync model backward
  ```

3. Chọn model cần pull từ Neo4j.
4. Plugin sẽ tạo lại model trong USE session hiện tại.

## 8. Đồng bộ object từ USE sang Neo4j

Cần đồng bộ model trước khi đồng bộ object.

Các bước:

1. Load model `.use`.
2. Tạo hoặc load object state trong USE, ví dụ bằng file `.soil`.
3. Chạy:

  ```text
  Neo4j Plugin -> Sync model forward
  ```

4. Sau đó chạy:

  ```text
  Neo4j Plugin -> Sync object forward
  ```

## 9. Đồng bộ object từ Neo4j về USE

Chức năng này dùng khi object/link đã được tạo hoặc sửa trong Neo4j và cần pull về USE.

Các bước:

1. Đảm bảo USE đang load đúng model.
2. Kết nối đúng database Neo4j.
3. Chọn:

  ```text
  Neo4j Plugin -> Sync object backward
  ```

4. Xác nhận preview nếu có.
5. Plugin sẽ cập nhật object state trong USE.

Mặc định các chế độ auto sync đều tắt.

## 11. OCL Evaluator

OCL evaluator dùng để đánh giá biểu thức OCL trên dữ liệu object đã sync vào Neo4j.

Các bước:

1. Sync model sang Neo4j.
2. Sync object sang Neo4j.
3. Mở:

  ```text
  Neo4j Plugin -> OCL evaluator
  ```

4. Nhập biểu thức.
5. Bấm `Evaluate`.

Lưu ý: OCL evaluator hiện dùng OCLite, chưa phải full USE OCL. Một số cấu trúc OCL phức tạp có thể chưa được hỗ trợ.

## 12. TGG model trafo

Plugin hỗ trợ luồng TGG để sinh correspondence model, tìm match và execute rule trên Neo4j.

Các bước tổng quát:

1. Chuẩn bị source model và target model.
2. Sync cả hai model lên Neo4j.
3. Mở:

  ```text
  Neo4j Plugin -> Correspondence model
  ```

4. Load file `.tgg`.
5. Sinh hoặc copy Cypher query tạo correspondence metamodel.
6. Tạo object test bằng USE, Neo4j hoặc:

  ```text
  Neo4j Plugin -> State manager
  ```

7. Mở:

  ```text
  Neo4j Plugin -> Rule matcher-n-executor
  ```

8. Scan rule.
9. Chọn candidate có trạng thái `FOUND`.
10. Execute query để tạo node/edge mới trong Neo4j.

Ảnh và tài liệu demo nằm trong:

  ```text
  neo4j/readme_img/
  neo4j/docs/
  ```

## 13. Troubleshooting

### Không thấy menu Neo4j Plugin

Kiểm tra:

- File `neo4j-7.1.0.jar` đã nằm trong thư mục plugin chưa.
- USE có đọc đúng plugin directory không.
- Trong jar có file `useplugin.xml` không.

### Không kết nối được Neo4j

Kiểm tra:

- Neo4j đã chạy chưa.
- Bolt URI có đúng không.
- Username/password có đúng không.
- Database có tồn tại không.
- Nếu tạo database mới, user Neo4j cần có quyền tạo database.

### Sync object lỗi vì thiếu model

Cần chạy trước:

  ```text
  Neo4j Plugin -> Sync model forward
  ```

Sau đó mới chạy:

  ```text
  Neo4j Plugin -> Sync object forward
  ```

### OCL evaluator không trả kết quả đúng

Kiểm tra:

- Đã sync object sang Neo4j chưa.
- Object trong Neo4j có `use_id` chưa.
- Model name trong USE có khớp với model trong Neo4j không.
- Thử biểu thức đơn giản trước:

  ```ocl
  ClassName.allInstances()->size()
  ```

### TGG không tìm thấy match

Kiểm tra:

- Source model và target model đã sync lên Neo4j chưa.
- Object data đã sync chưa.
- Tên model trong Neo4j có đúng không.
- File TGG có khai báo đúng class, link và correspondence không.

