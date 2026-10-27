theory OCL
  imports Build
begin

section \<open>Surface OCL syntax\<close>

text \<open>
  Textual OCL is parsed outside this theory into the datatype below.  Therefore
  grammatical syntax is guaranteed by construction of an ocl_expr value.

  Model-dependent validity is NOT guaranteed by syntax.  It is checked below:
  class names, attributes, association roles, variable scopes, multiplicities,
  and operator types must all agree with the concrete class model M.
\<close>

type_synonym vname = string

datatype ocl_type =
    OTBool
  | OTInt
  | OTReal
  | OTString
  | OTObject cname
  | OTSet ocl_type
  | OTBag ocl_type

datatype ocl_unop =
    UNot
  | UNeg
  | UIsUndefined
  | USize
  | UIsEmpty
  | UNotEmpty
  | USum

datatype ocl_binop =
    BAnd | BOr | BImplies
  | BEq | BNeq
  | BLt | BLe | BGt | BGe
  | BAdd | BSub | BMul

datatype ocl_expr =
    OBool bool
  | OInt int
  | OReal real
  | OString string
  | OSelf
  | OVar vname
  | OLet vname ocl_expr ocl_expr
  | OIf ocl_expr ocl_expr ocl_expr
  | OAttr ocl_expr aname
  | ONav ocl_expr role_name
  | OAllInstances cname
  | OUnary ocl_unop ocl_expr
  | OBinary ocl_binop ocl_expr ocl_expr
  | OSelect ocl_expr vname ocl_expr
  | OCollect ocl_expr vname ocl_expr
  | OForAll ocl_expr vname ocl_expr
  | OExists ocl_expr vname ocl_expr


type_synonym ocl_model = "ocl_expr class_model"
type_synonym ocl_invariant = "ocl_expr invariant_decl"
type_synonym type_env = "(vname \<times> ocl_type) list"


section \<open>Primitive type correspondence\<close>

fun ocl_type_of_model_type :: "model_type \<Rightarrow> ocl_type" where
  "ocl_type_of_model_type (Primitive TBoolean) = OTBool"
| "ocl_type_of_model_type (Primitive TInteger) = OTInt"
| "ocl_type_of_model_type (Primitive TReal)    = OTReal"
| "ocl_type_of_model_type (Primitive TString)  = OTString"
| "ocl_type_of_model_type (Reference c)        = OTObject c"

fun is_numeric_type :: "ocl_type \<Rightarrow> bool" where
  "is_numeric_type OTInt = True"
| "is_numeric_type OTReal = True"
| "is_numeric_type _ = False"

fun collection_element_type :: "ocl_type \<Rightarrow> ocl_type option" where
  "collection_element_type (OTSet t) = Some t"
| "collection_element_type (OTBag t) = Some t"
| "collection_element_type _ = None"

fun is_collection_type :: "ocl_type \<Rightarrow> bool" where
  "is_collection_type (OTSet _) = True"
| "is_collection_type (OTBag _) = True"
| "is_collection_type _ = False"

fun collection_same_kind :: "ocl_type \<Rightarrow> ocl_type \<Rightarrow> ocl_type option" where
  "collection_same_kind (OTSet _) t = Some (OTSet t)"
| "collection_same_kind (OTBag _) t = Some (OTBag t)"
| "collection_same_kind _ _ = None"

fun upper_is_one :: "multiplicity \<Rightarrow> bool" where
  "upper_is_one m =
     (case mult_upper m of
        Finite u \<Rightarrow> u \<le> 1
      | Unlimited \<Rightarrow> False)"

fun navigation_result_type :: "cname \<Rightarrow> multiplicity \<Rightarrow> ocl_type" where
  "navigation_result_type c m =
     (if upper_is_one m then OTObject c else OTSet (OTObject c))"


section \<open>Environment lookup\<close>

fun env_lookup :: "type_env \<Rightarrow> vname \<Rightarrow> ocl_type option" where
  "env_lookup [] x = None"
| "env_lookup ((y,t)#\<Gamma>) x = (if x = y then Some t else env_lookup \<Gamma> x)"


section \<open>Resolving attributes against M\<close>

text \<open>
  An OCL attribute occurrence e.a is accepted only when e has an object type
  and exactly one attribute named a is applicable to that receiver type.  This
  explicitly rejects ambiguous inherited declarations.
\<close>

definition matching_attributes ::
  "ocl_model \<Rightarrow> cname \<Rightarrow> aname \<Rightarrow> attribute_decl set" where
  "matching_attributes M c a =
     {d\<in>model_attributes M. attr_name d = a \<and> applicable_attribute M c d}"

definition attribute_resolves ::
  "ocl_model \<Rightarrow> cname \<Rightarrow> aname \<Rightarrow> bool" where
  "attribute_resolves M c a \<longleftrightarrow> card (matching_attributes M c a) = 1"

definition resolved_attribute ::
  "ocl_model \<Rightarrow> cname \<Rightarrow> aname \<Rightarrow> attribute_decl" where
  "resolved_attribute M c a = (THE d. d \<in> matching_attributes M c a)"


section \<open>Resolving association-role navigation against M\<close>

datatype logical_nav_direction = LeftToRight | RightToLeft

record nav_ref =
  nav_assoc_key    :: assoc_name
  nav_role_key     :: role_name
  nav_source_class :: cname
  nav_target_class :: cname
  nav_target_mult  :: multiplicity
  nav_direction    :: logical_nav_direction

text \<open>
  For an association A -- B, navigation from A using the role declared on the
  B end reaches B, and vice versa.  Receiver subtypes are admitted through
  subclass_of.  The surface role is valid only when exactly one candidate is
  found in M.
\<close>

definition navigation_candidates ::
  "ocl_model \<Rightarrow> cname \<Rightarrow> role_name \<Rightarrow> (association_decl \<times> logical_nav_direction) set" where
  "navigation_candidates M c r =
     ({(a,LeftToRight) | a.
          a \<in> model_associations M \<and>
          subclass_of M c (end_class (assoc_left a)) \<and>
          end_role (assoc_right a) = r}
      \<union>
      {(a,RightToLeft) | a.
          a \<in> model_associations M \<and>
          subclass_of M c (end_class (assoc_right a)) \<and>
          end_role (assoc_left a) = r})"

definition navigation_resolves ::
  "ocl_model \<Rightarrow> cname \<Rightarrow> role_name \<Rightarrow> bool" where
  "navigation_resolves M c r \<longleftrightarrow> card (navigation_candidates M c r) = 1"

definition resolved_navigation ::
  "ocl_model \<Rightarrow> cname \<Rightarrow> role_name \<Rightarrow> nav_ref" where
  "resolved_navigation M c r =
     (let p = (THE p. p \<in> navigation_candidates M c r);
          a = fst p;
          d = snd p
      in case d of
           LeftToRight \<Rightarrow>
             \<lparr> nav_assoc_key = assoc_key a,
               nav_role_key = r,
               nav_source_class = end_class (assoc_left a),
               nav_target_class = end_class (assoc_right a),
               nav_target_mult = end_mult (assoc_right a),
               nav_direction = LeftToRight \<rparr>
         | RightToLeft \<Rightarrow>
             \<lparr> nav_assoc_key = assoc_key a,
               nav_role_key = r,
               nav_source_class = end_class (assoc_right a),
               nav_target_class = end_class (assoc_left a),
               nav_target_mult = end_mult (assoc_left a),
               nav_direction = RightToLeft \<rparr>)"


section \<open>Type compatibility\<close>

fun same_scalar_type :: "ocl_type \<Rightarrow> ocl_type \<Rightarrow> bool" where
  "same_scalar_type OTBool OTBool = True"
| "same_scalar_type OTInt OTInt = True"
| "same_scalar_type OTReal OTReal = True"
| "same_scalar_type OTString OTString = True"
| "same_scalar_type (OTObject c) (OTObject d) = (c = d)"
| "same_scalar_type _ _ = False"

fun numeric_result :: "ocl_type \<Rightarrow> ocl_type \<Rightarrow> ocl_type option" where
  "numeric_result OTInt OTInt = Some OTInt"
| "numeric_result OTInt OTReal = Some OTReal"
| "numeric_result OTReal OTInt = Some OTReal"
| "numeric_result OTReal OTReal = Some OTReal"
| "numeric_result _ _ = None"

fun branch_join :: "ocl_type \<Rightarrow> ocl_type \<Rightarrow> ocl_type option" where
  "branch_join OTBool OTBool = Some OTBool"
| "branch_join OTInt OTInt = Some OTInt"
| "branch_join OTInt OTReal = Some OTReal"
| "branch_join OTReal OTInt = Some OTReal"
| "branch_join OTReal OTReal = Some OTReal"
| "branch_join OTString OTString = Some OTString"
| "branch_join (OTObject c) (OTObject d) = (if c=d then Some (OTObject c) else None)"
| "branch_join (OTSet a) (OTSet b) = (if a=b then Some (OTSet a) else None)"
| "branch_join (OTBag a) (OTBag b) = (if a=b then Some (OTBag a) else None)"
| "branch_join _ _ = None"


section \<open>Model-dependent OCL type checking\<close>

text \<open>
  infer_type is the admission checker for surface OCL.  Returning None means
  that the expression is invalid for M (unknown variable/class/attribute/role,
  ambiguous resolution, or an ill-typed operator/iterator).
\<close>

fun infer_type :: "ocl_model \<Rightarrow> cname \<Rightarrow> type_env \<Rightarrow> ocl_expr \<Rightarrow> ocl_type option" where
  "infer_type M C \<Gamma> (OBool b) = Some OTBool"
| "infer_type M C \<Gamma> (OInt i) = Some OTInt"
| "infer_type M C \<Gamma> (OReal r) = Some OTReal"
| "infer_type M C \<Gamma> (OString s) = Some OTString"
| "infer_type M C \<Gamma> OSelf = (if C \<in> class_names M then Some (OTObject C) else None)"
| "infer_type M C \<Gamma> (OVar x) = env_lookup \<Gamma> x"
| "infer_type M C \<Gamma> (OLet x e body) =
     (case infer_type M C \<Gamma> e of
        None \<Rightarrow> None
      | Some t \<Rightarrow> infer_type M C ((x,t)#\<Gamma>) body)"
| "infer_type M C \<Gamma> (OIf c t e) =
     (case infer_type M C \<Gamma> c of
        Some OTBool \<Rightarrow>
          (case (infer_type M C \<Gamma> t, infer_type M C \<Gamma> e) of
             (Some tt, Some te) \<Rightarrow> branch_join tt te
           | _ \<Rightarrow> None)
      | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OAttr e a) =
     (case infer_type M C \<Gamma> e of
        Some (OTObject c) \<Rightarrow>
          (if attribute_resolves M c a then
             Some (ocl_type_of_model_type (attr_type (resolved_attribute M c a)))
           else None)
      | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (ONav e r) =
     (case infer_type M C \<Gamma> e of
        Some (OTObject c) \<Rightarrow>
          (if navigation_resolves M c r then
             (let n = resolved_navigation M c r
              in Some (navigation_result_type
                         (nav_target_class n) (nav_target_mult n)))
           else None)
      | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OAllInstances c) =
     (if c \<in> class_names M then Some (OTSet (OTObject c)) else None)"
| "infer_type M C \<Gamma> (OUnary UNot e) =
     (if infer_type M C \<Gamma> e = Some OTBool then Some OTBool else None)"
| "infer_type M C \<Gamma> (OUnary UNeg e) =
     (case infer_type M C \<Gamma> e of Some OTInt \<Rightarrow> Some OTInt | Some OTReal \<Rightarrow> Some OTReal | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OUnary UIsUndefined e) =
     (case infer_type M C \<Gamma> e of None \<Rightarrow> None | Some t \<Rightarrow> Some OTBool)"
| "infer_type M C \<Gamma> (OUnary USize e) =
     (case infer_type M C \<Gamma> e of Some (OTSet t) \<Rightarrow> Some OTInt | Some (OTBag t) \<Rightarrow> Some OTInt | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OUnary UIsEmpty e) =
     (case infer_type M C \<Gamma> e of Some (OTSet t) \<Rightarrow> Some OTBool | Some (OTBag t) \<Rightarrow> Some OTBool | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OUnary UNotEmpty e) =
     (case infer_type M C \<Gamma> e of Some (OTSet t) \<Rightarrow> Some OTBool | Some (OTBag t) \<Rightarrow> Some OTBool | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OUnary USum e) =
     (case infer_type M C \<Gamma> e of
        Some (OTSet OTInt) \<Rightarrow> Some OTInt
      | Some (OTBag OTInt) \<Rightarrow> Some OTInt
      | Some (OTSet OTReal) \<Rightarrow> Some OTReal
      | Some (OTBag OTReal) \<Rightarrow> Some OTReal
      | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OBinary op a b) =
     (case (infer_type M C \<Gamma> a, infer_type M C \<Gamma> b) of
        (Some ta, Some tb) \<Rightarrow>
          (case op of
             BAnd \<Rightarrow> if ta=OTBool \<and> tb=OTBool then Some OTBool else None
           | BOr \<Rightarrow> if ta=OTBool \<and> tb=OTBool then Some OTBool else None
           | BImplies \<Rightarrow> if ta=OTBool \<and> tb=OTBool then Some OTBool else None
           | BEq \<Rightarrow> if ta=tb \<or> (is_numeric_type ta \<and> is_numeric_type tb) then Some OTBool else None
           | BNeq \<Rightarrow> if ta=tb \<or> (is_numeric_type ta \<and> is_numeric_type tb) then Some OTBool else None
           | BLt \<Rightarrow> if numeric_result ta tb \<noteq> None \<or> (ta=OTString \<and> tb=OTString) then Some OTBool else None
           | BLe \<Rightarrow> if numeric_result ta tb \<noteq> None \<or> (ta=OTString \<and> tb=OTString) then Some OTBool else None
           | BGt \<Rightarrow> if numeric_result ta tb \<noteq> None \<or> (ta=OTString \<and> tb=OTString) then Some OTBool else None
           | BGe \<Rightarrow> if numeric_result ta tb \<noteq> None \<or> (ta=OTString \<and> tb=OTString) then Some OTBool else None
           | BAdd \<Rightarrow> numeric_result ta tb
           | BSub \<Rightarrow> numeric_result ta tb
           | BMul \<Rightarrow> numeric_result ta tb)
      | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OSelect src x body) =
     (case infer_type M C \<Gamma> src of
        Some (OTSet t) \<Rightarrow>
          (if infer_type M C ((x,t)#\<Gamma>) body = Some OTBool then Some (OTSet t) else None)
      | Some (OTBag t) \<Rightarrow>
          (if infer_type M C ((x,t)#\<Gamma>) body = Some OTBool then Some (OTBag t) else None)
      | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OCollect src x body) =
     (case infer_type M C \<Gamma> src of
        Some (OTSet t) \<Rightarrow>
          (case infer_type M C ((x,t)#\<Gamma>) body of
             Some u \<Rightarrow> if is_collection_type u then None else Some (OTBag u)
           | None \<Rightarrow> None)
      | Some (OTBag t) \<Rightarrow>
          (case infer_type M C ((x,t)#\<Gamma>) body of
             Some u \<Rightarrow> if is_collection_type u then None else Some (OTBag u)
           | None \<Rightarrow> None)
      | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OForAll src x body) =
     (case infer_type M C \<Gamma> src of
        Some (OTSet t) \<Rightarrow> if infer_type M C ((x,t)#\<Gamma>) body = Some OTBool then Some OTBool else None
      | Some (OTBag t) \<Rightarrow> if infer_type M C ((x,t)#\<Gamma>) body = Some OTBool then Some OTBool else None
      | _ \<Rightarrow> None)"
| "infer_type M C \<Gamma> (OExists src x body) =
     (case infer_type M C \<Gamma> src of
        Some (OTSet t) \<Rightarrow> if infer_type M C ((x,t)#\<Gamma>) body = Some OTBool then Some OTBool else None
      | Some (OTBag t) \<Rightarrow> if infer_type M C ((x,t)#\<Gamma>) body = Some OTBool then Some OTBool else None
      | _ \<Rightarrow> None)"


section \<open>Published supported profile\<close>

text \<open>
  supported_expr is the single profile predicate used for compilation claims.
  infer_type remains the ordinary model-dependent type checker; this predicate
  additionally excludes constructors or operand domains for which the current
  semantic layer is intentionally incomplete (Real sum and String ordering).
\<close>

definition numeric_operands ::
  "ocl_model \<Rightarrow> cname \<Rightarrow> type_env \<Rightarrow> ocl_expr \<Rightarrow> ocl_expr \<Rightarrow> bool" where
  "numeric_operands M C \<Gamma> a b =
     (case (infer_type M C \<Gamma> a, infer_type M C \<Gamma> b) of
        (Some ta, Some tb) \<Rightarrow> numeric_result ta tb \<noteq> None
      | _ \<Rightarrow> False)"

fun supported_expr ::
  "ocl_model \<Rightarrow> cname \<Rightarrow> type_env \<Rightarrow> ocl_expr \<Rightarrow> bool" where
  "supported_expr M C \<Gamma> (OBool b) = True"
| "supported_expr M C \<Gamma> (OInt i) = True"
| "supported_expr M C \<Gamma> (OReal r) = True"
| "supported_expr M C \<Gamma> (OString s) = True"
| "supported_expr M C \<Gamma> OSelf = True"
| "supported_expr M C \<Gamma> (OVar x) = True"
| "supported_expr M C \<Gamma> (OLet x e body) =
     (supported_expr M C \<Gamma> e \<and>
      (case infer_type M C \<Gamma> e of
         None \<Rightarrow> False
       | Some t \<Rightarrow> supported_expr M C ((x,t)#\<Gamma>) body))"
| "supported_expr M C \<Gamma> (OIf c t e) =
     (supported_expr M C \<Gamma> c \<and>
      supported_expr M C \<Gamma> t \<and>
      supported_expr M C \<Gamma> e)"
| "supported_expr M C \<Gamma> (OAttr e a) =
     (supported_expr M C \<Gamma> e \<and>
      (case infer_type M C \<Gamma> (OAttr e a) of
         Some OTBool \<Rightarrow> True
       | Some OTInt \<Rightarrow> True
       | Some OTReal \<Rightarrow> True
       | Some OTString \<Rightarrow> True
       | _ \<Rightarrow> False))"
| "supported_expr M C \<Gamma> (ONav e r) = supported_expr M C \<Gamma> e"
| "supported_expr M C \<Gamma> (OAllInstances c) = True"
| "supported_expr M C \<Gamma> (OUnary op e) =
     (supported_expr M C \<Gamma> e \<and>
      (case op of
         UNot \<Rightarrow> True
       | UNeg \<Rightarrow> (infer_type M C \<Gamma> e = Some OTInt \<or>
                   infer_type M C \<Gamma> e = Some OTReal)
       | UIsUndefined \<Rightarrow> True
       | USize \<Rightarrow> True
       | UIsEmpty \<Rightarrow> True
       | UNotEmpty \<Rightarrow> True
       | USum \<Rightarrow> (case infer_type M C \<Gamma> e of
                      Some (OTSet OTInt) \<Rightarrow> True
                    | Some (OTBag OTInt) \<Rightarrow> True
                    | _ \<Rightarrow> False)))"
| "supported_expr M C \<Gamma> (OBinary op a b) =
     (supported_expr M C \<Gamma> a \<and> supported_expr M C \<Gamma> b \<and>
      (case op of
         BLt \<Rightarrow> numeric_operands M C \<Gamma> a b
       | BLe \<Rightarrow> numeric_operands M C \<Gamma> a b
       | BGt \<Rightarrow> numeric_operands M C \<Gamma> a b
       | BGe \<Rightarrow> numeric_operands M C \<Gamma> a b
       | BAdd \<Rightarrow> numeric_operands M C \<Gamma> a b
       | BSub \<Rightarrow> numeric_operands M C \<Gamma> a b
       | BMul \<Rightarrow> numeric_operands M C \<Gamma> a b
       | _ \<Rightarrow> True))"
| "supported_expr M C \<Gamma> (OSelect src x body) =
     (supported_expr M C \<Gamma> src \<and>
      (case infer_type M C \<Gamma> src of
         Some (OTSet t) \<Rightarrow> supported_expr M C ((x,t)#\<Gamma>) body
       | Some (OTBag t) \<Rightarrow> supported_expr M C ((x,t)#\<Gamma>) body
       | _ \<Rightarrow> False))"
| "supported_expr M C \<Gamma> (OCollect src x body) =
     (supported_expr M C \<Gamma> src \<and>
      (case infer_type M C \<Gamma> src of
         Some (OTSet t) \<Rightarrow> supported_expr M C ((x,t)#\<Gamma>) body
       | Some (OTBag t) \<Rightarrow> supported_expr M C ((x,t)#\<Gamma>) body
       | _ \<Rightarrow> False))"
| "supported_expr M C \<Gamma> (OForAll src x body) =
     (supported_expr M C \<Gamma> src \<and>
      (case infer_type M C \<Gamma> src of
         Some (OTSet t) \<Rightarrow> supported_expr M C ((x,t)#\<Gamma>) body
       | Some (OTBag t) \<Rightarrow> supported_expr M C ((x,t)#\<Gamma>) body
       | _ \<Rightarrow> False))"
| "supported_expr M C \<Gamma> (OExists src x body) =
     (supported_expr M C \<Gamma> src \<and>
      (case infer_type M C \<Gamma> src of
         Some (OTSet t) \<Rightarrow> supported_expr M C ((x,t)#\<Gamma>) body
       | Some (OTBag t) \<Rightarrow> supported_expr M C ((x,t)#\<Gamma>) body
       | _ \<Rightarrow> False))"

lemma supported_sum_integer:
  assumes "supported_expr M C \<Gamma> e"
      and "infer_type M C \<Gamma> e = Some (OTSet OTInt) \<or>
           infer_type M C \<Gamma> e = Some (OTBag OTInt)"
  shows "supported_expr M C \<Gamma> (OUnary USum e)"
  using assms by auto

lemma unsupported_sum_real:
  assumes "infer_type M C \<Gamma> e = Some (OTSet OTReal) \<or>
           infer_type M C \<Gamma> e = Some (OTBag OTReal)"
  shows "\<not> supported_expr M C \<Gamma> (OUnary USum e)"
  using assms by auto

lemma real_comparison_supported:
  "supported_expr M C \<Gamma> (OBinary BLt (OReal 2) (OReal 3)) \<and>
   infer_type M C \<Gamma> (OBinary BLt (OReal 2) (OReal 3)) = Some OTBool"
  by (simp add: numeric_operands_def)

lemma mixed_real_arithmetic_supported:
  "supported_expr M C \<Gamma> (OBinary BAdd (OInt 2) (OReal 3)) \<and>
   infer_type M C \<Gamma> (OBinary BAdd (OInt 2) (OReal 3)) = Some OTReal"
  by (simp add: numeric_operands_def)


section \<open>Invariant validity relative to M\<close>

definition invariant_wf :: "ocl_model \<Rightarrow> ocl_invariant \<Rightarrow> bool" where
  "invariant_wf M I \<longleftrightarrow>
     I \<in> model_invariants M \<and>
     inv_context I \<in> class_names M \<and>
     infer_type M (inv_context I) [] (inv_body I) = Some OTBool \<and>
     supported_expr M (inv_context I) [] (inv_body I)"

text \<open>
  This is the full source-side admission predicate required before compilation.
  model_conforms checks the class-diagram structure; invariant_wf additionally
  checks every OCL body against that very model.
\<close>

definition ocl_model_conforms :: "ocl_model \<Rightarrow> bool" where
  "ocl_model_conforms M \<longleftrightarrow>
     model_conforms MM_Class M \<and>
     (\<forall>I\<in>model_invariants M. invariant_wf M I)"


section \<open>Immediate safety properties\<close>

lemma invariant_wf_context_declared:
  assumes "invariant_wf M I"
  shows "inv_context I \<in> class_names M"
  using assms unfolding invariant_wf_def by blast

lemma invariant_wf_boolean:
  assumes "invariant_wf M I"
  shows "infer_type M (inv_context I) [] (inv_body I) = Some OTBool"
  using assms unfolding invariant_wf_def by blast

lemma invariant_wf_supported:
  assumes "invariant_wf M I"
  shows "supported_expr M (inv_context I) [] (inv_body I)"
  using assms unfolding invariant_wf_def by blast

lemma ocl_model_conforms_model:
  assumes "ocl_model_conforms M"
  shows "model_conforms MM_Class M"
  using assms unfolding ocl_model_conforms_def by blast

lemma ocl_model_conforms_invariant:
  assumes "ocl_model_conforms M" "I \<in> model_invariants M"
  shows "invariant_wf M I"
  using assms unfolding ocl_model_conforms_def by blast

end
