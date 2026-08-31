package io.amscotti.janus.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * CLI composition-root boundary: {@code janus-cli} may depend on the gateway module
 * (including root-package {@code JanusApplication}) but must not reach around it into
 * core/provider/router/store. Companion to {@code io.amscotti.janus.ArchitectureTest}
 * (gateway classpath; CLI is not visible there).
 */
@AnalyzeClasses(packages = "io.amscotti.janus.cli", importOptions = ImportOption.DoNotIncludeTests.class)
class CliArchitectureTest {

    @ArchTest
    static final ArchRule cliDoesNotBypassGateway = noClasses()
            .that()
            .resideInAPackage("io.amscotti.janus.cli..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.amscotti.janus.core..",
                    "io.amscotti.janus.provider..",
                    "io.amscotti.janus.router..",
                    "io.amscotti.janus.store..")
            .because("janus-cli is the composition root and depends on gateway only");

    @ArchTest
    static final ArchRule cliPackageIsJanusCliOnly = classes()
            .that()
            .resideInAPackage("io.amscotti.janus.cli")
            .and()
            .areTopLevelClasses()
            .and()
            .haveNameNotMatching(".*\\$.*")
            .should()
            .haveSimpleName("JanusCli")
            .because("janus-cli is a single composition-root class");
}
