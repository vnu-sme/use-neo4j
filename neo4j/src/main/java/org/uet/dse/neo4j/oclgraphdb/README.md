# OCLGraphDB Pattern-Based Cypher Planning

## 1. Ý tưởng chính

Mục tiêu của `oclgraphdb` là chuyển nhiều phần ngữ nghĩa OCL sang Cypher để Neo4j xử lý trực tiếp, thay vì lấy dữ liệu từ Neo4j về Java rồi lọc/tính toán bằng evaluator cũ.

Ý tưởng cốt lõi:

```text
Vẫn duyệt AST/expression tree theo kiểu đệ quy,
nhưng tại mỗi node sẽ thử detect xem subtree bắt đầu từ node đó
có khớp pattern đặc biệt nào không.
```

Nếu subtree khớp pattern tốt, planner sẽ "bẻ nhánh" tại đó:

```text
AST subtree
  -> Pattern match
  -> Pattern implementation riêng
  -> Sinh Cypher tối ưu cho nguyên subtree
```

Nếu không có pattern nào khớp, hệ thống quay về cách xử lý đệ quy/generic hơn. Nếu toàn bộ graph-db planner không xử lý được, dialog fallback về `oclite` Java evaluator cũ.

## 2. Vì sao cần pattern thay vì dịch từng node

Dịch từng node nhỏ thường sinh được Cypher đúng nhưng chưa tối ưu.

Ví dụ:

```ocl
Person.allInstances()->forAll(p | p.age >= 18)
```

Dịch từng node có thể tạo collection `allInstances`, rồi dùng list comprehension/iterator trong Cypher.

Nhưng pattern lớn hơn có thể nhận ra đây là dạng:

```text
Class.allInstances()->forAll(var | predicate)
```

và sinh query dạng kiểm tra violation:

```cypher
MATCH ...
WHERE NOT (predicate)
RETURN collect(violation)
```

Query dạng này thường tốt hơn vì Neo4j có thể lọc trực tiếp bằng `MATCH/WHERE`, không cần dựng nhiều collection trung gian.

## 3. Pipeline đề xuất

Luồng xử lý tổng quát:

```text
OCL text
  -> ANTLR parser
  -> ASTVisitor
  -> ASTNode tree
  -> OclGraphDbEvaluator
  -> SemanticResolver
  -> MultiPassPatternPlanner
  -> PatternGraph
  -> MultiPassCypherCompiler
  -> CypherPlan
  -> CypherExecutor
  -> Result
```

Nếu multi-pass compiler không lower được root expression, evaluator fallback về compatibility path:

```text
PatternPlanner
  -> PlanCandidate
  -> CypherPlan
  -> CypherExecutor
```

Nếu cả hai graph-db paths đều không xử lý được, `OclEvaluatorDialog` fallback về `oclite` Java evaluator cũ.

Trong code hiện tại:

- `OclGraphDbEvaluator`: entry point mới, thử chạy bằng Cypher trước.
- `SemanticResolver`: kiểm tra tên class/object và resolve property theo metamodel Neo4j.
- `PatternRegistry`: đăng ký các pattern có sẵn hoặc custom.
- `PatternPlanner`: thử match pattern, chấm điểm, chọn candidate tốt nhất.
- `OclPattern`: interface cho từng pattern.
- `PlanCandidate`: kết quả match/compile của một pattern, có score.
- `multipass.MultiPassPatternPlanner`: chạy nhiều vòng pattern passes và tạo `PatternGraph`.
- `multipass.PatternInstance`: một pattern đã được nhận dạng trên AST/subtree, có kind, score, metadata, children.
- `multipass.PatternPass`: một vòng gom pattern, ví dụ primitive, semantic property, compare, iterator, terminal.
- `multipass.impl.PatternImplementation`: implementation sinh `CypherExpression` từ pattern trừu tượng.
- `multipass.impl.MultiPassCypherCompiler`: lower `PatternInstance` tốt nhất thành `CypherPlan`.
- `CypherExpression`: biểu thức Cypher kèm prelude clauses.
- `CypherPlan`: query Cypher hoàn chỉnh.
- `CypherExecutor`: chạy Cypher và đọc result.
- `analysis.OclAnalysisService`: phân tích AST, tính độ sâu, số node, pattern candidates và score để hiển thị trong GUI.

## 3.1. Analyze/visualize OCL trong GUI

`OclEvaluatorDialog` có nút `Analyze`. Luồng này chỉ parse và phân tích câu OCL, không thực thi kết quả OCL chính.

Kết quả hiển thị trong tab `Analysis` gồm:

- Tổng số AST node.
- Độ sâu AST lớn nhất.
- Số node có pattern match.
- Tổng số pattern candidates.
- Tree AST dạng Swing `JTree`.
- Với từng AST node, các pattern candidates tốt nhất theo planner, gồm pattern name, expression kind, score, covered nodes, cost và Cypher expression preview.
- Với từng AST node, các `multi-pass patterns` được gom qua nhiều vòng, ví dụ `ATTRIBUTE_ACCESS`, `ATTRIBUTE_COMPARE`, `FILTER`, `FILTERED_TERMINAL`, `CONTEXT_INVARIANT`.
- Số vòng multi-pass đã chạy, max pass được cấu hình, và trạng thái fixpoint.

GUI có field `pattern passes (0 = auto)`. Nếu nhập `0`, hệ thống tự chọn số vòng theo độ sâu/số node AST và dừng khi đạt fixpoint. Nếu nhập số dương, số đó là giới hạn vòng chạy.

Điểm này dùng chung `PatternPlanner`, `MultiPassPatternPlanner` và `SemanticResolver`, nên phần visualize phản ánh cả path compile hiện tại lẫn pattern graph nhiều vòng đang hướng tới optimizer.

Các pass mặc định:

- `PrimitivePatternPass`: tạo pattern cơ sở như `VARIABLE`, `LITERAL`, `PROPERTY_ACCESS`, `BINARY`, `ITERATOR`, đồng thời gắn legacy `CYPHER_CANDIDATE`.
- `SemanticPropertyPass`: gom `PROPERTY_ACCESS` thành `ATTRIBUTE_ACCESS` hoặc `NAVIGATION_ACCESS` dựa trên metamodel.
- `CompareCompositionPass`: gom binary comparison thành `COMPARE`, `ATTRIBUTE_COMPARE`, `NAVIGATION_COMPARE`; gom logical operator thành `LOGICAL_COMPOSITION`; gom `not` thành `NEGATION`.
- `IteratorCompositionPass`: gom source + body thành `FILTER` hoặc `QUANTIFIER`; source có thể là `ALL_INSTANCES_SOURCE`, `NAVIGATION_SOURCE` hoặc `FILTER` từ vòng trước.
- `TerminalCompositionPass`: gom `select/reject + size/isEmpty/isNotEmpty` thành `FILTERED_TERMINAL`.
- `RootCompositionPass`: gom pattern root thành `SCALAR_QUERY` hoặc `CONTEXT_INVARIANT`.

Các implementation trừu tượng hiện có:

- `RootPatternImplementation`: lower `SCALAR_QUERY` và `CONTEXT_INVARIANT`.
- `AttributeAccessImplementation`: lower attribute access đã resolve semantic.
- `NavigationAccessImplementation`: lower navigation access đã resolve semantic.
- `ComparePatternImplementation`: lower `COMPARE`, `ATTRIBUTE_COMPARE`, `NAVIGATION_COMPARE`.
- `LogicalPatternImplementation`: lower `and/or/implies`.
- `NegationPatternImplementation`: lower `not`.
- `CollectionSourceImplementation`: lower `Class.allInstances()`.
- `FilterPatternImplementation`: lower `select/reject` thành Cypher collection expression.
- `QuantifierPatternImplementation`: lower `forAll/exists/one/any`; `forAll/exists` ưu tiên `NOT EXISTS`/`EXISTS` khi source có thể match trực tiếp.
- `FilteredTerminalImplementation`: lower `select/reject + size/isEmpty/isNotEmpty` bằng subquery count khi source match trực tiếp.
- `LegacyPatternImplementation`: compatibility implementation cuối cùng, gọi lại `PatternPlanner` cũ cho subtree chưa có implementation trừu tượng.

## 3.2. Các mode OCL cần hỗ trợ

Evaluator cần xử lý ba kiểu input:

### Query thường không có context

Ví dụ:

```ocl
p1.age > 18
```

```ocl
Person.allInstances()->select(p | p.age > 18)->size()
```

Mode này không có `self` mặc định. Các biến như `p1` được resolve như object id trong Neo4j, còn `Person` được resolve như class.

### Context invariant

Ví dụ:

```ocl
context Person inv Adult:
  self.age >= 18
```

Mode này tự bind:

```text
self = từng instance của context class Person
```

Query sinh ra sẽ kiểm tra tất cả instance và trả summary `SUCCESS/FAILURE`.

### Query thường có self được bind từ UI/caller

Ví dụ người dùng nhập:

```ocl
self.age > 18
```

Vì không có `context`, evaluator không thể tự biết `self` là object nào. `OclEvaluatorDialog` có ô `self object id (optional)` để bind `self` vào một object cụ thể.

API tương ứng:

```java
graphDbEvaluator.tryEvaluate(ast, modelName, "p1");
```

hoặc:

```java
OclGraphDbEvaluationOptions options = OclGraphDbEvaluationOptions.builder()
    .bindObject("self", "p1")
    .build();

graphDbEvaluator.tryEvaluate(ast, modelName, options);
```

Nếu không truyền binding mà expression thường vẫn dùng `self`, graph-db planner sẽ không compile `self` như biến tự do. Khi đó evaluator fallback về `oclite`; nếu fallback cũng không có binding thì kết quả có thể là `null`/không evaluate được.

## 4. Cơ chế bẻ nhánh subtree

Tại mỗi AST node, planner làm:

```text
plan(node):
  candidates = []

  for pattern in registry:
    if pattern.supports(node):
      candidates += pattern.match(node)

  sort candidates by score
  return best candidate
```

Điểm quan trọng: `pattern.match(node)` có thể consume cả subtree lớn, không bắt buộc chỉ xử lý node hiện tại.

Ví dụ một pattern có thể match nguyên subtree:

```text
ASTIterator(
  source = ASTMethodCall(ASTVar("Person"), "allInstances"),
  operation = "forAll",
  iteratorName = "p",
  body = ASTBinary(ASTProperty(ASTVar("p"), "age"), ">=", 18)
)
```

Pattern implementation có thể capture:

```text
className = Person
iteratorVar = p
attribute = age
operator = >=
literal = 18
```

rồi sinh query riêng cho toàn bộ subtree.

## 5. Pattern generic và pattern chuyên biệt

Nên có hai nhóm pattern.

### Pattern generic

Các pattern này xử lý các cấu trúc cơ bản:

- Literal
- Variable
- Property/navigation
- Binary comparison
- Logical `and/or/implies`
- `not`
- Collection operation
- Iterator
- `allInstances`
- `oclIsTypeOf/oclIsKindOf`

Ưu điểm: cover rộng.

Nhược điểm: đôi khi query chưa tối ưu nhất vì ghép từ nhiều expression nhỏ.

### Pattern chuyên biệt

Các pattern này match subtree lớn:

- `Class.allInstances()->forAll(var | predicate)`
- `Class.allInstances()->exists(var | predicate)`
- `collection->select(var | predicate)->size()`
- `navigation->size() > n`
- `navigation->isEmpty()`
- `navigation->includes(obj)`
- `attribute comparison`
- `forAll` dạng no-violation
- `exists` dạng existence query

Ưu điểm: sinh Cypher sát ngữ nghĩa hơn, ít collection trung gian hơn.

Nhược điểm: cần implement detect và compile riêng.

## 6. Scoring pattern

Khi nhiều pattern cùng khớp một subtree, planner không nên chọn pattern đầu tiên. Nên dùng score.

Một công thức tham khảo:

```text
score =
    semanticExactness
  + consumedNodeCount
  + cypherPushdownLevel
  - estimatedCost
  - fallbackPenalty
```

Ý nghĩa:

- `semanticExactness`: pattern có giữ đúng ngữ nghĩa OCL không.
- `consumedNodeCount`: pattern consume càng nhiều subtree càng có khả năng tối ưu.
- `cypherPushdownLevel`: càng đẩy được nhiều logic xuống Neo4j càng tốt.
- `estimatedCost`: pattern tạo query quá nặng thì bị trừ điểm.
- `fallbackPenalty`: pattern gần đúng hoặc cần Java hậu xử lý thì bị trừ điểm.

Ví dụ cùng một OCL:

```ocl
Person.allInstances()->forAll(p | p.age >= 18)
```

Có thể match bởi:

```text
IteratorPattern
AllInstancesIteratorPattern
AllInstancesForAllAttributeComparisonPattern
```

Pattern cuối nên thắng vì nó hiểu cả cấu trúc lớn và có thể sinh query tốt hơn.

## 7. Ví dụ sinh Cypher trực tiếp

### Ví dụ 1: Invariant attribute comparison

OCL:

```ocl
context Person inv Adult:
  self.age >= 18
```

Ý nghĩa pattern:

```text
self.attribute OP literal
```

Cypher dạng rút gọn:

```cypher
MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(target {name: $contextClass})
MATCH (cls)-[:Extends*0..]->(target)
OPTIONAL MATCH (self)-[:ObjectInstanceOf]->(cls)
WITH [instance IN collect(DISTINCT self) WHERE instance IS NOT NULL] AS instances
CALL {
  WITH instances
  UNWIND instances AS self
  WHERE NOT (
    toFloat(replace(replace(toString(
      head([(self)-[:ObjectHasAttribute]->(attr:AttributeValue)
            WHERE attr.name ENDS WITH $attrSuffix | attr.value])
    ), "'", ""), "#", "")) >= $lit
  )
  RETURN collect(self.use_id) AS violations
}
RETURN size(instances) AS total, violations
```

Java chỉ nhận summary `total` và `violations`, không lọc từng object ở Java.

### Ví dụ 2: allInstances + forAll

OCL:

```ocl
Person.allInstances()->forAll(p | p.age >= 18)
```

Pattern chuyên biệt mong muốn:

```text
Class.allInstances()->forAll(var | predicate)
```

Cypher tối ưu hơn có thể sinh:

```cypher
MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(target {name: $className})
MATCH (cls)-[:Extends*0..]->(target)
MATCH (p)-[:ObjectInstanceOf]->(cls)
MATCH (p)-[:ObjectHasAttribute]->(age:AttributeValue)
WHERE age.name ENDS WITH $ageSuffix
WITH collect(DISTINCT p) AS allPersons,
     collect(DISTINCT CASE
       WHEN toFloat(replace(replace(toString(age.value), "'", ""), "#", "")) < 18
       THEN p.use_id
       ELSE null
     END) AS rawViolations
RETURN size(allPersons) AS total,
       [v IN rawViolations WHERE v IS NOT NULL] AS violations
```

Điểm chính: Neo4j tự lọc violation. Java không duyệt từng `Person`.

### Ví dụ 3: navigation size

OCL:

```ocl
context Family inv HasMembers:
  self.members->size() > 0
```

Pattern:

```text
self.navigation->size() OP literal
```

Cypher dạng rút gọn:

```cypher
MATCH ...
WHERE NOT (
  size([
    (self)-[rel]-(target)
    WHERE type(rel) STARTS WITH 'Link'
      AND (
        startNode(rel) = self AND (rel.targetRole = $role OR rel.name = $role)
        OR
        endNode(rel) = self AND (rel.sourceRole = $role OR rel.name = $role)
      )
    | target
  ]) > 0
)
RETURN ...
```

Không cần lấy `self.members` lên Java để đếm.

### Ví dụ 4: exists trên navigation

OCL:

```ocl
context Family inv HasAdult:
  self.members->exists(m | m.age >= 18)
```

Pattern chuyên biệt mong muốn:

```text
navigation->exists(var | attr comparison)
```

Cypher tốt hơn có thể dùng `EXISTS`:

```cypher
WHERE EXISTS {
  MATCH (self)-[rel]-(m)
  MATCH (m)-[:ObjectHasAttribute]->(age:AttributeValue)
  WHERE type(rel) STARTS WITH 'Link'
    AND age.name ENDS WITH $ageSuffix
    AND toFloat(replace(replace(toString(age.value), "'", ""), "#", "")) >= 18
}
```

Pattern dạng này tốt hơn list comprehension vì Neo4j có thể dừng sớm khi tìm thấy một match.

## 7.1. Coverage iterator/filter hiện tại

Các iterator/filter quan trọng đã có pattern chạy ở Cypher:

| OCL shape | Pattern | Cypher strategy |
| --- | --- | --- |
| `C.allInstances()->forAll(x | P)` | `AllInstancesIteratorPattern` | `NOT EXISTS { MATCH x ... WHERE NOT P }` |
| `C.allInstances()->exists(x | P)` | `AllInstancesIteratorPattern` | `EXISTS { MATCH x ... WHERE P }` |
| `self.nav->forAll(x | P)` | `NavigationIteratorPattern` | `NOT EXISTS { MATCH self-nav-x ... WHERE NOT P }` |
| `self.nav->exists(x | P)` | `NavigationIteratorPattern` | `EXISTS { MATCH self-nav-x ... WHERE P }` |
| `C.allInstances()->select(x | P)->size()` | `AllInstancesSelectTerminalPattern` | subquery `count(DISTINCT x)` |
| `C.allInstances()->select(x | P)->isEmpty()` | `AllInstancesSelectTerminalPattern` | subquery `count(DISTINCT x) = 0` |
| `C.allInstances()->select(x | P)->isNotEmpty()` | `AllInstancesSelectTerminalPattern` | subquery `count(DISTINCT x) > 0` |
| `self.nav->select(x | P)->size()` | `NavigationSelectTerminalPattern` | subquery `count(DISTINCT x)` |
| `self.nav->select(x | P)->isEmpty()` | `NavigationSelectTerminalPattern` | subquery `count(DISTINCT x) = 0` |
| `self.nav->select(x | P)->isNotEmpty()` | `NavigationSelectTerminalPattern` | subquery `count(DISTINCT x) > 0` |
| Generic `select/reject/collect/forAll/exists/one/any` | `IteratorPattern` | Cypher list predicate/comprehension |

Các pattern chuyên biệt dùng semantic resolver để chỉ match navigation khi property thật sự là relationship trong metamodel. Generic `PropertyPattern` cũng resolve `dr1.age` theo class của `dr1`: nếu `age` là `Attribute` thì sinh query đọc `ObjectHasAttribute`; nếu `age` là role navigation thì sinh query match link. Iterator cũng truyền type cho biến lặp khi source là `C.allInstances()`, `self.nav`, hoặc `select/reject` trên các collection đó, nên body như `p.age` tiếp tục resolve theo metamodel. Khi type nguồn đã biết nhưng property không tồn tại trong metamodel, planner không sinh Cypher đoán mò và để evaluator fallback. Heuristic chỉ còn dùng cho subtree chưa xác định được type nguồn.

## 8. Khi nào vẫn fallback về Java

Fallback xảy ra khi:

- Không có pattern nào match được subtree/root expression.
- Pattern match nhưng không đảm bảo semantic.
- Cypher sinh ra lỗi runtime.
- Cấu trúc OCL chưa support trong grammar hoặc planner.

Khi fallback, `OclEvaluatorDialog` dùng lại `ExpressionBinder` + `ExecutionContext` cũ của `oclite`.

Như vậy:

```text
Pattern match được  -> Neo4j xử lý bằng Cypher
Pattern không match -> Java evaluator cũ xử lý
```

## 9. Cách custom/mở rộng pattern

Thêm một pattern mới bằng cách implement:

```java
public class MyPattern implements OclPattern {
  @Override
  public String name() {
    return "my-pattern";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTIterator;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    // 1. Kiểm tra shape subtree
    // 2. Capture class/attribute/navigation/operator/literal
    // 3. Sinh CypherExpression hoặc CypherPlan fragment
    // 4. Trả PlanCandidate với score phù hợp
  }
}
```

Đăng ký vào registry:

```java
PatternRegistry registry = PatternRegistry.defaults()
    .register(new MyPattern());
```

Hoặc inject registry riêng:

```java
OclGraphDbEvaluator evaluator = new OclGraphDbEvaluator(registry);
```

## 10. Hướng phát triển tiếp theo

Nên phát triển theo thứ tự:

1. Giữ generic pattern để cover rộng.
2. Thêm specialized pattern cho các cấu trúc OCL phổ biến.
3. Tách rõ phase `match` và phase `compile` nếu pattern ngày càng phức tạp.
4. Thêm `PatternMatch` object chứa captures thay vì compile ngay trong `match`.
5. Thêm cost model tốt hơn dựa trên:
   - số node subtree consume,
   - số prelude clause,
   - có dùng collection trung gian không,
   - có dùng `EXISTS`/`NOT EXISTS` được không,
   - có đẩy predicate xuống `WHERE` được không.
6. Thêm logging/debug để biết expression nào dùng pattern nào.

## 11. Kết luận

Phương pháp này là một dạng hybrid:

```text
Recursive AST traversal
+ subtree pattern matching
+ query planning/scoring
+ Cypher code generation
+ Java fallback
```

Ưu điểm lớn nhất là có thể tăng dần mức tối ưu:

- Ban đầu dùng generic pattern để sinh Cypher cơ bản.
- Sau đó thêm pattern chuyên biệt cho các OCL hay gặp.
- Mỗi pattern mới có thể đẩy thêm phần ngữ nghĩa OCL xuống Neo4j.
- Java evaluator cũ vẫn giữ vai trò fallback để hệ thống không bị gãy khi gặp OCL chưa support.
