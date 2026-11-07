package org.example.agent.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * 架构规则（项目规约 §1.1 / §2.1 / §2.3 / §10.1）。
 *
 * <p>编号对应《规约自动化检查》§2 映射表。
 */
@AnalyzeClasses(packages = "org.example.agent", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /** A1：依赖单向 —— Gateway → Runtime → Tools/Governance → Domain ← Executor，Infra 只依赖 Domain。 */
    @ArchTest
    static final ArchRule A1_layering_is_one_way = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Gateway").definedBy("..agent.gateway..")
            .layer("Runtime").definedBy("..agent.runtime..")
            .layer("Tools").definedBy("..agent.tools..")
            .layer("Governance").definedBy("..agent.governance..")
            .layer("Executor").definedBy("..agent.executor..")
            .layer("Domain").definedBy("..agent.domain..")
            .layer("Infra").definedBy("..agent.infra..")
            .whereLayer("Gateway").mayOnlyAccessLayers("Runtime", "Tools", "Governance", "Domain", "Infra")
            .whereLayer("Runtime").mayOnlyAccessLayers("Tools", "Governance", "Domain", "Infra")
            .whereLayer("Tools").mayOnlyAccessLayers("Domain")
            .whereLayer("Governance").mayOnlyAccessLayers("Domain")
            .whereLayer("Executor").mayOnlyAccessLayers("Domain")
            .whereLayer("Domain").mayNotAccessAnyLayer()
            .whereLayer("Infra").mayOnlyAccessLayers("Domain")
            .because("项目规约 §2.1：依赖必须单向；tools 只能依赖 domain（执行器通过 domain 端口接入）");

    /** A2：domain 层与框架无关（§2.3）。 */
    @ArchTest
    static final ArchRule A2_domain_is_framework_free = noClasses()
            .that().resideInAPackage("..agent.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "org.apache.ibatis..",
                    "io.agentscope..",
                    "dev.langchain4j..",
                    "jakarta.persistence..",
                    "com.baomidou..")
            .because("项目规约 §2.3：domain 必须与框架无关");

    /** A3：执行面不含 LLM（§1.1 A3）。 */
    @ArchTest
    static final ArchRule A3_executor_has_no_llm = noClasses()
            .that().resideInAPackage("..agent.executor..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.agentscope..", "dev.langchain4j..", "org.springframework.ai..")
            .because("项目规约 §1.1 A3：执行面必须是确定性代码");

    /** A4：AI 决策面不含容器/编排客户端（§1.1 A1）。 */
    @ArchTest
    static final ArchRule A4_decision_plane_has_no_runtime_clients = noClasses()
            .that().resideInAnyPackage("..agent.gateway..", "..agent.runtime..",
                    "..agent.tools..", "..agent.governance..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.github.dockerjava..", "io.fabric8..")
            .because("项目规约 §1.1 A1：Docker/K8s 客户端只能出现在执行面");

    /** A6：禁止无领域语义的工具包（§2.2）。 */
    @ArchTest
    static final ArchRule A6_no_grab_bag_packages = noClasses()
            .should().resideInAnyPackage("..util..", "..utils..", "..common..", "..helper..", "..helpers..")
            .because("项目规约 §2.2：禁止 util/common/helper，请归入所属领域");

    /** A7：tools 层不得直接起进程（§4）。 */
    @ArchTest
    static final ArchRule A7_tools_cannot_spawn_processes = noClasses()
            .that().resideInAPackage("..agent.tools..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
            .because("项目规约 §4：命令执行只能出现在执行面");

    /** A9：禁止字段注入（§10.1）。 */
    @ArchTest
    static final ArchRule A9_no_field_injection = noFields()
            .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .because("项目规约 §10.1：必须使用构造器注入");
}
