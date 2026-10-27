theory Example
  imports Adequacy
begin

section \<open>Executable paper example\<close>

text \<open>
  This theory instantiates the Branch--Employee example from the paper.  It is
  deliberately part of the session: the equations below are executable
  regression checks, while the lemmas are checked by Isabelle/HOL.
\<close>

definition branch_class :: class_decl where
  "branch_class =
     \<lparr>cls_name = ''Branch'', cls_abstract = False\<rparr>"

definition employee_class :: class_decl where
  "employee_class =
     \<lparr>cls_name = ''Employee'', cls_abstract = False\<rparr>"

definition salary_attribute :: attribute_decl where
  "salary_attribute =
     \<lparr>attr_owner = ''Employee'',
       attr_name = ''salary'',
       attr_type = Primitive TInteger,
       attr_mult = \<lparr>mult_lower = 1, mult_upper = Finite 1\<rparr>\<rparr>"

definition employee_association :: association_decl where
  "employee_association =
     \<lparr>assoc_key = ''employment'',
       assoc_left =
         \<lparr>end_class = ''Branch'', end_role = ''branch'',
           end_mult = \<lparr>mult_lower = 0, mult_upper = Unlimited\<rparr>,
           end_ordered = False\<rparr>,
       assoc_right =
         \<lparr>end_class = ''Employee'', end_role = ''employee'',
           end_mult = \<lparr>mult_lower = 0, mult_upper = Unlimited\<rparr>,
           end_ordered = False\<rparr>\<rparr>"

definition manager_association :: association_decl where
  "manager_association =
     \<lparr>assoc_key = ''management'',
       assoc_left =
         \<lparr>end_class = ''Branch'', end_role = ''managedBranch'',
           end_mult = \<lparr>mult_lower = 0, mult_upper = Finite 1\<rparr>,
           end_ordered = False\<rparr>,
       assoc_right =
         \<lparr>end_class = ''Employee'', end_role = ''manager'',
           end_mult = \<lparr>mult_lower = 0, mult_upper = Finite 1\<rparr>,
           end_ordered = False\<rparr>\<rparr>"

definition manager_salary_dominates :: ocl_invariant where
  "manager_salary_dominates =
     \<lparr>inv_key = ''ManagerSalaryDominates'',
       inv_context = ''Branch'',
       inv_body =
         OForAll (ONav OSelf ''employee'') ''e''
           (OBinary BImplies
             (OBinary BNeq (OVar ''e'') (ONav OSelf ''manager''))
             (OBinary BGt
               (OAttr (ONav OSelf ''manager'') ''salary'')
               (OAttr (OVar ''e'') ''salary'')))\<rparr>"

definition example_model :: ocl_model where
  "example_model =
     \<lparr>model_classes = {branch_class, employee_class},
       model_attributes = {salary_attribute},
       model_associations = {employee_association, manager_association},
       model_generalizations = {},
       model_invariants = {manager_salary_dominates}\<rparr>"

definition example_sigma :: rep_spec where
  "example_sigma =
     \<lparr>sigma_id_key = ''id'',
       sigma_class_label = (\<lambda>c. c),
       sigma_attr_key = (\<lambda>ak. snd ak),
       sigma_assoc_type =
         (\<lambda>a. if a = ''employment'' then ''EMPLOYEE'' else ''MANAGER''),
       sigma_assoc_direction =
         (\<lambda>a. if a = ''employment'' then Reverse else Forward)\<rparr>"

definition branch_object :: object_instance where
  "branch_object = \<lparr>obj_id = ''oneBranch'', obj_class = ''Branch''\<rparr>"

definition alice_object :: object_instance where
  "alice_object = \<lparr>obj_id = ''Alice'', obj_class = ''Employee''\<rparr>"

definition bob_object :: object_instance where
  "bob_object = \<lparr>obj_id = ''Bob'', obj_class = ''Employee''\<rparr>"

definition alice_salary :: slot_instance where
  "alice_salary =
     \<lparr>slot_object = ''Alice'', slot_attr = (''Employee'', ''salary''),
       slot_values = [VInt 50]\<rparr>"

definition bob_salary :: slot_instance where
  "bob_salary =
     \<lparr>slot_object = ''Bob'', slot_attr = (''Employee'', ''salary''),
       slot_values = [VInt 70]\<rparr>"

definition employee_alice :: link_instance where
  "employee_alice =
     \<lparr>link_assoc = ''employment'', link_left = ''oneBranch'',
       link_right = ''Alice''\<rparr>"

definition employee_bob :: link_instance where
  "employee_bob =
     \<lparr>link_assoc = ''employment'', link_left = ''oneBranch'',
       link_right = ''Bob''\<rparr>"

definition manager_alice :: link_instance where
  "manager_alice =
     \<lparr>link_assoc = ''management'', link_left = ''oneBranch'',
       link_right = ''Alice''\<rparr>"

definition example_snapshot :: snapshot where
  "example_snapshot =
     \<lparr>snap_objects = {branch_object, alice_object, bob_object},
       snap_slots = {alice_salary, bob_salary},
       snap_links = {employee_alice, employee_bob, manager_alice}\<rparr>"


section \<open>Checked transformation results\<close>

lemma example_class_names[simp]:
  "class_names example_model = {''Branch'', ''Employee''}"
  by (simp add: class_names_def example_model_def branch_class_def
      employee_class_def)

lemma example_assoc_names[simp]:
  "assoc_names example_model = {''employment'', ''management''}"
  by (simp add: assoc_names_def example_model_def employee_association_def
      manager_association_def)

lemma example_parent_rel[simp]:
  "parent_rel example_model = {}"
  by (auto simp: parent_rel_def example_model_def)

lemma example_invariants[simp]:
  "model_invariants example_model = {manager_salary_dominates}"
  by (simp add: example_model_def)

lemma example_employee_navigation_candidates[simp]:
  "navigation_candidates example_model ''Branch'' ''employee'' =
     {(employee_association, LeftToRight)}"
  by (auto simp: navigation_candidates_def example_model_def
      employee_association_def manager_association_def subclass_of_def)

lemma example_manager_navigation_candidates[simp]:
  "navigation_candidates example_model ''Branch'' ''manager'' =
     {(manager_association, LeftToRight)}"
  by (auto simp: navigation_candidates_def example_model_def
      employee_association_def manager_association_def subclass_of_def)

lemma example_salary_attribute_candidates[simp]:
  "matching_attributes example_model ''Employee'' ''salary'' =
     {salary_attribute}"
  by (auto simp: matching_attributes_def example_model_def salary_attribute_def
      applicable_attribute_def subclass_of_def)

lemma example_class_model_conforms:
  "model_conforms MM_Class example_model"
  by (auto simp add: model_conforms_def example_model_def branch_class_def
      employee_class_def salary_attribute_def employee_association_def
      manager_association_def manager_salary_dominates_def attr_key_of_def
      type_defined_def wf_multiplicity_def unique_by_def MM_Class_def
      class_names_def parent_rel_def acyclic_def)

lemma example_invariant_well_typed:
  "invariant_wf example_model manager_salary_dominates"
  by (simp add: invariant_wf_def manager_salary_dominates_def
      navigation_resolves_def resolved_navigation_def attribute_resolves_def
      resolved_attribute_def employee_association_def manager_association_def
      salary_attribute_def numeric_operands_def)

lemma example_model_admissible:
  "ocl_model_conforms example_model"
  using example_class_model_conforms example_invariant_well_typed
  by (simp add: ocl_model_conforms_def example_model_def)

lemma example_representation_well_formed:
  "rep_spec_wf example_sigma example_model"
  by (simp add: rep_spec_wf_def example_sigma_def example_model_def
      branch_class_def employee_class_def salary_attribute_def
      employee_association_def manager_association_def
      class_names_def assoc_names_def attr_key_of_def
      valid_graph_name_def inj_on_def)

lemma example_builder_supported:
  "builder_supported_model example_model"
  by (simp add: builder_supported_model_def primitive_scalar_attribute_def
      example_model_def salary_attribute_def)

lemma example_management_left_links:
  "{l\<in>snap_links example_snapshot.
      link_assoc l = ''management'' \<and> link_left l = ''oneBranch''} = {manager_alice}"
  by (auto simp: example_snapshot_def employee_alice_def employee_bob_def
      manager_alice_def)

lemma example_management_alice_links:
  "{l\<in>snap_links example_snapshot.
      link_assoc l = ''management'' \<and> link_right l = ''Alice''} = {manager_alice}"
  by (auto simp: example_snapshot_def employee_alice_def employee_bob_def
      manager_alice_def)

lemma example_management_bob_links:
  "{l\<in>snap_links example_snapshot.
      link_assoc l = ''management'' \<and> link_right l = ''Bob''} = {}"
  by (auto simp: example_snapshot_def employee_alice_def employee_bob_def
      manager_alice_def)

lemma example_management_counts:
  "count_right_targets example_snapshot manager_association ''oneBranch'' = 1"
  "count_left_sources example_snapshot manager_association ''Alice'' = 1"
  "count_left_sources example_snapshot manager_association ''Bob'' = 0"
  by (simp_all add: count_right_targets_def count_left_sources_def
      manager_association_def example_management_left_links
      example_management_alice_links example_management_bob_links)

lemma example_association_multiplicities:
  "association_multiplicities_hold example_model example_snapshot"
  using example_management_counts
  by (auto simp: association_multiplicities_hold_def example_model_def
      example_snapshot_def branch_object_def alice_object_def bob_object_def
      employee_association_def manager_association_def subclass_of_def
      parent_rel_def multiplicity_holds_def)

lemma example_snapshot_conforms:
  "snapshot_conforms example_model example_snapshot"
  using example_association_multiplicities
  by (auto simp add: snapshot_conforms_def example_model_def example_snapshot_def
      branch_class_def employee_class_def salary_attribute_def
      employee_association_def manager_association_def
      branch_object_def alice_object_def bob_object_def
      alice_salary_def bob_salary_def employee_alice_def employee_bob_def
      manager_alice_def class_names_def attr_key_of_def object_ids_def
      unique_by_def is_abstract_class_def subclass_of_def parent_rel_def
      slot_key_of_def slot_wf_def all_required_slots_present_def
      applicable_attribute_def multiplicity_holds_def link_wf_def
      association_multiplicities_hold_def)

lemma example_build_admissible:
  "build_admissible example_sigma example_model example_snapshot"
  using example_class_model_conforms example_snapshot_conforms
    example_representation_well_formed example_builder_supported
  unfolding build_admissible_def by blast

lemma example_graph_conforms:
  "graph_conforms MM_Graph
     (build example_sigma example_model example_snapshot)"
  using build_graph_conforms example_build_admissible
  by blast

lemma example_generated_nodes:
  "pg_nodes (build example_sigma example_model example_snapshot) =
     {''oneBranch'', ''Alice'', ''Bob''}"
  by (simp add: example_snapshot_def branch_object_def alice_object_def
      bob_object_def object_ids_def)

lemma employee_navigation_reverses_physical_edge:
  "physical_traversal
     (sigma_assoc_direction example_sigma ''employment'')
     LeftToRight = TraverseIn"
  by (simp add: example_sigma_def)

lemma manager_navigation_follows_physical_edge:
  "physical_traversal
     (sigma_assoc_direction example_sigma ''management'')
     LeftToRight = TraverseOut"
  by (simp add: example_sigma_def)

lemma example_employee_edge_is_physically_reversed:
  "pg_source (build example_sigma example_model example_snapshot) employee_bob = ''Bob'' \<and>
   pg_target (build example_sigma example_model example_snapshot) employee_bob = ''oneBranch''"
  by (simp add: build_source_def build_target_def example_sigma_def
      employee_bob_def)

lemma example_object_node_bijection:
  "bij_betw obj_id
     (snap_objects example_snapshot)
     (pg_nodes (build example_sigma example_model example_snapshot))"
  using build_object_node_bijection example_snapshot_conforms by blast

lemma example_compiler_succeeds:
  "\<exists>q. compile_invariant example_sigma example_model
          manager_salary_dominates = Some q"
  using compile_total_on_admitted_invariant[
      OF example_model_admissible
         example_representation_well_formed
         example_builder_supported]
  unfolding example_model_def by simp

lemma example_compiled_query_contract:
  assumes "compile_cypher example_sigma example_model
             manager_salary_dominates = Some cq"
  shows "cy_context_label cq = ''Branch'' \<and>
         cy_return_id_key cq = ''id'' \<and>
         cy_keep_non_true cq \<and>
         cy_return_distinct cq"
  using compile_cypher_context[OF assms]
        compile_cypher_return_id[OF assms]
        compile_cypher_violation_wrapper[OF assms]
  unfolding example_sigma_def manager_salary_dominates_def by simp

value "build_nodes example_snapshot"
value "(build_source example_sigma employee_bob,
        build_target example_sigma employee_bob)"
value "physical_traversal
         (sigma_assoc_direction example_sigma ''employment'') LeftToRight"

end
