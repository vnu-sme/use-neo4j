# OCL2Cypher examples

Thư mục này chỉ chứa các model, snapshot và corpus phục vụ trực tiếp cho
OCL2Cypher.

Đã bổ sung **33 snapshot độc lập** cho 11 bản metamodel USE: 27 snapshot có thể
đối chiếu bằng pipeline công cụ và 6 snapshot chỉ kiểm tra bằng USE. Mỗi bộ gồm
`regular.soil`, `mixed.soil`, `larger.soil` cùng tập ID vi phạm riêng. Xem
[danh mục và cách chạy](INSTANCES.md), [manifest](snapshot-manifest.tsv).

## Case studies

- `carrental`
- `case-study-oclcypher`
- `ER2REL`
- `families-to-persons-correctness-case-study`
- `medical`
- `medical-system-yte`
- `ocl2cypher-correctness-case-study`
- `uml-ocl-comprehensive-case-study`

## Input-adapter examples

| Thư mục | Mục đích |
|---|---|
| `shop-use-soil` | USE schema với snapshot SOIL dạng thường, truyền thống và block |
| `shop-uml-ocl` | Fixture UML/XMI cũ; plugin hiện không nạp định dạng này |
| `shop-ecore-xmi` | Fixture Ecore/XMI cũ; plugin hiện không nạp định dạng này |
| `advanced-adapters` | Qualifier và association class qua USE/SOIL |
| `stable-id-duplicate` | Hai object dùng cùng raw identity; kiểm tra hậu tố `__1` |
| `stable-id-collision` | Raw identity đã chiếm hậu tố; kiểm tra bỏ qua sang `__2` |
| `unsupported-operations` | Boundary case: operation declaration phải bị từ chối rõ ràng |
| `unsupported-prepost` | Boundary case: operation context/precondition phải bị từ chối rõ ràng |

Mỗi thư mục input-adapter là một fixture tự chứa. Hai boundary case cuối có
snapshot `empty.soil` riêng và được kỳ vọng trả về diagnostic, không phải compile
thành công.
