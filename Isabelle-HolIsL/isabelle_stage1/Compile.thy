theory Compile
  imports Cypher
begin

section \<open>Whole OCL-to-query compiler\<close>

text \<open>
  Compilation succeeds only across the complete admission boundary:

    1. M conforms to MM_Class;
    2. every OCL invariant in M is well typed/resolvable against M and belongs
       to the published supported_expr profile;
    3. Sigma is a well-formed representation specification for M;
    4. Front resolves the selected invariant;
    5. lower_invariant generates the graph-query AST.

  In particular, an OCL expression that merely parses but references the wrong
  attribute/role/class cannot be compiled.
\<close>


definition compiler_admissible :: "rep_spec \<Rightarrow> ocl_model \<Rightarrow> bool" where
  "compiler_admissible \<Sigma> M \<longleftrightarrow>
     ocl_model_conforms M \<and>
     rep_spec_wf \<Sigma> M \<and>
     builder_supported_model M"


definition compile_invariant ::
  "rep_spec \<Rightarrow> ocl_model \<Rightarrow> ocl_invariant \<Rightarrow> graph_query option" where
  "compile_invariant \<Sigma> M I =
     (if compiler_admissible \<Sigma> M then
        (case front M I of
           None \<Rightarrow> None
         | Some CI \<Rightarrow> Some (lower_invariant \<Sigma> CI))
      else None)"


definition compile_all ::
  "rep_spec \<Rightarrow> ocl_model \<Rightarrow> (inv_name \<times> graph_query) set" where
  "compile_all \<Sigma> M =
     {(inv_key I,q) | I q.
        I \<in> model_invariants M \<and> compile_invariant \<Sigma> M I = Some q}"


section \<open>Compiler admission and rejection theorems\<close>

lemma compile_success_model_conforms:
  assumes "compile_invariant \<Sigma> M I = Some q"
  shows "model_conforms MM_Class M"
proof -
  from assms have "compiler_admissible \<Sigma> M"
    unfolding compile_invariant_def
    by (auto split: if_splits option.splits)
  hence "ocl_model_conforms M"
    unfolding compiler_admissible_def by blast
  then show ?thesis
    using ocl_model_conforms_model by blast
qed

lemma compile_success_rep_spec_wf:
  assumes "compile_invariant \<Sigma> M I = Some q"
  shows "rep_spec_wf \<Sigma> M"
  using assms unfolding compile_invariant_def compiler_admissible_def
  by (auto split: if_splits option.splits)

lemma compile_success_invariant_wf:
  assumes "compile_invariant \<Sigma> M I = Some q"
  shows "invariant_wf M I"
proof -
  obtain CI where F: "front M I = Some CI"
    using assms unfolding compile_invariant_def
    by (auto split: if_splits option.splits)
  show ?thesis using front_success_implies_invariant_wf[OF F] .
qed

lemma compile_rejects_ill_typed_ocl:
  assumes "\<not> invariant_wf M I"
  shows "compile_invariant \<Sigma> M I = None"
proof (cases "compiler_admissible \<Sigma> M")
  case False
  then show ?thesis unfolding compile_invariant_def by simp
next
  case True
  moreover have "front M I = None"
    using front_rejects_invalid[OF assms] .
  ultimately show ?thesis unfolding compile_invariant_def by simp
qed

lemma compile_success_has_boolean_source_body:
  assumes "compile_invariant \<Sigma> M I = Some q"
  shows "infer_type M (inv_context I) [] (inv_body I) = Some OTBool"
  using invariant_wf_boolean compile_success_invariant_wf assms by blast

lemma compile_success_has_supported_source_body:
  assumes "compile_invariant \<Sigma> M I = Some q"
  shows "supported_expr M (inv_context I) [] (inv_body I)"
  using invariant_wf_supported compile_success_invariant_wf assms by blast


section \<open>Generated-query correspondence\<close>

lemma compile_success_is_lowered_front:
  assumes "compile_invariant \<Sigma> M I = Some q"
  obtains CI where
    "front M I = Some CI"
    "q = lower_invariant \<Sigma> CI"
  using assms unfolding compile_invariant_def
  by (auto split: if_splits option.splits)

lemma compile_success_preserves_invariant_name:
  assumes "compile_invariant \<Sigma> M I = Some q"
  shows "query_name q = inv_key I"
proof -
  obtain CI where F: "front M I = Some CI" and Q: "q = lower_invariant \<Sigma> CI"
    using compile_success_is_lowered_front[OF assms] by blast
  have "core_inv_name CI = inv_key I"
    using front_preserves_name[OF F] .
  with Q show ?thesis unfolding lower_invariant_def by simp
qed

lemma compile_success_context_label:
  assumes "compile_invariant \<Sigma> M I = Some q"
  shows "query_context_label q = sigma_class_label \<Sigma> (inv_context I)"
proof -
  obtain CI where F: "front M I = Some CI" and Q: "q = lower_invariant \<Sigma> CI"
    using compile_success_is_lowered_front[OF assms] by blast
  have "core_inv_context CI = inv_context I"
    using front_preserves_context[OF F] .
  with Q show ?thesis unfolding lower_invariant_def by simp
qed

lemma compile_success_return_id_key:
  assumes "compile_invariant \<Sigma> M I = Some q"
  shows "query_return_id_key q = sigma_id_key \<Sigma>"
  using assms unfolding compile_invariant_def lower_invariant_def
  by (auto split: if_splits option.splits)


section \<open>Final OCL-to-Cypher-AST compiler\<close>


definition compile_cypher ::
  "rep_spec \<Rightarrow> ocl_model \<Rightarrow> ocl_invariant \<Rightarrow> cypher_query option" where
  "compile_cypher \<Sigma> M I =
     (case compile_invariant \<Sigma> M I of
        None \<Rightarrow> None
      | Some q \<Rightarrow> Some (realize_query q))"


definition compile_all_cypher ::
  "rep_spec \<Rightarrow> ocl_model \<Rightarrow> (inv_name \<times> cypher_query) set" where
  "compile_all_cypher \<Sigma> M =
     {(inv_key I,cq) | I cq.
        I \<in> model_invariants M \<and> compile_cypher \<Sigma> M I = Some cq}"

lemma compile_cypher_success_implies_valid_ocl:
  assumes "compile_cypher \<Sigma> M I = Some cq"
  shows "invariant_wf M I"
proof -
  obtain q where "compile_invariant \<Sigma> M I = Some q"
    using assms unfolding compile_cypher_def by (auto split: option.splits)
  thus ?thesis using compile_success_invariant_wf by blast
qed

lemma compile_cypher_rejects_invalid_ocl:
  assumes "\<not> invariant_wf M I"
  shows "compile_cypher \<Sigma> M I = None"
  using compile_rejects_ill_typed_ocl[OF assms]
  unfolding compile_cypher_def by simp

lemma compile_cypher_context:
  assumes "compile_cypher \<Sigma> M I = Some cq"
  shows "cy_context_label cq = sigma_class_label \<Sigma> (inv_context I)"
proof -
  obtain q where Q: "compile_invariant \<Sigma> M I = Some q" and CQ: "cq = realize_query q"
    using assms unfolding compile_cypher_def by (auto split: option.splits)
  show ?thesis using CQ compile_success_context_label[OF Q] by simp
qed

lemma compile_cypher_return_id:
  assumes "compile_cypher \<Sigma> M I = Some cq"
  shows "cy_return_id_key cq = sigma_id_key \<Sigma>"
proof -
  obtain q where Q: "compile_invariant \<Sigma> M I = Some q" and CQ: "cq = realize_query q"
    using assms unfolding compile_cypher_def by (auto split: option.splits)
  show ?thesis using CQ compile_success_return_id_key[OF Q] by simp
qed

lemma compile_cypher_violation_wrapper:
  assumes "compile_cypher \<Sigma> M I = Some cq"
  shows "cy_keep_non_true cq \<and> cy_return_distinct cq"
  using assms unfolding compile_cypher_def by (auto split: option.splits)


section \<open>Compiler totality on admitted invariants\<close>

lemma compile_total_on_admitted_invariant:
  assumes "ocl_model_conforms M"
      and "rep_spec_wf \<Sigma> M"
      and "builder_supported_model M"
      and "I \<in> model_invariants M"
  shows "\<exists>q. compile_invariant \<Sigma> M I = Some q"
proof -
  have WF: "invariant_wf M I"
    using ocl_model_conforms_invariant assms(1,4) by blast
  hence "\<exists>CI. front M I = Some CI"
    unfolding front_def by simp
  then show ?thesis
    using assms(1,2,3) unfolding compile_invariant_def compiler_admissible_def by auto
qed

definition generated_queries :: "rep_spec \<Rightarrow> ocl_model \<Rightarrow> graph_query set" where
  "generated_queries \<Sigma> M = snd ` compile_all \<Sigma> M"

lemma admitted_model_generates_query_for_each_invariant:
  assumes "ocl_model_conforms M"
      and "rep_spec_wf \<Sigma> M"
      and "builder_supported_model M"
      and "I \<in> model_invariants M"
  shows "\<exists>q. (inv_key I,q) \<in> compile_all \<Sigma> M"
proof -
  obtain q where Q: "compile_invariant \<Sigma> M I = Some q"
    using compile_total_on_admitted_invariant[OF assms] by blast
  show ?thesis
    unfolding compile_all_def using assms(4) Q by blast
qed

lemma admitted_model_generates_nonempty_query_set_if_invariants:
  assumes "ocl_model_conforms M"
      and "rep_spec_wf \<Sigma> M"
      and "builder_supported_model M"
      and "model_invariants M \<noteq> {}"
  shows "generated_queries \<Sigma> M \<noteq> {}"
proof -
  obtain I where I: "I \<in> model_invariants M"
    using assms(4) by blast
  obtain q where "(inv_key I,q) \<in> compile_all \<Sigma> M"
    using admitted_model_generates_query_for_each_invariant[OF assms(1,2,3) I] by blast
  hence "q \<in> generated_queries \<Sigma> M"
    unfolding generated_queries_def by force
  thus ?thesis by blast
qed


text \<open>
  This theory establishes source admission and syntactic generation.  The
  semantic layer and its explicit builder-adequacy boundary are developed in
  Semantics.thy rather than hidden inside compile_invariant.
\<close>

end
