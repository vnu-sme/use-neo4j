theory Query
  imports Core
begin

section \<open>Representation-aware graph-query AST\<close>

text \<open>
  This is the formal query language generated from resolved OCL.  It is an AST,
  not raw Cypher text.  Therefore target-query syntax is well formed by
  construction.  A later serializer/realizer can turn this AST into textual
  read-only Cypher without changing name resolution or graph-direction choices.
\<close>

datatype traversal_direction = TraverseOut | TraverseIn

record query_nav =
  qn_relationship_type :: relationship_type
  qn_traversal         :: traversal_direction
  qn_target_label      :: graph_label
  qn_collection_kind   :: collection_kind


datatype q_expr =
    QBool bool
  | QInt int
  | QReal real
  | QString string
  | QSelf
  | QVar vname
  | QLet vname q_expr q_expr
  | QIf q_expr q_expr q_expr
  | QProperty q_expr property_key
  | QNavOne q_expr query_nav
  | QNavMany q_expr query_nav
  | QScan graph_label
  | QUnary ocl_unop q_expr
  | QBinary ocl_binop q_expr q_expr
  | QFilter q_expr vname q_expr
  | QCollect q_expr vname q_expr
  | QForAll3 q_expr vname q_expr
  | QExists3 q_expr vname q_expr

record graph_query =
  query_name          :: inv_name
  query_context_label :: graph_label
  query_predicate     :: q_expr
  query_return_id_key :: property_key


section \<open>Logical UML navigation to physical graph traversal\<close>

fun physical_traversal ::
  "edge_direction \<Rightarrow> logical_nav_direction \<Rightarrow> traversal_direction" where
  "physical_traversal Forward LeftToRight = TraverseOut"
| "physical_traversal Forward RightToLeft = TraverseIn"
| "physical_traversal Reverse LeftToRight = TraverseIn"
| "physical_traversal Reverse RightToLeft = TraverseOut"


definition lower_nav :: "rep_spec \<Rightarrow> nav_ref \<Rightarrow> collection_kind \<Rightarrow> query_nav" where
  "lower_nav \<Sigma> n k =
     \<lparr> qn_relationship_type = sigma_assoc_type \<Sigma> (nav_assoc_key n),
       qn_traversal =
         physical_traversal
           (sigma_assoc_direction \<Sigma> (nav_assoc_key n))
           (nav_direction n),
       qn_target_label = sigma_class_label \<Sigma> (nav_target_class n),
       qn_collection_kind = k
     \<rparr>"


section \<open>Core to graph-query lowering\<close>

fun lower_expr :: "rep_spec \<Rightarrow> core_expr \<Rightarrow> q_expr" where
  "lower_expr \<Sigma> (CBool b) = QBool b"
| "lower_expr \<Sigma> (CInt i) = QInt i"
| "lower_expr \<Sigma> (CReal r) = QReal r"
| "lower_expr \<Sigma> (CString s) = QString s"
| "lower_expr \<Sigma> (CSelf c) = QSelf"
| "lower_expr \<Sigma> (CVar x) = QVar x"
| "lower_expr \<Sigma> (CLet x e b) = QLet x (lower_expr \<Sigma> e) (lower_expr \<Sigma> b)"
| "lower_expr \<Sigma> (CIf c t e) = QIf (lower_expr \<Sigma> c) (lower_expr \<Sigma> t) (lower_expr \<Sigma> e)"
| "lower_expr \<Sigma> (CAttr e ak) = QProperty (lower_expr \<Sigma> e) (sigma_attr_key \<Sigma> ak)"
| "lower_expr \<Sigma> (CNavOne e n) = QNavOne (lower_expr \<Sigma> e) (lower_nav \<Sigma> n CKSet)"
| "lower_expr \<Sigma> (CNavMany k e n) = QNavMany (lower_expr \<Sigma> e) (lower_nav \<Sigma> n k)"
| "lower_expr \<Sigma> (CScan c) = QScan (sigma_class_label \<Sigma> c)"
| "lower_expr \<Sigma> (CUnary op e) = QUnary op (lower_expr \<Sigma> e)"
| "lower_expr \<Sigma> (CBinary op a b) = QBinary op (lower_expr \<Sigma> a) (lower_expr \<Sigma> b)"
| "lower_expr \<Sigma> (CSelect src x b) = QFilter (lower_expr \<Sigma> src) x (lower_expr \<Sigma> b)"
| "lower_expr \<Sigma> (CCollect src x b) = QCollect (lower_expr \<Sigma> src) x (lower_expr \<Sigma> b)"
| "lower_expr \<Sigma> (CForAll src x b) = QForAll3 (lower_expr \<Sigma> src) x (lower_expr \<Sigma> b)"
| "lower_expr \<Sigma> (CExists src x b) = QExists3 (lower_expr \<Sigma> src) x (lower_expr \<Sigma> b)"


definition lower_invariant :: "rep_spec \<Rightarrow> core_invariant \<Rightarrow> graph_query" where
  "lower_invariant \<Sigma> CI =
     \<lparr> query_name = core_inv_name CI,
       query_context_label = sigma_class_label \<Sigma> (core_inv_context CI),
       query_predicate = lower_expr \<Sigma> (core_inv_body CI),
       query_return_id_key = sigma_id_key \<Sigma>
     \<rparr>"


section \<open>Key transformation equations\<close>

lemma lower_attribute_rule[simp]:
  "lower_expr \<Sigma> (CAttr e ak) = QProperty (lower_expr \<Sigma> e) (sigma_attr_key \<Sigma> ak)"
  by simp

lemma lower_navigation_one_rule[simp]:
  "lower_expr \<Sigma> (CNavOne e n) = QNavOne (lower_expr \<Sigma> e) (lower_nav \<Sigma> n CKSet)"
  by simp

lemma lower_navigation_many_rule[simp]:
  "lower_expr \<Sigma> (CNavMany k e n) = QNavMany (lower_expr \<Sigma> e) (lower_nav \<Sigma> n k)"
  by simp

lemma lower_forall_rule[simp]:
  "lower_expr \<Sigma> (CForAll src x b) = QForAll3 (lower_expr \<Sigma> src) x (lower_expr \<Sigma> b)"
  by simp

lemma lower_exists_rule[simp]:
  "lower_expr \<Sigma> (CExists src x b) = QExists3 (lower_expr \<Sigma> src) x (lower_expr \<Sigma> b)"
  by simp

end
