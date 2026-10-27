theory ClassMetamodel
  imports "HOL.Real"
begin

section \<open>Source-side metamodel, model, and snapshot\<close>

type_synonym cname = string
type_synonym aname = string
type_synonym assoc_name = string
type_synonym role_name = string
type_synonym inv_name = string
type_synonym oid = string

datatype primitive_type = TBoolean | TInteger | TReal | TString

datatype model_type =
    Primitive primitive_type
  | Reference cname

datatype upper_bound = Finite nat | Unlimited

record multiplicity =
  mult_lower :: nat
  mult_upper :: upper_bound

definition wf_multiplicity :: "multiplicity \<Rightarrow> bool" where
  "wf_multiplicity m \<longleftrightarrow>
     (case mult_upper m of
        Unlimited \<Rightarrow> True
      | Finite u \<Rightarrow> mult_lower m \<le> u)"

definition multiplicity_holds :: "multiplicity \<Rightarrow> nat \<Rightarrow> bool" where
  "multiplicity_holds m n \<longleftrightarrow>
     mult_lower m \<le> n \<and>
     (case mult_upper m of
        Unlimited \<Rightarrow> True
      | Finite u \<Rightarrow> n \<le> u)"

record class_decl =
  cls_name     :: cname
  cls_abstract :: bool

record attribute_decl =
  attr_owner :: cname
  attr_name  :: aname
  attr_type  :: model_type
  attr_mult  :: multiplicity

record association_end =
  end_class   :: cname
  end_role    :: role_name
  end_mult    :: multiplicity
  end_ordered :: bool

record association_decl =
  assoc_key   :: assoc_name
  assoc_left  :: association_end
  assoc_right :: association_end

record generalization =
  gen_child  :: cname
  gen_parent :: cname

record 'e invariant_decl =
  inv_key     :: inv_name
  inv_context :: cname
  inv_body    :: 'e

record 'e class_model =
  model_classes         :: "class_decl set"
  model_attributes      :: "attribute_decl set"
  model_associations    :: "association_decl set"
  model_generalizations :: "generalization set"
  model_invariants      :: "'e invariant_decl set"

record class_metamodel =
  mm_primitive_types      :: "primitive_type set"
  mm_multiple_inheritance :: bool
  mm_ordered_assoc_ends   :: bool

definition MM_Class :: class_metamodel where
  "MM_Class =
    \<lparr> mm_primitive_types      = {TBoolean, TInteger, TReal, TString},
      mm_multiple_inheritance = True,
      mm_ordered_assoc_ends   = True
    \<rparr>"

section \<open>Model conformance\<close>

definition unique_by :: "('a \<Rightarrow> 'b) \<Rightarrow> 'a set \<Rightarrow> bool" where
  "unique_by f A \<longleftrightarrow> (\<forall>x\<in>A. \<forall>y\<in>A. f x = f y \<longrightarrow> x = y)"

lemma unique_by_imp_inj_on:
  assumes "unique_by f A"
  shows "inj_on f A"
  using assms unfolding unique_by_def inj_on_def by blast

definition class_names :: "'e class_model \<Rightarrow> cname set" where
  "class_names M = cls_name ` model_classes M"

definition assoc_names :: "'e class_model \<Rightarrow> assoc_name set" where
  "assoc_names M = assoc_key ` model_associations M"

definition attr_key_of :: "attribute_decl \<Rightarrow> cname \<times> aname" where
  "attr_key_of a = (attr_owner a, attr_name a)"

definition parent_rel :: "'e class_model \<Rightarrow> (cname \<times> cname) set" where
  "parent_rel M =
     {(c,p). \<exists>g\<in>model_generalizations M.
                 gen_child g = c \<and> gen_parent g = p}"

definition subclass_of :: "'e class_model \<Rightarrow> cname \<Rightarrow> cname \<Rightarrow> bool" where
  "subclass_of M c d \<longleftrightarrow> c = d \<or> (c,d) \<in> trancl (parent_rel M)"

definition is_abstract_class :: "'e class_model \<Rightarrow> cname \<Rightarrow> bool" where
  "is_abstract_class M c \<longleftrightarrow>
     (\<exists>C\<in>model_classes M. cls_name C = c \<and> cls_abstract C)"

definition type_defined ::
  "class_metamodel \<Rightarrow> 'e class_model \<Rightarrow> model_type \<Rightarrow> bool" where
  "type_defined MM M \<tau> \<longleftrightarrow>
     (case \<tau> of
        Primitive p \<Rightarrow> p \<in> mm_primitive_types MM
      | Reference c \<Rightarrow> c \<in> class_names M)"

definition applicable_attribute ::
  "'e class_model \<Rightarrow> cname \<Rightarrow> attribute_decl \<Rightarrow> bool" where
  "applicable_attribute M c a \<longleftrightarrow>
     a \<in> model_attributes M \<and> subclass_of M c (attr_owner a)"

definition model_conforms ::
  "class_metamodel \<Rightarrow> 'e class_model \<Rightarrow> bool" where
  "model_conforms MM M \<longleftrightarrow>
     finite (model_classes M) \<and>
     finite (model_attributes M) \<and>
     finite (model_associations M) \<and>
     finite (model_generalizations M) \<and>
     finite (model_invariants M) \<and>
     unique_by cls_name (model_classes M) \<and>
     unique_by attr_key_of (model_attributes M) \<and>
     unique_by assoc_key (model_associations M) \<and>
     unique_by inv_key (model_invariants M) \<and>
     (\<forall>a\<in>model_attributes M.
        attr_owner a \<in> class_names M \<and>
        type_defined MM M (attr_type a) \<and>
        wf_multiplicity (attr_mult a)) \<and>
     (\<forall>a\<in>model_associations M.
        end_class (assoc_left a) \<in> class_names M \<and>
        end_class (assoc_right a) \<in> class_names M \<and>
        wf_multiplicity (end_mult (assoc_left a)) \<and>
        wf_multiplicity (end_mult (assoc_right a)) \<and>
        (mm_ordered_assoc_ends MM \<or> \<not> end_ordered (assoc_left a)) \<and>
        (mm_ordered_assoc_ends MM \<or> \<not> end_ordered (assoc_right a))) \<and>
     (\<forall>g\<in>model_generalizations M.
        gen_child g \<in> class_names M \<and>
        gen_parent g \<in> class_names M \<and>
        gen_child g \<noteq> gen_parent g) \<and>
     acyclic (parent_rel M) \<and>
     (mm_multiple_inheritance MM \<or>
        (\<forall>c\<in>class_names M. card {p. (c,p) \<in> parent_rel M} \<le> 1)) \<and>
     (\<forall>i\<in>model_invariants M. inv_context i \<in> class_names M)"

abbreviation valid_class_model :: "'e class_model \<Rightarrow> bool" where
  "valid_class_model M \<equiv> model_conforms MM_Class M"

section \<open>Snapshots\<close>

datatype uml_value =
    VBool bool
  | VInt int
  | VReal real
  | VString string
  | VObj oid

record object_instance =
  obj_id    :: oid
  obj_class :: cname

record slot_instance =
  slot_object :: oid
  slot_attr   :: "cname \<times> aname"
  slot_values :: "uml_value list"

record link_instance =
  link_assoc :: assoc_name
  link_left  :: oid
  link_right :: oid

record snapshot =
  snap_objects :: "object_instance set"
  snap_slots   :: "slot_instance set"
  snap_links   :: "link_instance set"

definition object_ids :: "snapshot \<Rightarrow> oid set" where
  "object_ids S = obj_id ` snap_objects S"

definition slot_key_of :: "slot_instance \<Rightarrow> oid \<times> (cname \<times> aname)" where
  "slot_key_of s = (slot_object s, slot_attr s)"

fun value_conforms ::
  "'e class_model \<Rightarrow> snapshot \<Rightarrow> model_type \<Rightarrow> uml_value \<Rightarrow> bool" where
  "value_conforms M S (Primitive TBoolean) (VBool b) = True"
| "value_conforms M S (Primitive TInteger) (VInt i) = True"
| "value_conforms M S (Primitive TReal)    (VReal r) = True"
| "value_conforms M S (Primitive TString)  (VString x) = True"
| "value_conforms M S (Reference c) (VObj i) =
     (\<exists>ob\<in>snap_objects S. obj_id ob = i \<and> subclass_of M (obj_class ob) c)"
| "value_conforms M S \<tau> v = False"

definition slot_wf ::
  "'e class_model \<Rightarrow> snapshot \<Rightarrow> slot_instance \<Rightarrow> bool" where
  "slot_wf M S sl \<longleftrightarrow>
     (\<exists>ob\<in>snap_objects S.
        obj_id ob = slot_object sl \<and>
        (\<exists>a\<in>model_attributes M.
           attr_key_of a = slot_attr sl \<and>
           applicable_attribute M (obj_class ob) a \<and>
           multiplicity_holds (attr_mult a) (length (slot_values sl)) \<and>
           (\<forall>v\<in>set (slot_values sl). value_conforms M S (attr_type a) v)))"

definition all_required_slots_present ::
  "'e class_model \<Rightarrow> snapshot \<Rightarrow> bool" where
  "all_required_slots_present M S \<longleftrightarrow>
     (\<forall>ob\<in>snap_objects S. \<forall>a\<in>model_attributes M.
        applicable_attribute M (obj_class ob) a \<longrightarrow>
        (\<exists>!sl. sl\<in>snap_slots S \<and>
               slot_object sl = obj_id ob \<and>
               slot_attr sl = attr_key_of a))"

definition link_wf ::
  "'e class_model \<Rightarrow> snapshot \<Rightarrow> link_instance \<Rightarrow> bool" where
  "link_wf M S l \<longleftrightarrow>
     (\<exists>a\<in>model_associations M.
        assoc_key a = link_assoc l \<and>
        (\<exists>lo\<in>snap_objects S. \<exists>ro\<in>snap_objects S.
           obj_id lo = link_left l \<and>
           obj_id ro = link_right l \<and>
           subclass_of M (obj_class lo) (end_class (assoc_left a)) \<and>
           subclass_of M (obj_class ro) (end_class (assoc_right a))))"

definition count_right_targets ::
  "snapshot \<Rightarrow> association_decl \<Rightarrow> oid \<Rightarrow> nat" where
  "count_right_targets S a left_id =
     card {l\<in>snap_links S. link_assoc l = assoc_key a \<and> link_left l = left_id}"

definition count_left_sources ::
  "snapshot \<Rightarrow> association_decl \<Rightarrow> oid \<Rightarrow> nat" where
  "count_left_sources S a right_id =
     card {l\<in>snap_links S. link_assoc l = assoc_key a \<and> link_right l = right_id}"

definition association_multiplicities_hold ::
  "'e class_model \<Rightarrow> snapshot \<Rightarrow> bool" where
  "association_multiplicities_hold M S \<longleftrightarrow>
     (\<forall>a\<in>model_associations M.
        (\<forall>ob\<in>snap_objects S.
           subclass_of M (obj_class ob) (end_class (assoc_left a)) \<longrightarrow>
           multiplicity_holds
             (end_mult (assoc_right a))
             (count_right_targets S a (obj_id ob))) \<and>
        (\<forall>ob\<in>snap_objects S.
           subclass_of M (obj_class ob) (end_class (assoc_right a)) \<longrightarrow>
           multiplicity_holds
             (end_mult (assoc_left a))
             (count_left_sources S a (obj_id ob))))"

definition snapshot_conforms ::
  "'e class_model \<Rightarrow> snapshot \<Rightarrow> bool" where
  "snapshot_conforms M S \<longleftrightarrow>
     finite (snap_objects S) \<and>
     finite (snap_slots S) \<and>
     finite (snap_links S) \<and>
     unique_by obj_id (snap_objects S) \<and>
     (\<forall>ob\<in>snap_objects S.
        obj_class ob \<in> class_names M \<and>
        \<not> is_abstract_class M (obj_class ob)) \<and>
     unique_by slot_key_of (snap_slots S) \<and>
     (\<forall>sl\<in>snap_slots S. slot_wf M S sl) \<and>
     all_required_slots_present M S \<and>
     (\<forall>l\<in>snap_links S. link_wf M S l) \<and>
     association_multiplicities_hold M S"

lemma snapshot_conforms_unique_object_ids:
  assumes "snapshot_conforms M S"
  shows "unique_by obj_id (snap_objects S)"
  using assms unfolding snapshot_conforms_def by blast

lemma snapshot_conforms_finite_objects:
  assumes "snapshot_conforms M S"
  shows "finite (snap_objects S)"
  using assms unfolding snapshot_conforms_def by blast

lemma snapshot_conforms_finite_links:
  assumes "snapshot_conforms M S"
  shows "finite (snap_links S)"
  using assms unfolding snapshot_conforms_def by blast

end
