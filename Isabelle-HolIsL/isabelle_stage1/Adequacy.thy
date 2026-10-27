theory Adequacy
  imports Semantics
begin

section \<open>Concrete observations over snapshots and generated graphs\<close>

fun pg_value_sem :: "pg_value \<Rightarrow> sem_value" where
  "pg_value_sem (PGVBoolean b) = SBool b"
| "pg_value_sem (PGVInteger i) = SInt i"
| "pg_value_sem (PGVReal r) = SReal r"
| "pg_value_sem (PGVString s) = SString s"

fun pg_option_sem :: "pg_value option \<Rightarrow> sem_value" where
  "pg_option_sem None = SUndefined"
| "pg_option_sem (Some v) = pg_value_sem v"

definition snapshot_attr_observation ::
  "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> snapshot \<Rightarrow>
   oid \<Rightarrow> cname \<times> aname \<Rightarrow> sem_value" where
  "snapshot_attr_observation \<Sigma> M S i ak =
     pg_option_sem (build_node_property \<Sigma> M S i (sigma_attr_key \<Sigma> ak))"

definition graph_attr_observation ::
  "('n, 'e) property_graph \<Rightarrow> 'n \<Rightarrow> property_key \<Rightarrow> sem_value" where
  "graph_attr_observation G i k = pg_option_sem (pg_node_properties G i k)"

definition snapshot_scan_ids ::
  "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> snapshot \<Rightarrow> cname \<Rightarrow> oid set" where
  "snapshot_scan_ids \<Sigma> M S c =
     {i \<in> object_ids S. sigma_class_label \<Sigma> c \<in> build_labels \<Sigma> M S i}"

definition graph_scan_ids ::
  "('n, 'e) property_graph \<Rightarrow> graph_label \<Rightarrow> 'n set" where
  "graph_scan_ids G l = {i \<in> pg_nodes G. l \<in> pg_node_labels G i}"

definition snapshot_instance_ids ::
  "'e class_model \<Rightarrow> snapshot \<Rightarrow> cname \<Rightarrow> oid set" where
  "snapshot_instance_ids M S c =
     {i \<in> object_ids S.
        subclass_of M (obj_class (object_of_id S i)) c}"

definition ids_as_values :: "oid set \<Rightarrow> sem_value list" where
  "ids_as_values A = map SObject (SOME xs. set xs = A)"

definition snapshot_scan_observation ::
  "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> snapshot \<Rightarrow>
   cname \<Rightarrow> sem_value list" where
  "snapshot_scan_observation \<Sigma> M S c =
     ids_as_values (snapshot_scan_ids \<Sigma> M S c)"

definition graph_scan_observation ::
  "(oid, 'e) property_graph \<Rightarrow> graph_label \<Rightarrow> sem_value list" where
  "graph_scan_observation G l = ids_as_values (graph_scan_ids G l)"

definition snapshot_nav_ids ::
  "rep_spec \<Rightarrow> snapshot \<Rightarrow> oid \<Rightarrow> nav_ref \<Rightarrow> oid set" where
  "snapshot_nav_ids \<Sigma> S i n =
     (let t = sigma_assoc_type \<Sigma> (nav_assoc_key n);
          d = physical_traversal
                (sigma_assoc_direction \<Sigma> (nav_assoc_key n))
                (nav_direction n)
      in case d of
           TraverseOut \<Rightarrow>
             {build_target \<Sigma> e | e.
                e \<in> snap_links S \<and>
                sigma_assoc_type \<Sigma> (link_assoc e) = t \<and>
                build_source \<Sigma> e = i}
         | TraverseIn \<Rightarrow>
             {build_source \<Sigma> e | e.
                e \<in> snap_links S \<and>
                sigma_assoc_type \<Sigma> (link_assoc e) = t \<and>
                build_target \<Sigma> e = i})"

definition graph_nav_ids ::
  "(oid, 'e) property_graph \<Rightarrow> oid \<Rightarrow> query_nav \<Rightarrow> oid set" where
  "graph_nav_ids G i n =
     (case qn_traversal n of
        TraverseOut \<Rightarrow>
          {pg_target G e | e.
             e \<in> pg_edges G \<and>
             pg_edge_type G e = qn_relationship_type n \<and>
             pg_source G e = i}
      | TraverseIn \<Rightarrow>
          {pg_source G e | e.
             e \<in> pg_edges G \<and>
             pg_edge_type G e = qn_relationship_type n \<and>
             pg_target G e = i})"

definition snapshot_nav_observation ::
  "rep_spec \<Rightarrow> snapshot \<Rightarrow> oid \<Rightarrow> nav_ref \<Rightarrow> sem_value list" where
  "snapshot_nav_observation \<Sigma> S i n =
     ids_as_values (snapshot_nav_ids \<Sigma> S i n)"

definition graph_nav_observation ::
  "(oid, 'e) property_graph \<Rightarrow> oid \<Rightarrow> query_nav \<Rightarrow> sem_value list" where
  "graph_nav_observation G i n = ids_as_values (graph_nav_ids G i n)"

section \<open>The generated graph discharges the semantic boundary\<close>

lemma build_label_iff_subclass:
  assumes REP: "rep_spec_wf \<Sigma> M"
      and CLASS: "c \<in> class_names M"
      and OBJECT: "i \<in> object_ids S"
  shows "sigma_class_label \<Sigma> c \<in> pg_node_labels (build \<Sigma> M S) i
         \<longleftrightarrow> subclass_of M (obj_class (object_of_id S i)) c"
proof -
  have INJ: "inj_on (sigma_class_label \<Sigma>) (class_names M)"
    using REP unfolding rep_spec_wf_def by simp
  show ?thesis
    using INJ CLASS OBJECT
    by (auto simp: build_labels_def dest: inj_onD)
qed

lemma build_class_scan_exact:
  assumes REP: "rep_spec_wf \<Sigma> M"
      and CLASS: "c \<in> class_names M"
  shows "graph_scan_ids (build \<Sigma> M S) (sigma_class_label \<Sigma> c) =
         snapshot_instance_ids M S c"
  using build_label_iff_subclass[OF REP CLASS]
  unfolding graph_scan_ids_def snapshot_instance_ids_def
  by auto

lemma snapshot_scan_ids_exact:
  assumes REP: "rep_spec_wf \<Sigma> M"
      and CLASS: "c \<in> class_names M"
  shows "snapshot_scan_ids \<Sigma> M S c = snapshot_instance_ids M S c"
  using build_label_iff_subclass[OF REP CLASS]
  unfolding snapshot_scan_ids_def snapshot_instance_ids_def
  by auto

lemma build_attr_observation_adequate:
  "graph_attr_observation (build \<Sigma> M S) i (sigma_attr_key \<Sigma> ak) =
   snapshot_attr_observation \<Sigma> M S i ak"
  unfolding graph_attr_observation_def snapshot_attr_observation_def by simp

lemma build_scan_observation_adequate:
  "graph_scan_observation (build \<Sigma> M S) (sigma_class_label \<Sigma> c) =
   snapshot_scan_observation \<Sigma> M S c"
  unfolding graph_scan_observation_def snapshot_scan_observation_def
    graph_scan_ids_def snapshot_scan_ids_def by simp

lemma build_nav_ids_adequate:
  "graph_nav_ids (build \<Sigma> M S) i (lower_nav \<Sigma> n k) =
   snapshot_nav_ids \<Sigma> S i n"
  unfolding graph_nav_ids_def snapshot_nav_ids_def lower_nav_def Let_def
  by (cases "sigma_assoc_direction \<Sigma> (nav_assoc_key n)";
      cases "nav_direction n"; simp)

lemma build_nav_observation_adequate:
  "graph_nav_observation (build \<Sigma> M S) i (lower_nav \<Sigma> n k) =
   snapshot_nav_observation \<Sigma> S i n"
  unfolding graph_nav_observation_def snapshot_nav_observation_def
  using build_nav_ids_adequate[of \<Sigma> M S i n k] by simp

interpretation build_semantics: adequate_observations
  \<Sigma>
  "snapshot_attr_observation \<Sigma> M S"
  "snapshot_nav_observation \<Sigma> S"
  "snapshot_scan_observation \<Sigma> M S"
  "graph_attr_observation (build \<Sigma> M S)"
  "graph_nav_observation (build \<Sigma> M S)"
  "graph_scan_observation (build \<Sigma> M S)"
proof
  show "graph_attr_observation (build \<Sigma> M S) i (sigma_attr_key \<Sigma> ak) =
        snapshot_attr_observation \<Sigma> M S i ak" for i ak
    by (rule build_attr_observation_adequate)
  show "graph_nav_observation (build \<Sigma> M S) i (lower_nav \<Sigma> n k) =
        snapshot_nav_observation \<Sigma> S i n" for i n k
    by (rule build_nav_observation_adequate)
  show "graph_scan_observation (build \<Sigma> M S) (sigma_class_label \<Sigma> c) =
        snapshot_scan_observation \<Sigma> M S c" for c
    by (rule build_scan_observation_adequate)
qed

section \<open>Closed transformation-correctness statements\<close>

definition source_context_ids ::
  "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> snapshot \<Rightarrow>
   core_invariant \<Rightarrow> oid set" where
  "source_context_ids \<Sigma> M S CI =
     snapshot_scan_ids \<Sigma> M S (core_inv_context CI)"

definition target_context_ids ::
  "rep_spec \<Rightarrow> (oid, 'e) property_graph \<Rightarrow>
   graph_query \<Rightarrow> oid set" where
  "target_context_ids \<Sigma> G q = graph_scan_ids G (query_context_label q)"

theorem compiled_build_preserves_violations:
  assumes COMPILE: "compile_invariant \<Sigma> M I = Some q"
  obtains CI where
    "front M I = Some CI"
    "q = lower_invariant \<Sigma> CI"
    "build_semantics.query_violations
       TYPE(ocl_expr) \<Sigma> M S q
       (target_context_ids \<Sigma> (build \<Sigma> M S) q) =
     build_semantics.ocl_violations
       TYPE(ocl_expr) \<Sigma> M S M I (source_context_ids \<Sigma> M S CI)"
proof -
  obtain CI where FRONT: "front M I = Some CI"
      and Q: "q = lower_invariant \<Sigma> CI"
    using compile_success_is_lowered_front[OF COMPILE] by blast
  have CONTEXT:
    "target_context_ids \<Sigma> (build \<Sigma> M S) q =
     source_context_ids \<Sigma> M S CI"
    unfolding target_context_ids_def source_context_ids_def Q lower_invariant_def
    graph_scan_ids_def snapshot_scan_ids_def by simp
  have EQ:
    "build_semantics.query_violations
       TYPE(ocl_expr) \<Sigma> M S q
       (target_context_ids \<Sigma> (build \<Sigma> M S) q) =
     build_semantics.ocl_violations
       TYPE(ocl_expr) \<Sigma> M S M I (source_context_ids \<Sigma> M S CI)"
    using build_semantics.compile_invariant_preserves_violations[OF COMPILE CONTEXT] .
  show thesis using that[OF FRONT Q EQ] .
qed

theorem transformation_correct:
  assumes ADMISSIBLE: "build_admissible \<Sigma> M S"
      and COMPILE: "compile_invariant \<Sigma> M I = Some q"
  shows "graph_conforms MM_Graph (build \<Sigma> M S) \<and>
         bij_betw obj_id (snap_objects S) (pg_nodes (build \<Sigma> M S)) \<and>
         (\<exists>CI. front M I = Some CI \<and> q = lower_invariant \<Sigma> CI \<and>
           build_semantics.query_violations
             TYPE(ocl_expr) \<Sigma> M S q
             (target_context_ids \<Sigma> (build \<Sigma> M S) q) =
           build_semantics.ocl_violations
             TYPE(ocl_expr) \<Sigma> M S M I (source_context_ids \<Sigma> M S CI))"
proof -
  have GRAPH: "graph_conforms MM_Graph (build \<Sigma> M S)"
    using build_graph_conforms[OF ADMISSIBLE] .
  have SNAP: "snapshot_conforms M S"
    using ADMISSIBLE unfolding build_admissible_def by blast
  have BIJ: "bij_betw obj_id (snap_objects S) (pg_nodes (build \<Sigma> M S))"
    using build_object_node_bijection[OF SNAP] .
  obtain CI where F: "front M I = Some CI" and Q: "q = lower_invariant \<Sigma> CI"
      and EQ: "build_semantics.query_violations
        TYPE(ocl_expr) \<Sigma> M S q
        (target_context_ids \<Sigma> (build \<Sigma> M S) q) =
       build_semantics.ocl_violations
        TYPE(ocl_expr) \<Sigma> M S M I (source_context_ids \<Sigma> M S CI)"
    using compiled_build_preserves_violations[OF COMPILE] by blast
  show ?thesis using GRAPH BIJ F Q EQ by blast
qed

end
