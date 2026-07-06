# Báo cáo đề xuất cải thiện phần TGG - Neo4j

## Tóm tắt nhanh

Phần TGG Neo4j hiện tại đã có nền móng: RTL có thể nạp luật TGG, Neo4j plugin có thể đồng bộ model/object, và `NRuleCollectionManager` đã bắt đầu sinh Cypher để tìm match trên Neo4j. Tuy nhiên, engine hiện mới ở mức nguyên mẫu: chưa xử lý đầy đủ guard/OCL, chưa thực thi assignment trong rule, chưa bind kết quả match từ Neo4j vào RHS, chưa quản lý traceability đúng nghĩa, và mới hỗ trợ hướng forward.

Hướng phát triển phù hợp cho khóa luận là xây dựng một engine thực thi TGG dựa trên Neo4j: chuyển pattern TGG sang Cypher, lọc match bằng guard/OCL, materialize target/correspondence object vào USE, và lưu trace để tránh tạo trùng cũng như mở đường cho đồng bộ hai chiều.

## 1. Bối cảnh

Dự án hiện tại đang tích hợp ba thành phần chính:

- USE/RTL: nạp metamodel, nạp luật TGG và tạo `RuleCollection` trong runtime của USE.
- Neo4j plugin: đồng bộ model/object giữa USE và Neo4j.
- TGG Neo4j engine: thử nghiệm chạy luật TGG bằng cách tìm match trên Neo4j và tạo object/link kết quả vào USE.

Luồng hiện tại có ý tưởng tốt: thay vì chỉ chạy TGG trên object trong USE, hệ thống hướng tới việc dùng Neo4j làm kho lưu trữ graph, tìm match bằng Cypher, rồi materialize kết quả trở lại USE. Đây là một hướng có giá trị vì Neo4j phù hợp với dữ liệu graph, trong khi TGG cũng là bài toán graph transformation.

Tuy nhiên, phần TGG Neo4j hiện mới ở mức nguyên mẫu. Code đã có nền móng, nhưng chưa đủ tổng quát và chưa đủ chính xác để trở thành một engine TGG hoàn chỉnh.

## 2. Hiện trạng kỹ thuật

### 2.1. Phần RTL đã nạp được luật TGG

`RTLLoader` hiện có thể:

- đọc source metamodel và target metamodel;
- parse file `.tgg`;
- sinh merged USE model;
- tạo object `RuleCollection`;
- lưu rule collection vào `org.uet.dse.rtlplus.Main`.

Điểm này cho thấy phần frontend/language infrastructure của TGG đã có sẵn và có thể tái sử dụng.

File liên quan:

- `rtl/src/main/java/org/uet/dse/rtlplus/RTLLoader.java`
- `rtl/src/main/java/org/uet/dse/rtlplus/Main.java`

### 2.2. Phần Neo4j đã có đồng bộ model và object

Neo4j plugin đã có các chức năng:

- đẩy model từ USE sang Neo4j;
- đẩy object/link từ USE sang Neo4j;
- kéo object/link từ Neo4j về USE;
- lưu object bằng `use_id`;
- biểu diễn object instance bằng quan hệ `ObjectInstanceOf`;
- biểu diễn link bằng các quan hệ như `LinkAssociateWith`, `LinkAggregates`, `LinkComposeOf`.

Đây là nền tảng quan trọng để TGG engine có dữ liệu graph để truy vấn.

File liên quan:

- `neo4j/src/main/java/org/uet/dse/neo4j/sync/object/ObjectPushService.java`
- `neo4j/src/main/java/org/uet/dse/neo4j/sync/object/ObjectPullService.java`
- `neo4j/src/main/java/org/uet/dse/neo4j/repo/query/Neo4jObjectQuery.java`

### 2.3. Phần TGG Neo4j mới xử lý được một phần nhỏ

`NRuleCollectionManager` hiện đang:

- lấy `MRuleCollection` từ RTL;
- duyệt các `MTggRule`;
- sinh Cypher `MATCH` từ pattern object/link;
- chạy query trên Neo4j;
- tạo object/link mới vào USE bằng `UseSystemApi`.

File liên quan:

- `neo4j/src/main/java/org/uet/dse/neo4j/tgg/engine/NRuleCollectionManager.java`

## 3. Các vấn đề cần cải thiện

### 3.1. Engine chưa tổng quát, mới phù hợp với ví dụ đơn giản

File `F2PForward.tgg` trong ví dụ `Families2Persons` có cấu trúc khá quen thuộc:

- source object: `FamilyRegister`, `Family`, `FamilyMember`;
- target object: `PersonRegister`, `Male`, `Female`;
- correspondence object: `FR2PR`, `F2MP`, `FM2MP`, ...

Engine hiện không hard-code tên `Family` hay `Person`, nhưng logic đang đơn giản hóa mạnh:

- chỉ dùng object/link pattern để tạo Cypher;
- chưa xử lý đầy đủ guard/OCL;
- chưa dùng kết quả match để bind object thật;
- chưa sinh tên object kết quả theo từng match;
- chưa thực thi assignment trong rule.

Vì vậy, engine hiện mới giống một proof-of-concept cho case `Families2Persons`, chưa phải engine TGG tổng quát.

### 3.2. Chưa xử lý guard/OCL condition

Trong TGG, guard/OCL là điều kiện quyết định rule có được áp dụng hay không. Ví dụ trong `F2PForward.tgg`:

```tgg
[fm.name <> Undefined]
```

Điều kiện này yêu cầu family phải có `name`. Nếu bỏ qua guard, engine có thể match sai và tạo target object không hợp lệ.

Các điều kiện khác:

```tgg
F2MP:[self.mp.name.split(', ')->at(2) = self.f.name]
FM2MP:[self.mp.name.split(', ')->at(1)= self.fm.name]
```

Hiện `NRuleCollectionManager` chưa chuyển các OCL condition này sang Cypher, cũng chưa đánh giá chúng bằng USE OCL evaluator. Điều này làm giảm độ đúng của transformation.

### 3.3. Chưa xử lý assignment/action trong rule

Trong `F2PForward.tgg`, có các lệnh gán như:

```tgg
`self.mp.name := self.f.familyFather.name + ', ' + self.f.name`
```

Đây là phần quyết định attribute của target object. Nếu chỉ tạo object `Male` mà không set `name`, kết quả transformation chưa đúng về mặt ngữ nghĩa.

Hiện engine mới tạo object/link:

```java
systemApi.createObject(obj.cls().name(), obj.name());
systemApi.createLinkEx(link.association(), link.linkedObjectsAsArray());
```

Chưa có bước tính toán và set attribute theo action của rule.

### 3.4. Chưa bind kết quả match từ Neo4j vào RHS của rule

Engine có chạy query và map kết quả Neo4j thành `FindMatchResult`, nhưng sau đó chưa sử dụng kết quả này để tạo RHS tương ứng.

Hiện tại:

- query tìm match được trả về;
- `res` được in ra;
- nhưng `resolveNewObjectNLinks(rule)` lại tạo object/link trực tiếp từ RHS pattern của rule.

Điều này có nghĩa là engine đang dùng tên biến trong rule như tên object thật. Ví dụ `mp`, `f2mp`, `fm2mp` có thể bị tạo như object cố định. Nếu có nhiều match, engine không sinh được nhiều target object riêng biệt.

Đây là vấn đề cốt lõi cần cải thiện.

### 3.5. Chưa có cơ chế sinh tên object target/correspondence

Một transformation thật cần sinh object mới theo từng match. Ví dụ cùng một rule `Father2Male` có thể áp dụng cho nhiều `father` khác nhau, mỗi match cần sinh một `Male` khác nhau.

Engine cần một cơ chế đặt tên như:

- dựa trên rule name và source object id;
- dựa trên mapping table;
- hoặc dùng `UniqueNameGenerator` của RTL.

Hiện tại chưa có cơ chế này, nên dễ trùng tên object hoặc chỉ chạy được một lần.

### 3.6. Chưa kiểm soát traceability/correspondence đầy đủ

TGG phụ thuộc rất nhiều vào correspondence graph để biết source object nào đã sinh target object nào. Ví dụ:

```tgg
(father, mp) as (f, mp) in f2mp:F2MP
```

Correspondence object `F2MP` không chỉ là dữ liệu phụ. Nó là trace link để:

- tránh tạo lặp target object;
- phục vụ backward transformation;
- phục vụ synchronization/co-evolution;
- truy vết nguồn gốc kết quả.

Engine hiện có tạo correspondence RHS, nhưng chưa dùng nó như một traceability index thực sự.

### 3.7. Chưa hỗ trợ backward/co-evolution

Trong `NRuleCollectionManager`, chỉ nhánh `FORWARD` có xử lý. Các hướng còn lại đang fallback:

```java
case BACKWARD -> fallBack();
case COEVOLUTION -> fallBack();
```

Trong khi đó TGG có giá trị nhất khi hỗ trợ nhiều hướng:

- forward: source sang target;
- backward: target sang source;
- synchronization: đồng bộ hai chiều;
- co-evolution: cập nhật khi model thay đổi.

Đây là khoảng trống lớn nếu muốn phát triển thành đề tài khóa luận.

### 3.8. Mapping giữa USE graph và Neo4j graph chưa nhất quán hoàn toàn

Có dấu hiệu lệch naming quan hệ:

- Object sync dùng `LinkAggregates`;
- TGG query dùng `LinkAggregateWith`.

Nếu Neo4j lưu quan hệ bằng một tên nhưng TGG query tìm tên khác, rule sẽ không match.

Ngoài ra, engine cần thống nhất rõ schema Neo4j:

- object node;
- class node;
- attribute value node;
- association relationship;
- link object;
- correspondence object;
- model version;
- object timestamp.

Nếu không có schema contract rõ, các module sync, OCL, TGG sẽ rất dễ lệch nhau.

### 3.9. Có lỗi logic trong `resolveClasses`

Đoạn cuối của `resolveClasses` hiện là:

```java
singleClass.addAll(singleClass);
```

Về mặt logic, nhiều khả năng phải là:

```java
singleClass.addAll(allObject);
```

Lỗi này làm các object không nằm trong link pattern không được đưa vào Cypher `MATCH`, khiến rule thiếu điều kiện match.

### 3.10. Chưa có UI/action riêng để chạy TGG từ Neo4j

Hiện `NRuleCollectionManager` chưa được nối thành một action rõ ràng trong menu. Trong `ActionOpenOclEvaluator`, đoạn gọi TGG đang bị comment:

```java
// NRuleCollectionManager st = new NRuleCollectionManager(...);
// st.iterateThroughMRuleCollection();
```

Vì vậy, về mặt trải nghiệm người dùng, hiện chưa có nút "Run TGG from Neo4j" hoàn chỉnh.

### 3.11. Thiếu kiểm thử và bộ benchmark

Hiện chưa có test rõ ràng cho:

- parse TGG rồi sinh Cypher;
- match trên Neo4j;
- tạo RHS object/link vào USE;
- guard/OCL conversion;
- assignment/action execution;
- nhiều match cùng lúc;
- tránh tạo trùng object;
- forward/backward consistency.

Nếu muốn làm khóa luận, cần có bộ ví dụ và tiêu chí đánh giá rõ.

## 4. Hướng cải thiện kỹ thuật

### 4.1. Chuẩn hóa kiến trúc TGG Neo4j engine

Nên tách engine thành các module:

- `TggRuleAnalyzer`: đọc `MTggRule`, tách LHS/RHS, source/target/corr.
- `PatternToCypherCompiler`: chuyển graph pattern sang Cypher.
- `OclToCypherCompiler`: chuyển guard/OCL sang điều kiện Cypher.
- `MatchBinder`: bind kết quả Neo4j record vào biến trong rule.
- `RhsMaterializer`: tạo object/link/attribute vào USE hoặc Neo4j.
- `TraceabilityManager`: quản lý correspondence mapping.
- `TransformationRunner`: điều phối forward/backward/synchronization.

Cách tách này giúp engine dễ kiểm thử và dễ mở rộng.

### 4.2. Dùng kết quả Neo4j match để tạo object chính xác

Cần thay đổi luồng hiện tại từ:

```text
rule -> query -> records -> tạo RHS từ rule pattern
```

thành:

```text
rule -> query -> records -> bind variables -> materialize RHS per match
```

Với mỗi `Record` trả về từ Neo4j, engine cần biết:

- biến `fr` map tới object nào;
- biến `fm` map tới object nào;
- biến `father` map tới object nào;
- target object nào đã tồn tại;
- target object nào cần tạo mới;
- correspondence object nào cần tạo mới.

### 4.3. Sinh tên object theo match

Nên xây dựng quy tắc đặt tên ổn định, ví dụ:

```text
<ruleName>_<targetVar>_<sourceUseIdHash>
```

Ví dụ:

```text
Father2Male_mp_father
Father2Male_f2mp_father
```

Hoặc dùng bảng trace:

```text
(:TggTrace {rule, sourceIds, targetIds, corrIds})
```

Cách dùng trace node trong Neo4j sẽ bền hơn vì hỗ trợ tránh duplicate và hỗ trợ synchronization về sau.

### 4.4. Hỗ trợ guard/OCL

Có hai hướng:

Hướng 1: chuyển OCL sang Cypher.

- Ưu điểm: chạy trực tiếp trên Neo4j, hiệu năng tốt.
- Nhược điểm: phải hỗ trợ nhiều cú pháp OCL.

Hướng 2: dùng Neo4j để tìm graph pattern, sau đó dùng USE/OCL evaluator để lọc match.

- Ưu điểm: tận dụng evaluator có sẵn của USE.
- Nhược điểm: phải materialize/bind context để evaluate.

Hướng phù hợp cho khóa luận có thể là hybrid:

- hỗ trợ subset OCL phổ biến bằng Cypher;
- fallback sang USE evaluator cho biểu thức phức tạp;
- ghi nhận phạm vi OCL được hỗ trợ.

### 4.5. Hỗ trợ assignment/action

Engine cần đọc các action trong rule và thực thi:

```tgg
`self.mp.name := self.f.familyFather.name + ', ' + self.f.name`
```

Có thể làm theo hai mức:

- mức cơ bản: hỗ trợ assignment cho primitive attribute như String, Integer, Boolean;
- mức nâng cao: hỗ trợ navigation, collection operation, split/concat, object reference.

Với `Families2Persons`, cần tối thiểu xử lý được:

- đọc attribute từ source object;
- nối chuỗi;
- set attribute target object.

### 4.6. Quản lý traceability/correspondence trong Neo4j

Nên lưu trace rõ ràng trong Neo4j:

```cypher
(:TggTrace {
  rule: "Father2Male",
  direction: "FORWARD",
  sourceSignature: "...",
  targetSignature: "...",
  createdAt: ...
})
```

Trace node liên kết tới:

- source objects;
- target objects;
- correspondence objects;
- rule node.

Lợi ích:

- tránh chạy rule trùng;
- hỗ trợ incremental update;
- hỗ trợ undo/redo;
- hỗ trợ backward transformation;
- hỗ trợ giải thích kết quả transformation.

### 4.7. Chuẩn hóa schema Neo4j

Cần viết một tài liệu schema chính thức cho Neo4j:

- label nào dùng cho class;
- label nào dùng cho object;
- property bắt buộc là gì;
- quan hệ nào biểu diễn association;
- correspondence object lưu như thế nào;
- attribute value lưu như thế nào;
- version/timestamp được cập nhật khi nào.

Sau đó toàn bộ module sync, OCL, TGG phải dùng chung schema này.

### 4.8. Tạo UI riêng cho TGG Neo4j

Nên thêm một menu trong plugin:

```text
Neo4j Plugin phase 2 > Run TGG from Neo4j
```

Giao diện nên hiển thị:

- rule collection hiện tại;
- số lượng match tìm thấy;
- preview object/link sẽ tạo;
- nút run;
- log kết quả;
- lỗi guard/action nếu có.

UI này giúp phần demo khóa luận rõ ràng hơn nhiều.

### 4.9. Bổ sung kiểm thử

Cần có test theo từng lớp:

- Unit test cho `PatternToCypherCompiler`.
- Unit test cho `OclToCypherCompiler`.
- Integration test với Neo4j test container hoặc database test.
- End-to-end test: nạp `Families.use`, nạp `F2PForward.tgg`, push object, chạy transformation, kiểm tra `Male/Female/PersonRegistration`.
- Regression test cho lỗi duplicate object.

## 5. Hướng phát triển thành đề tài khóa luận

### 5.1. Tên đề tài gợi ý

**Xây dựng cơ chế thực thi Triple Graph Grammar trên cơ sở dữ liệu đồ thị Neo4j và tích hợp với môi trường USE**

Tên rút gọn:

**Neo4j-based TGG Transformation Engine for USE Models**

### 5.2. Vấn đề nghiên cứu

Các công cụ TGG truyền thống thường chạy transformation trực tiếp trên mô hình trong bộ nhớ. Cách này phù hợp với mô hình nhỏ, nhưng gặp hạn chế khi dữ liệu lớn, cần truy vấn linh hoạt, cần lưu vết thay đổi, hoặc cần đồng bộ cộng tác.

Neo4j là cơ sở dữ liệu đồ thị, có khả năng lưu object graph và truy vấn pattern bằng Cypher. Vì TGG cũng dựa trên graph pattern, việc chuyển matching và traceability sang Neo4j là một hướng nghiên cứu hợp lý.

Câu hỏi nghiên cứu chính:

```text
Làm thế nào để thực thi luật TGG trên dữ liệu model được lưu trong Neo4j, đồng thời duy trì tính đúng đắn, traceability và khả năng đồng bộ với USE?
```

### 5.3. Mục tiêu đề tài

- Thiết kế schema Neo4j cho UML object graph và TGG traceability.
- Xây dựng bộ biên dịch graph pattern TGG sang Cypher.
- Hỗ trợ một subset OCL guard trong rule TGG.
- Xây dựng cơ chế bind match result và materialize RHS.
- Hỗ trợ forward transformation từ source sang target.
- Tích hợp kết quả với USE runtime.
- Đánh giá trên các case study như `Families2Persons`, `CD2RDS`, `uml2csp`.

### 5.4. Phạm vi khả thi cho khóa luận

Để đề tài vừa đủ sâu vừa khả thi, nên giới hạn phạm vi:

- Chỉ tập trung forward transformation ở phiên bản đầu.
- Hỗ trợ binary association trước, ternary/link object là mở rộng.
- Hỗ trợ subset OCL phổ biến:
  - so sánh `=`, `<>`;
  - kiểm tra `Undefined`;
  - truy cập attribute;
  - string concat;
  - một số operation như `split`, `at` nếu cần cho `Families2Persons`.
- Chưa cần hỗ trợ toàn bộ OCL chuẩn.
- Backward/co-evolution đưa vào phần định hướng phát triển.

### 5.5. Kiến trúc đề xuất cho khóa luận

```text
USE/RTL Parser
     |
     v
MRuleCollection
     |
     v
TGG Rule Analyzer
     |
     v
Pattern + Guard + RHS Action
     |
     +--> PatternToCypherCompiler --> Neo4j MATCH
     |
     +--> OclGuardCompiler/Evaluator
     |
     v
MatchBinder
     |
     v
RhsMaterializer
     |
     +--> USE runtime
     +--> Neo4j trace store
```

### 5.6. Đóng góp khoa học/kỹ thuật có thể trình bày

- Một schema Neo4j biểu diễn UML model/object graph phục vụ TGG.
- Một thuật toán chuyển TGG LHS pattern sang Cypher.
- Một cơ chế bind kết quả Cypher vào biến TGG.
- Một cơ chế traceability giúp tránh tạo trùng và hỗ trợ incremental transformation.
- Một prototype tích hợp với USE/RTL.
- Một đánh giá thực nghiệm trên nhiều ví dụ transformation.

### 5.7. Kế hoạch thực hiện đề xuất

Giai đoạn 1: Khảo sát và chuẩn hóa hiện trạng.

- Đọc lại RTL parser và model class.
- Chuẩn hóa schema Neo4j.
- Sửa các lỗi rõ ràng trong `NRuleCollectionManager`.

Giai đoạn 2: Xây dựng matching engine.

- Tách `PatternToCypherCompiler`.
- Hỗ trợ object pattern và binary link pattern.
- Bind Cypher result về biến TGG.

Giai đoạn 3: Xây dựng materialization engine.

- Sinh object target/correspondence theo từng match.
- Tạo link target/correspondence.
- Lưu trace node.

Giai đoạn 4: Hỗ trợ guard và action.

- Chuyển subset OCL sang Cypher hoặc evaluate bằng USE.
- Thực thi assignment attribute cơ bản.

Giai đoạn 5: Tích hợp UI và đánh giá.

- Thêm menu `Run TGG from Neo4j`.
- Hiển thị preview/log.
- Viết test và demo.
- So sánh kết quả với RTL transformation hiện có.

## 6. Demo nên chuẩn bị khi báo cáo với thầy

Demo tối thiểu:

1. Mở `Families.use`.
2. Parse `Persons.use` và `F2PForward.tgg`.
3. Tạo dữ liệu source bằng `input01.soil`.
4. Sync model/object sang Neo4j.
5. Chạy TGG Neo4j engine.
6. Quan sát USE xuất hiện object target/correspondence.
7. Kiểm tra Neo4j có trace transformation.

Demo mở rộng:

- thay đổi source object;
- chạy lại transformation;
- chứng minh không tạo trùng target object;
- hiển thị guard/action được xử lý đúng.

## 7. Kết luận

Phần TGG Neo4j hiện tại có nền tảng tốt nhưng chưa đủ để gọi là engine TGG hoàn chỉnh. Hạn chế lớn nhất là engine mới sinh Cypher từ pattern đơn giản, chưa xử lý guard/OCL, chưa xử lý assignment, chưa bind match result vào RHS, và chưa quản lý traceability.

Tuy vậy, chính các hạn chế này tạo thành một hướng khóa luận rõ ràng: xây dựng một TGG execution engine dựa trên Neo4j, có khả năng match bằng Cypher, materialize kết quả vào USE, và quản lý traceability phục vụ đồng bộ mô hình.

Nếu triển khai theo phạm vi hợp lý, đề tài có thể bắt đầu từ forward transformation với subset OCL giới hạn, sau đó mở rộng sang synchronization và co-evolution. Đây là hướng vừa có tính nghiên cứu, vừa có sản phẩm demo rõ ràng, phù hợp để phát triển thành khóa luận.
