# Các snapshot bổ sung

Mỗi snapshot là một trạng thái đối tượng độc lập của **cùng metamodel**, không đổi file `.use` hoặc invariant gốc. Bộ mới có **33 snapshot trên 11 bản metamodel USE**. Các mô hình và snapshot cũ được giữ để tái lập kết quả trước đây.

Mỗi thư mục `instances` gồm:

- `regular.soil`: dữ liệu thông thường, khai báo các liên kết bắt buộc.
- `mixed.soil`: thay đổi thuộc tính và liên kết để tạo vi phạm có chủ đích.
- `larger.soil`: nhiều đối tượng hơn, kết hợp trường hợp đúng, vi phạm và trường hợp biên.
- `regular-expected.csv`, `mixed-expected.csv`, `larger-expected.csv`: tập ID vi phạm theo từng invariant, lấy từ evaluator USE; cột `ids` rỗng nghĩa là không có vi phạm.

`regular` **không có nghĩa mọi invariant đều đúng**: corpus còn có các invariant phân biệt Set/Bag, cast không phù hợp hoặc điều kiện gắn với số lượng đối tượng cố định. Ví dụ `Bag{'dose','dose'} = Bag{'dose'}` cố ý sai trên mọi Patient.

## Danh mục

Số đối tượng được lấy từ trạng thái USE đã nạp, bao gồm instance của association class.

| Profile | Metamodel | Snapshot regular / mixed / larger | Phạm vi |
| --- | --- | ---: | --- |
| Shop | [shop.use](shop-use-soil/shop.use) | [6 / 9 / 36](shop-use-soil/instances/) | Pipeline |
| Advanced | [advanced.use](advanced-adapters/advanced.use) | [8 / 11 / 47](advanced-adapters/instances/) | Pipeline |
| Company | [company.use](ocl2cypher-correctness-case-study/company.use) | [8 / 9 / 45](ocl2cypher-correctness-case-study/instances/) | Pipeline |
| Comprehensive | [comprehensive.use](uml-ocl-comprehensive-case-study/comprehensive.use) | [10 / 12 / 57](uml-ocl-comprehensive-case-study/instances/) | Pipeline |
| Medical | [medical.use](medical/medical.use) | [14 / 17 / 80](medical/instances/) | Pipeline |
| Car Rental UMLMM | [carrentalmodel.use](carrental/umlmm/carrentalmodel.use) | [28 / 42 / 168](carrental/umlmm/instances/) | Pipeline |
| Families | [families.use](families-to-persons-correctness-case-study/families/families.use) | [11 / 14 / 60](families-to-persons-correctness-case-study/families/instances/) | Pipeline |
| Persons | [persons.use](families-to-persons-correctness-case-study/persons/persons.use) | [6 / 9 / 36](families-to-persons-correctness-case-study/persons/instances/) | Pipeline |
| Library | [library.use](case-study-oclcypher/library.use) | [8 / 12 / 48](case-study-oclcypher/instances/) | Pipeline |
| Car Rental gốc | [carrentalmodel.use](carrental/carrentalmodel.use) | [15 / 15 / 45](carrental/instances/) | USE-only |
| MedicalSystemYte | [medical-system.use](medical-system-yte/medical-system.use) | [22 / 21 / 63](medical-system-yte/instances/) | USE-only |

Đường dẫn `.use`, `.soil`, `.ocl`, CSV kỳ vọng, số invariant và phạm vi của từng snapshot nằm trong [snapshot-manifest.tsv](snapshot-manifest.tsv). Có thể chọn trực tiếp một dòng manifest để nạp vào GUI/API. File OCL trống trong manifest nghĩa là invariant đã được nhúng trong `.use`.

## Đặc trưng dữ liệu

| Nhóm | Trường hợp được bổ sung |
| --- | --- |
| Shop | Catalog rỗng, item hoạt động nhưng tên rỗng/chưa xác định |
| Advanced | Lookup qualifier trả 0/1/2 phần tử, qualifier hai đầu, có/không có Loan association-class |
| Company, Comprehensive | Kế thừa, cast, manager vắng mặt, tập navigation rỗng, tuổi chưa xác định/âm, tên lặp, chính sách tài chính và kiểu |
| Medical | Bệnh nhân chưa gán ward/doctor, Boolean và phép đo chưa xác định, tuổi nhỏ, số âm, encounter và liên kết ngược |
| Car Rental UMLMM | Manager/employment khác nhánh, tên/ID lặp, ID chưa xác định, khách chưa thành niên, nhóm xe không được chào thuê, rental đồng thời maintenance |
| Families, Persons | Thiếu vai trò cha/mẹ, tên lặp/chưa xác định, register không đầy đủ, người chưa đăng ký, nhiều cấu hình gia đình |
| Library | Các liên kết đúng kiểu và đủ multiplicity, thành viên chưa đủ tuổi/chưa xác định, phí âm, sách không thuộc branch của loan |
| Hai bản USE-only | Operation, collection-valued attribute và liên kết n-ngôi của mô hình gốc; có thêm trạng thái lớn hơn |

Các snapshot đều đã qua `MSystemState.checkStructure` của USE. Vi phạm invariant được phép; sai kiểu liên kết hoặc vi phạm cấu trúc UML không được dùng làm dữ liệu agreement. Library gốc có một link sai kiểu và vẫn được giữ riêng như input boundary; ba snapshot Library mới là những trạng thái conform độc lập. Ràng buộc 1–1 của metamodel Library làm cho các Member trong các trạng thái mới đều là StaffMember, với Branch/Loan/Book tương ứng.

Trong MedicalSystemYte, các lower bound của association n-ngôi được kiểm tra trên các bộ đầu mút còn lại; snapshot bổ sung đủ tuple, kể cả giữa các nhóm ở bản lớn hơn. Car Rental gốc được đánh giá cả 13 invariant nhúng và 10 invariant trong file OCL. Không đổi metamodel để làm dữ liệu được chấp nhận.

`USE-only` là dữ liệu hợp lệ cho USE nhưng compiler hiện từ chối metamodel vì các cấu trúc ngoài profile. Các snapshot này không được tính là compiler chuyển đổi thành công. Các fixture Ecore/XMI cũ, ER2REL Ecore, `OCLmetamodel.use` và các fixture kiểm tra đầu vào lỗi không thuộc bộ metamodel USE chạy thực nghiệm này.

## Chạy lại

Từ root worktree `ocl2cypher-push`, với JDK 21 trở lên và Maven:

```powershell
.\ocl2cypher\scripts\Run-ExampleCorpusExperiment.ps1 -AllSnapshots
```

Lệnh này chạy 8 snapshot nền và 27 snapshot pipeline mới, đồng thời kiểm tra riêng 6 snapshot USE-only. Không có `-AllSnapshots` thì lệnh giữ bộ 8 snapshot nền.

Kết quả trong `ocl2cypher/target/experiments/<run-id>/` gồm:

- `subjects.csv`: số object/link và kết quả của từng snapshot pipeline.
- `invariants.csv`: ID USE/Q, trạng thái biên dịch, rejection và kết quả Neo4j nếu có chạy.
- `queries/<snapshot>/*.cypher` và `*.ids.tsv`: query cùng ID USE/Core/Q trên cùng snapshot.
- `source-only.csv`: kết quả riêng của sáu snapshot ngoài phạm vi compiler.
- `summary.json`, `manifest.json`, `inputs-before.json`, `inputs-after.json`: tổng hợp, cấu hình và hash đầu vào.

Kết quả local đã xác nhận: **564 lượt USE–Core–Q khớp ID**, 24 rejection có chủ đích, 4 lượt loại khỏi phạm vi và 6 snapshot USE-only đạt oracle. Trong đó 429 lượt agreement đến từ snapshot mới. Hai mismatch CSV lịch sử thuộc snapshot nền được giữ nguyên và ghi riêng; CSV mới không có mismatch.

Muốn chạy thêm Neo4j, dùng các tùy chọn kết nối của script cùng `-EnableNeo4j -ConfirmDisposableDatabase`. Lượt xác nhận bộ mới hiện chạy local; không tuyên bố đã kiểm tra trên Neo4j.

Khi dùng GUI, chọn đúng ba đường dẫn theo manifest và **nạp lại mô hình cho mỗi snapshot**. Không chạy nối tiếp các file SOIL trên cùng trạng thái USE: ID có thể được dùng lại giữa hai snapshot. Bộ này tăng tính đa dạng của dữ liệu; tối đa 168 object trong một snapshot pipeline, chưa thay thế benchmark quy mô lớn.
