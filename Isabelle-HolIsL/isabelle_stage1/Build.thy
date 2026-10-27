theory Build
  imports GraphMetamodel
begin

section \<open>Representation specification Sigma\<close>

text \<open>
  Sigma does not add a third graph level.  It is only the configuration of the
  transformation from the source model/snapshot to the single graph model G.
\<close>

datatype edge_direction = Forward | Reverse

record rep_spec =
  sigma_id_key          :: property_key
  sigma_class_label     :: "cname \<Rightarrow> graph_label"
  sigma_attr_key        :: "(cname \<times> aname) \<Rightarrow> property_key"
  sigma_assoc_type      :: "assoc_name \<Rightarrow> relationship_type"
  sigma_assoc_direction :: "assoc_name \<Rightarrow> edge_direction"


definition rep_spec_wf :: "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> bool" where
  "rep_spec_wf \<Sigma> M \<longleftrightarrow>
     valid_graph_name (sigma_id_key \<Sigma>) \<and>
     inj_on (sigma_class_label \<Sigma>) (class_names M) \<and>
     (\<forall>c\<in>class_names M. valid_graph_name (sigma_class_label \<Sigma> c)) \<and>
     inj_on (sigma_attr_key \<Sigma>) (attr_key_of ` model_attributes M) \<and>
     (\<forall>a\<in>model_attributes M.
        valid_graph_name (sigma_attr_key \<Sigma> (attr_key_of a)) \<and>
        sigma_attr_key \<Sigma> (attr_key_of a) \<noteq> sigma_id_key \<Sigma>) \<and>
     inj_on (sigma_assoc_type \<Sigma>) (assoc_names M) \<and>
     (\<forall>r\<in>assoc_names M. valid_graph_name (sigma_assoc_type \<Sigma> r))"


section \<open>Supported builder fragment\<close>

text \<open>
  In this first builder, UML attributes are stored as scalar Neo4j-style
  properties.  Therefore only primitive attributes with upper multiplicity at
  most one are admitted.  Object-to-object structure is represented by links,
  not by reference-valued node properties.
\<close>

fun scalar_upper :: "multiplicity \<Rightarrow> bool" where
  "scalar_upper m =
     (case mult_upper m of
        Unlimited \<Rightarrow> False
      | Finite u \<Rightarrow> u \<le> 1)"


definition primitive_scalar_attribute :: "attribute_decl \<Rightarrow> bool" where
  "primitive_scalar_attribute a \<longleftrightarrow>
     (\<exists>p. attr_type a = Primitive p) \<and> scalar_upper (attr_mult a)"


definition builder_supported_model :: "'e class_model \<Rightarrow> bool" where
  "builder_supported_model M \<longleftrightarrow>
     (\<forall>a\<in>model_attributes M. primitive_scalar_attribute a)"


definition build_admissible ::
  "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> snapshot \<Rightarrow> bool" where
  "build_admissible \<Sigma> M S \<longleftrightarrow>
     model_conforms MM_Class M \<and>
     snapshot_conforms M S \<and>
     rep_spec_wf \<Sigma> M \<and>
     builder_supported_model M"


section \<open>Source-to-graph primitive conversions\<close>

fun value_to_pg :: "uml_value \<Rightarrow> pg_value option" where
  "value_to_pg (VBool b)   = Some (PGVBoolean b)"
| "value_to_pg (VInt i)    = Some (PGVInteger i)"
| "value_to_pg (VReal r)   = Some (PGVReal r)"
| "value_to_pg (VString s) = Some (PGVString s)"
| "value_to_pg (VObj _)    = None"


fun scalar_value_of :: "uml_value list \<Rightarrow> uml_value option" where
  "scalar_value_of [] = None"
| "scalar_value_of [v] = Some v"
| "scalar_value_of (_ # _ # xs) = None"


definition object_of_id :: "snapshot \<Rightarrow> oid \<Rightarrow> object_instance" where
  "object_of_id S i = (SOME ob. ob\<in>snap_objects S \<and> obj_id ob = i)"


definition attr_of_graph_key ::
  "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> property_key \<Rightarrow> attribute_decl" where
  "attr_of_graph_key \<Sigma> M k =
     (SOME a. a\<in>model_attributes M \<and> sigma_attr_key \<Sigma> (attr_key_of a) = k)"


definition slot_of ::
  "snapshot \<Rightarrow> oid \<Rightarrow> (cname \<times> aname) \<Rightarrow> slot_instance" where
  "slot_of S i ak =
     (SOME sl. sl\<in>snap_slots S \<and> slot_object sl = i \<and> slot_attr sl = ak)"


section \<open>Rule R1: snapshot objects become graph nodes\<close>

text \<open>
  We choose the stable source object identifier itself as the HOL node carrier.
  This makes the object-to-node correspondence explicit: gamma(o) = obj_id o.
\<close>

definition build_nodes :: "snapshot \<Rightarrow> oid set" where
  "build_nodes S = object_ids S"


section \<open>Rule R2: dynamic type and inheritance become node labels\<close>

text \<open>
  A node carries the label of its dynamic class and every declared superclass.
  Hence inheritance-aware class extents can later be observed by label lookup.
\<close>

definition build_labels ::
  "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> snapshot \<Rightarrow> oid \<Rightarrow> graph_label set" where
  "build_labels \<Sigma> M S i =
     (if i \<in> object_ids S then
        {sigma_class_label \<Sigma> c |
           c. c \<in> class_names M \<and>
              subclass_of M (obj_class (object_of_id S i)) c}
      else {})"


section \<open>Rule R3: object identity and scalar slots become node properties\<close>

text \<open>
  The stable source object identifier is materialized under sigma_id_key.
  Each defined scalar slot is materialized under the property key selected by
  Sigma.  An empty optional slot becomes an absent graph property (None).
\<close>

definition applicable_attr_for_key ::
  "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> snapshot \<Rightarrow> oid \<Rightarrow> property_key \<Rightarrow> bool" where
  "applicable_attr_for_key \<Sigma> M S i k \<longleftrightarrow>
     i \<in> object_ids S \<and>
     (\<exists>a\<in>model_attributes M.
        sigma_attr_key \<Sigma> (attr_key_of a) = k \<and>
        applicable_attribute M (obj_class (object_of_id S i)) a)"


definition build_node_property ::
  "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> snapshot \<Rightarrow> oid \<Rightarrow> property_key \<Rightarrow> pg_value option" where
  "build_node_property \<Sigma> M S i k =
     (if i \<notin> object_ids S then None
      else if k = sigma_id_key \<Sigma> then Some (PGVString i)
      else if applicable_attr_for_key \<Sigma> M S i k then
        (let a  = attr_of_graph_key \<Sigma> M k;
             sl = slot_of S i (attr_key_of a)
         in case scalar_value_of (slot_values sl) of
              None \<Rightarrow> None
            | Some v \<Rightarrow> value_to_pg v)
      else None)"


section \<open>Rule R4: snapshot links become graph relationships\<close>

text \<open>
  Each source link is one graph edge.  Sigma chooses the physical relationship
  type and may choose either physical direction.  This direction is independent
  of the logical UML navigation direction used later by OCL compilation.
\<close>

definition build_edges :: "snapshot \<Rightarrow> link_instance set" where
  "build_edges S = snap_links S"


definition build_edge_type :: "rep_spec \<Rightarrow> link_instance \<Rightarrow> relationship_type" where
  "build_edge_type \<Sigma> l = sigma_assoc_type \<Sigma> (link_assoc l)"


definition build_source :: "rep_spec \<Rightarrow> link_instance \<Rightarrow> oid" where
  "build_source \<Sigma> l =
     (case sigma_assoc_direction \<Sigma> (link_assoc l) of
        Forward \<Rightarrow> link_left l
      | Reverse \<Rightarrow> link_right l)"


definition build_target :: "rep_spec \<Rightarrow> link_instance \<Rightarrow> oid" where
  "build_target \<Sigma> l =
     (case sigma_assoc_direction \<Sigma> (link_assoc l) of
        Forward \<Rightarrow> link_right l
      | Reverse \<Rightarrow> link_left l)"


definition build_edge_property ::
  "link_instance \<Rightarrow> property_key \<Rightarrow> pg_value option" where
  "build_edge_property l k = None"


section \<open>The builder Build_Sigma(M,S)\<close>

text \<open>
  The transformation rules above are assembled into one executable HOL
  definition.  G is not supplied as a premise: it is the value returned by
  this function.
\<close>

definition build ::
  "rep_spec \<Rightarrow> 'e class_model \<Rightarrow> snapshot \<Rightarrow> (oid, link_instance) property_graph" where
  "build \<Sigma> M S =
    \<lparr> pg_nodes           = build_nodes S,
      pg_edges           = build_edges S,
      pg_node_labels     = build_labels \<Sigma> M S,
      pg_node_properties = build_node_property \<Sigma> M S,
      pg_edge_type       = build_edge_type \<Sigma>,
      pg_source          = build_source \<Sigma>,
      pg_target          = build_target \<Sigma>,
      pg_edge_properties = build_edge_property
    \<rparr>"


section \<open>Generated-rule equations\<close>

lemma build_nodes_rule[simp]:
  "pg_nodes (build \<Sigma> M S) = object_ids S"
  unfolding build_def build_nodes_def by simp

lemma build_edges_rule[simp]:
  "pg_edges (build \<Sigma> M S) = snap_links S"
  unfolding build_def build_edges_def by simp

lemma build_labels_rule[simp]:
  "pg_node_labels (build \<Sigma> M S) i = build_labels \<Sigma> M S i"
  unfolding build_def by simp

lemma build_node_property_rule[simp]:
  "pg_node_properties (build \<Sigma> M S) i k = build_node_property \<Sigma> M S i k"
  unfolding build_def by simp

lemma build_edge_type_rule[simp]:
  "pg_edge_type (build \<Sigma> M S) l = sigma_assoc_type \<Sigma> (link_assoc l)"
  unfolding build_def build_edge_type_def by simp

lemma build_source_rule[simp]:
  "pg_source (build \<Sigma> M S) l = build_source \<Sigma> l"
  unfolding build_def by simp

lemma build_target_rule[simp]:
  "pg_target (build \<Sigma> M S) l = build_target \<Sigma> l"
  unfolding build_def by simp

lemma build_edge_property_rule[simp]:
  "pg_edge_properties (build \<Sigma> M S) l k = None"
  unfolding build_def build_edge_property_def by simp


section \<open>First correspondence theorem: source objects \<leftrightarrow> graph nodes\<close>

text \<open>
  Because pg_nodes(build Sigma M S) is exactly the image of obj_id over the
  snapshot objects, structural snapshot conformance immediately yields the
  desired one-to-one object/node correspondence.
\<close>

lemma build_object_node_bijection:
  assumes "snapshot_conforms M S"
  shows "bij_betw obj_id (snap_objects S) (pg_nodes (build \<Sigma> M S))"
proof -
  have inj: "inj_on obj_id (snap_objects S)"
    using snapshot_conforms_unique_object_ids[OF assms]
    by (rule unique_by_imp_inj_on)
  have img: "obj_id ` snap_objects S = pg_nodes (build \<Sigma> M S)"
    by (simp add: object_ids_def)
  show ?thesis
    unfolding bij_betw_def
    using inj img by simp
qed


section \<open>Builder correctness obligations\<close>

text \<open>
  The next proof target is structural soundness of the generated graph:

      build_admissible Sigma M S
      ==> graph_conforms MM_Graph (build Sigma M S).

  It is intentionally separate from the stronger representation-adequacy
  theorem.  graph_conforms only states that the generated result is a legal
  property graph; adequacy will additionally prove preservation of types,
  slots, links, inheritance-aware extents, and stable identity.
\<close>

lemma build_finite_nodes:
  assumes "snapshot_conforms M S"
  shows "finite (pg_nodes (build \<Sigma> M S))"
  using snapshot_conforms_finite_objects[OF assms]
  by (simp add: object_ids_def)

lemma build_finite_edges:
  assumes "snapshot_conforms M S"
  shows "finite (pg_edges (build \<Sigma> M S))"
  using snapshot_conforms_finite_links[OF assms] by simp


subsection \<open>Facts used by structural soundness\<close>

lemma model_conforms_finite_class_names:
  assumes "model_conforms MM_Class M"
  shows "finite (class_names M)"
  using assms unfolding model_conforms_def class_names_def by simp

lemma model_conforms_finite_attribute_keys:
  assumes "model_conforms MM_Class M"
  shows "finite (attr_key_of ` model_attributes M)"
  using assms unfolding model_conforms_def by simp

lemma snapshot_conforms_link_wf:
  assumes "snapshot_conforms M S" "l \<in> snap_links S"
  shows "link_wf M S l"
  using assms unfolding snapshot_conforms_def by blast

lemma link_wf_assoc_name:
  assumes "link_wf M S l"
  shows "link_assoc l \<in> assoc_names M"
proof -
  obtain a where A: "a \<in> model_associations M"
      "assoc_key a = link_assoc l"
    using assms unfolding link_wf_def by blast
  show ?thesis
    unfolding assoc_names_def using A by force
qed

lemma link_wf_endpoint_ids:
  assumes "link_wf M S l"
  shows "link_left l \<in> object_ids S \<and> link_right l \<in> object_ids S"
proof -
  obtain lo ro where LO: "lo \<in> snap_objects S" "obj_id lo = link_left l"
      and RO: "ro \<in> snap_objects S" "obj_id ro = link_right l"
    using assms unfolding link_wf_def by blast
  show ?thesis
    unfolding object_ids_def using LO RO by force
qed

lemma rep_spec_wf_id_name:
  assumes "rep_spec_wf \<Sigma> M"
  shows "valid_graph_name (sigma_id_key \<Sigma>)"
  using assms unfolding rep_spec_wf_def by blast

lemma rep_spec_wf_class_label:
  assumes "rep_spec_wf \<Sigma> M" "c \<in> class_names M"
  shows "valid_graph_name (sigma_class_label \<Sigma> c)"
  using assms unfolding rep_spec_wf_def by blast

lemma rep_spec_wf_attribute_key:
  assumes "rep_spec_wf \<Sigma> M" "a \<in> model_attributes M"
  shows "valid_graph_name (sigma_attr_key \<Sigma> (attr_key_of a))"
  using assms unfolding rep_spec_wf_def by blast

lemma rep_spec_wf_association_type:
  assumes "rep_spec_wf \<Sigma> M" "r \<in> assoc_names M"
  shows "valid_graph_name (sigma_assoc_type \<Sigma> r)"
  using assms unfolding rep_spec_wf_def by blast

lemma build_labels_finite:
  assumes "model_conforms MM_Class M"
  shows "finite (build_labels \<Sigma> M S i)"
proof (cases "i \<in> object_ids S")
  case False
  then show ?thesis unfolding build_labels_def by simp
next
  case True
  have "finite {c \<in> class_names M.
          subclass_of M (obj_class (object_of_id S i)) c}"
    using model_conforms_finite_class_names[OF assms] by simp
  then show ?thesis
    unfolding build_labels_def using True by simp
qed

lemma build_label_name_valid:
  assumes "rep_spec_wf \<Sigma> M" "l \<in> build_labels \<Sigma> M S i"
  shows "valid_graph_name l"
proof -
  have NODE: "i \<in> object_ids S"
  proof (rule ccontr)
    assume "i \<notin> object_ids S"
    with assms(2) show False
      unfolding build_labels_def by simp
  qed
  have LABEL_SET:
    "{sigma_class_label \<Sigma> c | c.
        c \<in> class_names M \<and>
        subclass_of M (obj_class (object_of_id S i)) c}
     = sigma_class_label \<Sigma> `
        {c. c \<in> class_names M \<and>
            subclass_of M (obj_class (object_of_id S i)) c}"
    by (rule setcompr_eq_image)
  have "l \<in> sigma_class_label \<Sigma> `
        {c. c \<in> class_names M \<and>
            subclass_of M (obj_class (object_of_id S i)) c}"
    using assms(2) NODE LABEL_SET unfolding build_labels_def by simp
  then have "\<exists>c. c \<in> class_names M \<and> l = sigma_class_label \<Sigma> c"
    by auto
  then obtain c where C: "c \<in> class_names M"
      and L: "l = sigma_class_label \<Sigma> c" by blast
  show ?thesis
    unfolding L using rep_spec_wf_class_label[OF assms(1) C] .
qed

lemma build_node_property_keys_subset:
  "{k. build_node_property \<Sigma> M S i k \<noteq> None}
     \<subseteq> {sigma_id_key \<Sigma>} \<union>
        sigma_attr_key \<Sigma> ` (attr_key_of ` model_attributes M)"
  unfolding build_node_property_def applicable_attr_for_key_def
  by (auto split: if_splits option.splits)

lemma build_node_property_keys_finite:
  assumes "model_conforms MM_Class M"
  shows "finite {k. build_node_property \<Sigma> M S i k \<noteq> None}"
proof (rule finite_subset[OF build_node_property_keys_subset])
  show "finite ({sigma_id_key \<Sigma>} \<union>
      sigma_attr_key \<Sigma> ` (attr_key_of ` model_attributes M))"
    using model_conforms_finite_attribute_keys[OF assms] by simp
qed

lemma build_node_property_name_valid:
  assumes REP: "rep_spec_wf \<Sigma> M"
      and SOME: "build_node_property \<Sigma> M S i k = Some v"
  shows "valid_graph_name k"
proof -
  have "k \<in> {sigma_id_key \<Sigma>} \<union>
      sigma_attr_key \<Sigma> ` (attr_key_of ` model_attributes M)"
    using build_node_property_keys_subset SOME by blast
  then show ?thesis
    using rep_spec_wf_id_name[OF REP]
          rep_spec_wf_attribute_key[OF REP] by auto
qed

lemma value_to_pg_supported:
  assumes "value_to_pg v = Some p"
  shows "pg_value_supported MM_Graph p"
  using assms
  by (cases v; auto simp: pg_value_supported_def MM_Graph_def)

lemma all_pg_values_supported[simp]:
  "pg_value_supported MM_Graph p"
  by (cases p; simp add: pg_value_supported_def MM_Graph_def)

lemma build_node_property_supported:
  assumes "build_node_property \<Sigma> M S i k = Some p"
  shows "pg_value_supported MM_Graph p"
  by simp

lemma build_edge_endpoint_nodes:
  assumes SNAP: "snapshot_conforms M S" and LINK: "l \<in> snap_links S"
  shows "build_source \<Sigma> l \<in> object_ids S \<and>
         build_target \<Sigma> l \<in> object_ids S"
proof -
  have ENDS: "link_left l \<in> object_ids S \<and>
              link_right l \<in> object_ids S"
    using link_wf_endpoint_ids snapshot_conforms_link_wf SNAP LINK by blast
  show ?thesis
    using ENDS unfolding build_source_def build_target_def
    by (cases "sigma_assoc_direction \<Sigma> (link_assoc l)"; simp)
qed


subsection \<open>Structural soundness of the generated graph\<close>

lemma build_node_wf:
  assumes MODEL: "model_conforms MM_Class M"
      and REP: "rep_spec_wf \<Sigma> M"
      and NODE: "n \<in> pg_nodes (build \<Sigma> M S)"
  shows "node_wf MM_Graph (build \<Sigma> M S) n"
proof -
  have LABELS_FIN: "finite (build_labels \<Sigma> M S n)"
    using build_labels_finite[OF MODEL] .
  have PROPS_FIN: "finite {k. build_node_property \<Sigma> M S n k \<noteq> None}"
    using build_node_property_keys_finite[OF MODEL] .
  have LABEL_NAMES:
    "\<forall>l\<in>build_labels \<Sigma> M S n. valid_graph_name l"
    using build_label_name_valid[OF REP] by blast
  have PROP_NAMES:
    "\<forall>k\<in>{k. build_node_property \<Sigma> M S n k \<noteq> None}.
       valid_graph_name k"
    using build_node_property_name_valid[OF REP] by auto
  have PROP_TYPES:
    "\<forall>k v. build_node_property \<Sigma> M S n k = Some v \<longrightarrow>
       pg_value_supported MM_Graph v"
    using build_node_property_supported by blast
  show ?thesis
    unfolding node_wf_def node_property_keys_def
    using NODE LABELS_FIN PROPS_FIN LABEL_NAMES PROP_NAMES PROP_TYPES
    by (simp add: MM_Graph_def)
qed

lemma build_relationship_wf:
  assumes SNAP: "snapshot_conforms M S"
      and REP: "rep_spec_wf \<Sigma> M"
      and EDGE: "e \<in> pg_edges (build \<Sigma> M S)"
  shows "relationship_wf MM_Graph (build \<Sigma> M S) e"
proof -
  have LINK: "e \<in> snap_links S" using EDGE by simp
  have WF: "link_wf M S e"
    using snapshot_conforms_link_wf[OF SNAP LINK] .
  have TYPE: "valid_graph_name (sigma_assoc_type \<Sigma> (link_assoc e))"
    using rep_spec_wf_association_type[OF REP link_wf_assoc_name[OF WF]] .
  have ENDS: "build_source \<Sigma> e \<in> object_ids S \<and>
              build_target \<Sigma> e \<in> object_ids S"
    using build_edge_endpoint_nodes[OF SNAP LINK] .
  show ?thesis
    unfolding relationship_wf_def edge_property_keys_def MM_Graph_def
    using EDGE TYPE ENDS by auto
qed

theorem build_graph_conforms:
  assumes "build_admissible \<Sigma> M S"
  shows "graph_conforms MM_Graph (build \<Sigma> M S)"
proof -
  have MODEL: "model_conforms MM_Class M"
    and SNAP: "snapshot_conforms M S"
    and REP: "rep_spec_wf \<Sigma> M"
    using assms unfolding build_admissible_def by blast+
  have FIN_N: "finite (pg_nodes (build \<Sigma> M S))"
    using build_finite_nodes[OF SNAP] .
  have FIN_E: "finite (pg_edges (build \<Sigma> M S))"
    using build_finite_edges[OF SNAP] .
  have NODES: "\<forall>n\<in>pg_nodes (build \<Sigma> M S).
      node_wf MM_Graph (build \<Sigma> M S) n"
    using build_node_wf[OF MODEL REP] by blast
  have EDGES: "\<forall>e\<in>pg_edges (build \<Sigma> M S).
      relationship_wf MM_Graph (build \<Sigma> M S) e"
    using build_relationship_wf[OF SNAP REP] by blast
  show ?thesis
    unfolding graph_conforms_def
    using FIN_N FIN_E NODES EDGES by (simp add: MM_Graph_def)
qed

end
