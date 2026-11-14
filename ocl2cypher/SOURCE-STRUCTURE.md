# Cấu trúc mã nguồn OCL2Cypher

Các file lớn được chia theo trách nhiệm. Các lớp quy tắc chuyển đổi là package-private; điểm vào biên dịch chính, adapter Neo4j và giao diện giữ API đang sử dụng.

## Đầu vào

Công cụ nhận `.use` cho mô hình, `.soil` cho snapshot và `.ocl` tùy chọn cho invariant riêng. Giao diện có trường **OCL File (optional)**; để trống khi invariant nằm trong `.use`. File `.ocl` chứa các khai báo `context ... inv ...` theo cú pháp USE. Invariant trong `.use` và `.ocl` được nạp cùng nhau qua parser USE; tên invariant trùng được parser báo lỗi.

API: `UseSoilInput.load(usePath, soilPath)` hoặc `UseSoilInput.load(usePath, soilPath, oclPath)`, rồi gọi `ModelInputCompiler.compile`. Các phần mở rộng khác bị từ chối ở boundary với diagnostic `E_USE_SOIL_INPUT`.

Đã gỡ các reader và API Ecore/XMI, UML/XMI cùng dependency EMF. Các kiểu UML trong `source/model` vẫn biểu diễn mô hình nguồn USE và được phép chuyển đổi sử dụng.

| Thành phần | Trách nhiệm |
| --- | --- |
| `cypher/Realization` | Điều phối Q → Cypher AST, quản lý alias, tham số, lexical scope và điều kiện certification. |
| `cypher/ScalarRealization` | Logic ba giá trị, số học, equality, unary/binary và biểu diễn giá trị. |
| `cypher/CypherArtifacts` | Tạo và quan sát tagged value: payload, bottom, scalar, object và collection. |
| `cypher/NavigationRealization` | Thuộc tính, navigation, qualifier, association class và kiểm tra kiểu object. |
| `cypher/CollectionRealization` | Materialize plan, occurrence, collection, iterator và whole-collection bottom. |
| `frontend/OclFrontend` | Parser, token cursor và lexical scope. |
| `frontend/OclResolution` | Phân giải property/operation theo schema. |
| `frontend/OclTyping` | Conformance, join kiểu, coercion và suy kiểu collection rỗng. |
| `frontend/FrontendError` | Diagnostic dùng chung cho parser, resolution và typing. |
| `source/model/CapabilityMatrix` | Allowlist operation dùng tại runtime. |
| `trace/TracingCompiler` | Lớp quan sát tùy chọn, gọi compiler và ghi trace; compiler thường không phụ thuộc collector. |
| `plugin/ui/Ocl2CypherDialog` | Điều phối tác vụ nạp graph, biên dịch, chạy query và hiển thị kết quả. |
| `plugin/ui/GraphCanvas` | Vẽ graph và tương tác node/edge. |
| `plugin/ui/WorkbenchStyle`, `LineNumbers` | Palette, tạo widget và số dòng editor. |
| `execution/Neo4jExecutionAdapter` | Kết nối, materialize graph và thực thi query. |
| `execution/Neo4jValueCodec` | Hợp đồng tham số, physical payload và giải mã tagged result. |
| `graph/ValidRepChecker` | Tương ứng đối tượng/snapshot, thuộc tính, liên kết và multiplicity. |
| `graph/RepositorySchemaValidator` | Cấu trúc repository/schema mà Cypher quan sát. |

Các rule dùng cùng context của một lượt compilation để giữ thứ tự cấp alias/tham số, declaration identity và bind-once. Certification Integer/Real của worktree tiếp tục là điều kiện chấp nhận; các lớp mới dùng cùng đối tượng certificate của lượt compilation. Xử lý undefined, chia cho 0, Bag, equality collection và stable object ID vẫn thuộc các quy tắc hiện có.

Certificate chỉ giữ dữ liệu được dùng để quyết định: cận dưới/trên của Integer và miền giá trị của Real. Cây giải thích `rule/children` được bỏ; các kiểm tra tràn số, tổng trung gian, giá trị finite/exact binary64 và giới hạn kích thước miền vẫn được giữ. Kiểm thử kiểm tra kết quả miền số thay cho cấu trúc cây giải thích.

Phân phối biểu thức Q dùng pattern switch; điều kiện admission số được kiểm tra riêng trước khi chọn quy tắc. Các vòng parse `and/or/xor` dùng chung một helper, còn `implies` giữ cách parse riêng của worktree.

Khi cần trace, gọi `TracingCompiler.compile/lower/translate/realize/serialize` với collector. Các hàm compiler chính chỉ nhận đầu vào phục vụ chuyển đổi.

## Kiểm thử và dữ liệu hỗ trợ

`SemanticBoundaryRegressionTest` được chia thành:

- `ScalarBoundaryRegressionTest`: các trường hợp Boolean và số học.
- `CollectionBoundaryRegressionTest`: collection, materialization và iterator.
- `SemanticBoundaryRegressionTest`: các boundary nguồn, graph, scope, object/type và VALUE còn lại.
- `SemanticBoundaryFixtures`: helper dùng chung, không có state dùng chung giữa các test.

Giữ nguyên 48 test của nhóm boundary. Các lớp `caseStudy/CarRentalFixture`, `CaseStudyReplayer`, `LibraryCorpus`, `conformance/PropertyFixtures` và `frontend/GrammarConformance` nằm trong `src/test/java`. Chúng phục vụ regression/evaluation và không được đóng gói vào JAR production.

Witness, tên constructor, rule, oracle coverage và test ID của capability nằm trong `src/test/resources/operation-witnesses.tsv`, được đọc bởi `support/CapabilityEvidence`. Baseline test đối chiếu từng operation giữa dữ liệu kiểm thử và allowlist runtime; operation mới mặc định bị từ chối.

`CoreOracle`, `CoreInterpreter`, `QInterpreter`, `OclOps`, `CoreValidator` và `InstanceRepValidator` cũng nằm trong `src/test/java`: chúng phục vụ đối chiếu ngữ nghĩa và kiểm tra cấu trúc, không được compiler hay giao diện gọi. Bộ kiểm tra graph đang dùng tại runtime vẫn là `ValidRepChecker` và `RepositorySchemaValidator`.

Grammar `OCL.g4` nằm trong `src/test/antlr4`. Maven sinh parser ANTLR vào `target/generated-test-sources/antlr4`; parser và ANTLR runtime chỉ thuộc classpath kiểm thử. Parser chạy chính vẫn là `OclFrontend`. Các test grammar và precedence tiếp tục dùng cùng grammar, không thay đổi nội dung.

Đã bỏ lớp điều phối cũ `UseSoilNeo4jAction`, không có nơi gọi từ ứng dụng hoặc script. Luồng đang dùng tiếp tục nạp bằng `UseSoilInput`, tạo graph bằng `GraphBuilder` và thực thi qua `Neo4jExecutionAdapter`. Các API nội bộ không có nơi gọi đã bỏ: `Result.map`, `Boolean3.bool3Of`, `OclType.isBoolean/isReal/isString`, `StableIdDisambiguator.isUsed`, `UmlAssociation.isToMany/qualifierNames`; hai trường lưu giá trị suy ra đi kèm cũng được bỏ. Mã bên ngoài từng gọi các API này cần điều chỉnh khi nâng cấp.

`GraphBuilder` giao việc kiểm tra hợp đồng encoding cho `ValidRepChecker`, tránh duyệt cùng graph hai lần liên tiếp. Hai helper `GraphModel.associationClassSourceParticipant/associationClassTargetParticipant` chỉ trả hằng số và đã được thay bằng các hằng `ASSOCIATION_CLASS_SOURCE_PARTICIPANT/ASSOCIATION_CLASS_TARGET_PARTICIPANT`. `MetamodelMapping.associationClassParticipant` chỉ nhận loại participant; khóa association tiếp tục được dùng trong định danh và bộ lọc thuộc tính tại các quy tắc chuyển đổi.

Adapter kiểm thử cũ `oracle/UseOracleAdapter` đã bỏ vì không có nơi sử dụng. Các test đối chiếu USE hiện dùng `execution/UseEvaluationAdapter`; các helper kiểm thử bỏ hoang được xóa, nhưng các phương thức test và chương trình kiểm tra thủ công vẫn được giữ.

Các file datatype/validator còn tương đối dài được giữ theo cấu trúc phép duyệt hiện tại. Việc tách dựa trên trách nhiệm độc lập; không đặt giới hạn số dòng cứng cho mọi file.

Đã bỏ các overload không có nơi gọi: constructor 3 tham số của `ValueQueryRequest`, constructor `AssociationClassNavigation` thiếu hướng receiver, constructor `UmlAssociation` có `QualifierEnd` nhưng thiếu `AssociationKind`, và `Neo4jExecutionAdapter.execute` không nhận graph. Dùng factory hoặc constructor đầy đủ; luồng thực thi hiện tại truyền graph ở lời gọi `execute`. Mã bên ngoài từng gọi các overload cũ cần điều chỉnh.

`OmgDocument` chỉ giữ context class và constraint; tên biến context nằm trong `ExpressionInOcl.contextVariable`, không lưu thêm bản sao trên document. Constructor document nhận `span`, `contextClassKey`, `constraints`. Đã bỏ interface `CypherAst.Spanned` không có nơi sử dụng; span cụ thể của `CypherQuery` và `LocatedExpr` vẫn được giữ. Parser tài liệu kiểm tra Boolean trực tiếp cho invariant; parser VALUE xử lý biểu thức riêng, không cần cờ chế độ dùng chung.

Các test codec và graph view gọi trực tiếp `Neo4jValueCodec`, `GraphCanvas` trong cùng package, không đi qua wrapper chỉ dành cho test trên adapter hoặc dialog. API trace tùy chọn, callback Swing/USE và các chương trình kiểm tra có `main` vẫn có vai trò riêng và được giữ.

## Chạy kiểm tra

Từ root worktree, chạy `mvn -pl ocl2cypher test` hoặc `package` trên Java 21 trở lên. Các test case study cần fixture `research/Case Study`, gồm dữ liệu library và `ER2REL/use-oracle`. Khi dùng worktree không có thư mục nghiên cứu, cần cấp cùng fixture nghiên cứu từ workspace chính trước khi chạy bộ test đầy đủ. Các test Neo4j tùy chọn chỉ chạy khi có cấu hình môi trường tương ứng.
