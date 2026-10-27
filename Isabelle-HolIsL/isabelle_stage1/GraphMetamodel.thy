theory GraphMetamodel
  imports ClassMetamodel
begin

section \<open>Two-level target-side property-graph metamodel\<close>

type_synonym graph_label = string
type_synonym relationship_type = string
type_synonym property_key = string

datatype pg_type = PGBoolean | PGInteger | PGReal | PGString

datatype pg_value =
    PGVBoolean bool
  | PGVInteger int
  | PGVReal real
  | PGVString string

fun type_of_pg_value :: "pg_value \<Rightarrow> pg_type" where
  "type_of_pg_value (PGVBoolean _) = PGBoolean"
| "type_of_pg_value (PGVInteger _) = PGInteger"
| "type_of_pg_value (PGVReal _)    = PGReal"
| "type_of_pg_value (PGVString _)  = PGString"

record ('node, 'edge) property_graph =
  pg_nodes           :: "'node set"
  pg_edges           :: "'edge set"
  pg_node_labels     :: "'node \<Rightarrow> graph_label set"
  pg_node_properties :: "'node \<Rightarrow> property_key \<Rightarrow> pg_value option"
  pg_edge_type       :: "'edge \<Rightarrow> relationship_type"
  pg_source          :: "'edge \<Rightarrow> 'node"
  pg_target          :: "'edge \<Rightarrow> 'node"
  pg_edge_properties :: "'edge \<Rightarrow> property_key \<Rightarrow> pg_value option"

definition node_property_keys ::
  "('n, 'e) property_graph \<Rightarrow> 'n \<Rightarrow> property_key set" where
  "node_property_keys G n = {k. pg_node_properties G n k \<noteq> None}"

definition edge_property_keys ::
  "('n, 'e) property_graph \<Rightarrow> 'e \<Rightarrow> property_key set" where
  "edge_property_keys G e = {k. pg_edge_properties G e k \<noteq> None}"

record graph_metamodel =
  mm_pg_property_types                :: "pg_type set"
  mm_pg_require_finite_graph          :: bool
  mm_pg_allow_unlabelled_nodes        :: bool
  mm_pg_allow_multiple_labels         :: bool
  mm_pg_allow_relationship_properties :: bool
  mm_pg_allow_self_relationships      :: bool

definition MM_Graph :: graph_metamodel where
  "MM_Graph =
    \<lparr> mm_pg_property_types = {PGBoolean, PGInteger, PGReal, PGString},
      mm_pg_require_finite_graph = True,
      mm_pg_allow_unlabelled_nodes = True,
      mm_pg_allow_multiple_labels = True,
      mm_pg_allow_relationship_properties = True,
      mm_pg_allow_self_relationships = True
    \<rparr>"

definition valid_graph_name :: "string \<Rightarrow> bool" where
  "valid_graph_name s \<longleftrightarrow> s \<noteq> []"

definition pg_value_supported :: "graph_metamodel \<Rightarrow> pg_value \<Rightarrow> bool" where
  "pg_value_supported MM v \<longleftrightarrow> type_of_pg_value v \<in> mm_pg_property_types MM"

definition node_wf ::
  "graph_metamodel \<Rightarrow> ('n, 'e) property_graph \<Rightarrow> 'n \<Rightarrow> bool" where
  "node_wf MM G n \<longleftrightarrow>
     n \<in> pg_nodes G \<and>
     finite (pg_node_labels G n) \<and>
     finite (node_property_keys G n) \<and>
     (\<forall>l\<in>pg_node_labels G n. valid_graph_name l) \<and>
     (\<forall>k\<in>node_property_keys G n. valid_graph_name k) \<and>
     (\<forall>k v. pg_node_properties G n k = Some v \<longrightarrow> pg_value_supported MM v) \<and>
     (mm_pg_allow_unlabelled_nodes MM \<or> pg_node_labels G n \<noteq> {}) \<and>
     (mm_pg_allow_multiple_labels MM \<or> card (pg_node_labels G n) \<le> 1)"

definition relationship_wf ::
  "graph_metamodel \<Rightarrow> ('n, 'e) property_graph \<Rightarrow> 'e \<Rightarrow> bool" where
  "relationship_wf MM G e \<longleftrightarrow>
     e \<in> pg_edges G \<and>
     valid_graph_name (pg_edge_type G e) \<and>
     pg_source G e \<in> pg_nodes G \<and>
     pg_target G e \<in> pg_nodes G \<and>
     finite (edge_property_keys G e) \<and>
     (\<forall>k\<in>edge_property_keys G e. valid_graph_name k) \<and>
     (\<forall>k v. pg_edge_properties G e k = Some v \<longrightarrow> pg_value_supported MM v) \<and>
     (mm_pg_allow_relationship_properties MM \<or> edge_property_keys G e = {}) \<and>
     (mm_pg_allow_self_relationships MM \<or> pg_source G e \<noteq> pg_target G e)"

definition graph_conforms ::
  "graph_metamodel \<Rightarrow> ('n, 'e) property_graph \<Rightarrow> bool" where
  "graph_conforms MM G \<longleftrightarrow>
     (\<not> mm_pg_require_finite_graph MM \<or>
        (finite (pg_nodes G) \<and> finite (pg_edges G))) \<and>
     (\<forall>n\<in>pg_nodes G. node_wf MM G n) \<and>
     (\<forall>e\<in>pg_edges G. relationship_wf MM G e)"

abbreviation valid_property_graph :: "('n, 'e) property_graph \<Rightarrow> bool" where
  "valid_property_graph G \<equiv> graph_conforms MM_Graph G"

end
