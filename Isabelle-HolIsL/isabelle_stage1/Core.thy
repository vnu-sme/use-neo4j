theory Core
  imports OCL
begin

section \<open>Resolved Core AST\<close>

text \<open>
  Surface OCL uses names.  Core uses keys resolved against M.  Consequently,
  once Front succeeds, later stages never guess which attribute or association
  a surface name meant.
\<close>

datatype collection_kind = CKSet | CKBag

datatype core_expr =
    CBool bool
  | CInt int
  | CReal real
  | CString string
  | CSelf cname
  | CVar vname
  | CLet vname core_expr core_expr
  | CIf core_expr core_expr core_expr
  | CAttr core_expr "cname \<times> aname"
  | CNavOne core_expr nav_ref
  | CNavMany collection_kind core_expr nav_ref
  | CScan cname
  | CUnary ocl_unop core_expr
  | CBinary ocl_binop core_expr core_expr
  | CSelect core_expr vname core_expr
  | CCollect core_expr vname core_expr
  | CForAll core_expr vname core_expr
  | CExists core_expr vname core_expr

record core_invariant =
  core_inv_name    :: inv_name
  core_inv_context :: cname
  core_inv_body    :: core_expr


section \<open>Name-resolving transformation\<close>

fun kind_of_collection_type :: "ocl_type \<Rightarrow> collection_kind" where
  "kind_of_collection_type (OTSet t) = CKSet"
| "kind_of_collection_type (OTBag t) = CKBag"
| "kind_of_collection_type t = CKSet"

text \<open>
  resolve_expr is used only after infer_type has accepted the same surface
  expression.  Fallback branches make it total as a HOL function; they are
  unreachable for successful Front results.
\<close>

fun resolve_expr :: "ocl_model \<Rightarrow> cname \<Rightarrow> type_env \<Rightarrow> ocl_expr \<Rightarrow> core_expr" where
  "resolve_expr M C \<Gamma> (OBool b) = CBool b"
| "resolve_expr M C \<Gamma> (OInt i) = CInt i"
| "resolve_expr M C \<Gamma> (OReal r) = CReal r"
| "resolve_expr M C \<Gamma> (OString s) = CString s"
| "resolve_expr M C \<Gamma> OSelf = CSelf C"
| "resolve_expr M C \<Gamma> (OVar x) = CVar x"
| "resolve_expr M C \<Gamma> (OLet x e body) =
     (case infer_type M C \<Gamma> e of
        Some t \<Rightarrow> CLet x (resolve_expr M C \<Gamma> e)
                         (resolve_expr M C ((x,t)#\<Gamma>) body)
      | None \<Rightarrow> CLet x (resolve_expr M C \<Gamma> e)
                       (resolve_expr M C \<Gamma> body))"
| "resolve_expr M C \<Gamma> (OIf c t e) =
     CIf (resolve_expr M C \<Gamma> c)
         (resolve_expr M C \<Gamma> t)
         (resolve_expr M C \<Gamma> e)"
| "resolve_expr M C \<Gamma> (OAttr e a) =
     (case infer_type M C \<Gamma> e of
        Some (OTObject c) \<Rightarrow>
          CAttr (resolve_expr M C \<Gamma> e)
                (attr_key_of (resolved_attribute M c a))
      | _ \<Rightarrow> CAttr (resolve_expr M C \<Gamma> e) (C,a))"
| "resolve_expr M C \<Gamma> (ONav e r) =
     (case infer_type M C \<Gamma> e of
        Some (OTObject c) \<Rightarrow>
          (let n = resolved_navigation M c r
           in if upper_is_one (nav_target_mult n)
              then CNavOne (resolve_expr M C \<Gamma> e) n
              else CNavMany CKSet (resolve_expr M C \<Gamma> e) n)
      | _ \<Rightarrow> CNavMany CKSet (resolve_expr M C \<Gamma> e)
                           (resolved_navigation M C r))"
| "resolve_expr M C \<Gamma> (OAllInstances c) = CScan c"
| "resolve_expr M C \<Gamma> (OUnary op e) = CUnary op (resolve_expr M C \<Gamma> e)"
| "resolve_expr M C \<Gamma> (OBinary op a b) =
     CBinary op (resolve_expr M C \<Gamma> a) (resolve_expr M C \<Gamma> b)"
| "resolve_expr M C \<Gamma> (OSelect src x body) =
     (case infer_type M C \<Gamma> src of
        Some (OTSet t) \<Rightarrow> CSelect (resolve_expr M C \<Gamma> src) x
                                  (resolve_expr M C ((x,t)#\<Gamma>) body)
      | Some (OTBag t) \<Rightarrow> CSelect (resolve_expr M C \<Gamma> src) x
                                  (resolve_expr M C ((x,t)#\<Gamma>) body)
      | _ \<Rightarrow> CSelect (resolve_expr M C \<Gamma> src) x (resolve_expr M C \<Gamma> body))"
| "resolve_expr M C \<Gamma> (OCollect src x body) =
     (case infer_type M C \<Gamma> src of
        Some (OTSet t) \<Rightarrow> CCollect (resolve_expr M C \<Gamma> src) x
                                    (resolve_expr M C ((x,t)#\<Gamma>) body)
      | Some (OTBag t) \<Rightarrow> CCollect (resolve_expr M C \<Gamma> src) x
                                    (resolve_expr M C ((x,t)#\<Gamma>) body)
      | _ \<Rightarrow> CCollect (resolve_expr M C \<Gamma> src) x (resolve_expr M C \<Gamma> body))"
| "resolve_expr M C \<Gamma> (OForAll src x body) =
     (case infer_type M C \<Gamma> src of
        Some (OTSet t) \<Rightarrow> CForAll (resolve_expr M C \<Gamma> src) x
                                   (resolve_expr M C ((x,t)#\<Gamma>) body)
      | Some (OTBag t) \<Rightarrow> CForAll (resolve_expr M C \<Gamma> src) x
                                   (resolve_expr M C ((x,t)#\<Gamma>) body)
      | _ \<Rightarrow> CForAll (resolve_expr M C \<Gamma> src) x (resolve_expr M C \<Gamma> body))"
| "resolve_expr M C \<Gamma> (OExists src x body) =
     (case infer_type M C \<Gamma> src of
        Some (OTSet t) \<Rightarrow> CExists (resolve_expr M C \<Gamma> src) x
                                   (resolve_expr M C ((x,t)#\<Gamma>) body)
      | Some (OTBag t) \<Rightarrow> CExists (resolve_expr M C \<Gamma> src) x
                                   (resolve_expr M C ((x,t)#\<Gamma>) body)
      | _ \<Rightarrow> CExists (resolve_expr M C \<Gamma> src) x (resolve_expr M C \<Gamma> body))"


definition front :: "ocl_model \<Rightarrow> ocl_invariant \<Rightarrow> core_invariant option" where
  "front M I =
     (if invariant_wf M I then
        Some \<lparr> core_inv_name = inv_key I,
               core_inv_context = inv_context I,
               core_inv_body = resolve_expr M (inv_context I) [] (inv_body I) \<rparr>
      else None)"


section \<open>Frontend safety\<close>

lemma front_success_implies_invariant_wf:
  assumes "front M I = Some CI"
  shows "invariant_wf M I"
  using assms unfolding front_def by (auto split: if_splits)

lemma front_rejects_invalid:
  assumes "\<not> invariant_wf M I"
  shows "front M I = None"
  using assms unfolding front_def by simp

lemma front_preserves_context:
  assumes "front M I = Some CI"
  shows "core_inv_context CI = inv_context I"
  using assms unfolding front_def by (auto split: if_splits)

lemma front_preserves_name:
  assumes "front M I = Some CI"
  shows "core_inv_name CI = inv_key I"
  using assms unfolding front_def by (auto split: if_splits)

end
