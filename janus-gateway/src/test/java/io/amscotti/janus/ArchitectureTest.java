package io.amscotti.janus;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Executable module-boundary contract from AGENTS.md.
 *
 * <p>Gradle multi-module deps already prevent compile-time edges that are not declared;
 * ArchUnit re-checks the <em>package</em> dependency graph on the gateway test classpath
 * (core + provider + router + store + gateway main classes). That catches:
 *
 * <ul>
 * <li>accidental cross-module type references that might sneak in via reflection-shaped
 * wiring or future classpath mistakes
 * <li>Micrometer / Micronaut / {@code jakarta.inject} only in the gateway
 * <li>Jackson only in core (codecs) and gateway (HTTP DTOs)
 * <li>SLF4J only in the gateway; JDBC / HikariCP / Postgres driver only in store
 * <li>JDK {@code HttpClient} only in provider (upstream) and gateway (webhook)
 * <li>no Spring, no ORM, no Joda-Time, no {@code java.util.logging}, no runtime
 *     reflection
 * <li>SPI and naming placement: adapters, codecs, LBs, controllers, factories,
 *     store seams, notifiers, publishers
 * <li>canonical model is records / sealed interfaces / enums
 * <li>gateway root package is boot + config only
 * <li>cycles between the five product packages
 * </ul>
 *
 * <p>{@code janus-cli} is not on this classpath — see {@code io.amscotti.janus.cli.CliArchitectureTest}.
 *
 * <p>Production classes only ({@link ImportOption.DoNotIncludeTests}).
 */
@AnalyzeClasses(packages = "io.amscotti.janus", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String CORE = "io.amscotti.janus.core..";
    private static final String PROVIDER = "io.amscotti.janus.provider..";
    private static final String ROUTER = "io.amscotti.janus.router..";
    private static final String STORE = "io.amscotti.janus.store..";
    private static final String GATEWAY = "io.amscotti.janus.gateway..";
    /** Application + config records live in the root package of the gateway module. */
    private static final String GATEWAY_ROOT = "io.amscotti.janus";

    /**
     * Layer map matching the Gradle module table. Access rules encode both "who may
     * depend on this layer" and "what this layer may depend on among Janus packages".
     */
    @ArchTest
    static final ArchRule moduleLayers = layeredArchitecture()
            .consideringOnlyDependenciesInAnyPackage("io.amscotti.janus..")
            .layer("core")
            .definedBy(CORE)
            .layer("provider")
            .definedBy(PROVIDER)
            .layer("router")
            .definedBy(ROUTER)
            .layer("store")
            .definedBy(STORE)
            .layer("gateway")
            .definedBy(GATEWAY, GATEWAY_ROOT)
            .whereLayer("core")
            .mayOnlyBeAccessedByLayers("provider", "router", "store", "gateway")
            .whereLayer("provider")
            .mayOnlyBeAccessedByLayers("gateway")
            .whereLayer("router")
            .mayOnlyBeAccessedByLayers("gateway")
            .whereLayer("store")
            .mayOnlyBeAccessedByLayers("gateway")
            .whereLayer("gateway")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("core")
            .mayNotAccessAnyLayer()
            .whereLayer("provider")
            .mayOnlyAccessLayers("core")
            .whereLayer("router")
            .mayOnlyAccessLayers("core")
            .whereLayer("store")
            .mayOnlyAccessLayers("core")
            .whereLayer("gateway")
            .mayOnlyAccessLayers("core", "provider", "router", "store")
            .because("core ← provider|router|store ← gateway; " + "router never imports provider (ChatBackend only)");

    /** Only the gateway module may take a Micrometer dependency. */
    @ArchTest
    static final ArchRule onlyGatewayUsesMicrometer = noClasses()
            .that()
            .resideInAnyPackage(CORE, PROVIDER, ROUTER, STORE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.micrometer..")
            .because("Micrometer stays in the gateway; libraries stay meter-free");

    /** Core, provider, router, store stay Micronaut-free (pure libraries). */
    @ArchTest
    static final ArchRule onlyGatewayUsesMicronaut = noClasses()
            .that()
            .resideInAnyPackage(CORE, PROVIDER, ROUTER, STORE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.micronaut..")
            .because("Micronaut is the gateway HTTP/DI shell only");

    /**
     * Sibling libraries must not reach into each other. Explicit even though
     * {@link #moduleLayers} already encodes this — fails with a clearer
     * package-level message when someone adds a provider→router import.
     */
    @ArchTest
    static final ArchRule providerDoesNotImportRouterOrStore = noClasses()
            .that()
            .resideInAPackage(PROVIDER)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(ROUTER, STORE, GATEWAY, GATEWAY_ROOT + ".cli..")
            .because("janus-provider depends on core only");

    @ArchTest
    static final ArchRule routerDoesNotImportProviderOrStore = noClasses()
            .that()
            .resideInAPackage(ROUTER)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(PROVIDER, STORE, GATEWAY, GATEWAY_ROOT + ".cli..")
            .because("janus-router depends on core only (ChatBackend SPI, no provider types)");

    @ArchTest
    static final ArchRule storeDoesNotImportProviderOrRouter = noClasses()
            .that()
            .resideInAPackage(STORE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(PROVIDER, ROUTER, GATEWAY, GATEWAY_ROOT + ".cli..")
            .because("janus-store depends on core only (+ third-party JDBC)");

    @ArchTest
    static final ArchRule coreDependsOnNothingInternal = noClasses()
            .that()
            .resideInAPackage(CORE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(PROVIDER, ROUTER, STORE, GATEWAY, GATEWAY_ROOT + ".cli..")
            .because("janus-core depends on nothing internal");

    /** No package cycles among the first segment under {@code io.amscotti.janus}. */
    @ArchTest
    static final ArchRule noCyclesBetweenModulePackages = slices().matching("io.amscotti.janus.(*)..")
            .should()
            .beFreeOfCycles()
            .because("module packages must form a DAG (core → … → gateway)");

    @ArchTest
    static final ArchRule noSpring = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .because("Janus is Micronaut, not Spring");

    @ArchTest
    static final ArchRule noOrm = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "javax.persistence..", "jakarta.persistence..", "org.hibernate..", "org.springframework.data..")
            .because("no ORM — store is explicit JDBC + records");

    @ArchTest
    static final ArchRule noRuntimeReflection = noClasses()
            .that()
            .haveNameNotMatching(".*\\$Definition.*")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.lang.reflect..")
            .because("native-image discipline: no runtime reflection (AGENTS.md); "
                    + "Micronaut-generated $Definition$Exec beans are exempt");

    @ArchTest
    static final ArchRule jacksonStaysInCoreAndGateway = noClasses()
            .that()
            .resideInAnyPackage(PROVIDER, ROUTER, STORE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.fasterxml.jackson..", "tools.jackson..")
            .because("Jackson is for codecs (core) and HTTP DTOs (gateway); " + "provider probes JSON without Jackson");

    @ArchTest
    static final ArchRule slf4jStaysInGateway = noClasses()
            .that()
            .resideInAnyPackage(CORE, PROVIDER, ROUTER, STORE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.slf4j..")
            .because("libraries use System.Logger; SLF4J is the gateway's Micronaut logger");

    @ArchTest
    static final ArchRule hikariAndPostgresStayInStore = noClasses()
            .that()
            .resideInAnyPackage(CORE, PROVIDER, ROUTER, GATEWAY, GATEWAY_ROOT + ".cli..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.zaxxer.hikari..", "org.postgresql..")
            .because("JDBC pool and driver live in janus-store");

    @ArchTest
    static final ArchRule controllersLiveInGateway = classes()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .resideInAPackage(GATEWAY)
            .because("HTTP faces are gateway-owned");

    @ArchTest
    static final ArchRule providerAdaptersLiveInProvider = classes()
            .that()
            .implement(io.amscotti.janus.provider.ProviderAdapter.class)
            .should()
            .resideInAPackage(PROVIDER)
            .because("ProviderAdapter implementations are the provider SPI");

    @ArchTest
    static final ArchRule loadBalancersLiveInRouter = classes()
            .that()
            .implement(io.amscotti.janus.router.LoadBalancer.class)
            .should()
            .resideInAPackage(ROUTER)
            .because("selection strategies live in janus-router");

    @ArchTest
    static final ArchRule chatBackendsAreRouterOrGateway = classes()
            .that()
            .implement(io.amscotti.janus.router.ChatBackend.class)
            .should()
            .resideInAnyPackage(ROUTER, GATEWAY)
            .because("ChatBackend is the router SPI; the gateway adapts ProviderAdapter");

    @ArchTest
    static final ArchRule codecsLiveInCore = classes()
            .that()
            .haveSimpleNameEndingWith("Codec")
            .should()
            .resideInAPackage("io.amscotti.janus.core.codec..")
            .because("wire codecs belong to janus-core");

    @ArchTest
    static final ArchRule canonicalModelIsRecordsOrSealed = classes()
            .that()
            .resideInAPackage("io.amscotti.janus.core.model")
            .and()
            .arePublic()
            .and()
            .areTopLevelClasses()
            .and()
            .doNotHaveSimpleName("ContentLogging")
            .should()
            .beRecords()
            .orShould()
            .beInterfaces()
            .orShould()
            .beEnums()
            .because("canonical model types are records, sealed interfaces, or enums "
                    + "(ContentLogging is the boot flag holder)");

    @ArchTest
    static final ArchRule gatewayRootHoldsOnlyBootAndConfig = classes()
            .that()
            .resideInAPackage(GATEWAY_ROOT)
            .and()
            .areTopLevelClasses()
            .and()
            .haveNameNotMatching(".*\\$.*")
            .should()
            .haveSimpleName("JanusApplication")
            .orShould()
            .haveSimpleName("JanusConfig")
            .because("the gateway root package is the boot + config records only "
                    + "(Micronaut $Definition beans are generated next to JanusConfig)");

    @ArchTest
    static final ArchRule noJodaTime = NO_CLASSES_SHOULD_USE_JODATIME;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule injectStaysInGateway = noClasses()
            .that()
            .resideInAnyPackage(CORE, PROVIDER, ROUTER, STORE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta.inject..", "javax.inject..")
            .because("DI annotations are the gateway composition root only");

    @ArchTest
    static final ArchRule jdbcStaysInStore = noClasses()
            .that()
            .resideInAnyPackage(CORE, PROVIDER, ROUTER, GATEWAY)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.sql..", "javax.sql..", "jakarta.sql..")
            .because("JDBC types live in janus-store");

    @ArchTest
    static final ArchRule jdkHttpClientStaysInProviderAndGateway = noClasses()
            .that()
            .resideInAnyPackage(CORE, ROUTER, STORE)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.net.http..")
            .because("JDK HttpClient is for upstream adapters (provider) " + "and the budget webhook (gateway)");

    @ArchTest
    static final ArchRule factoriesLiveInGateway = classes()
            .that()
            .haveSimpleNameEndingWith("Factory")
            .and()
            .areTopLevelClasses()
            .should()
            .resideInAPackage(GATEWAY)
            .because("TOML → object construction is the gateway composition root");

    @ArchTest
    static final ArchRule filtersLiveInGateway = classes()
            .that()
            .haveSimpleNameEndingWith("Filter")
            .and()
            .areTopLevelClasses()
            .should()
            .resideInAPackage(GATEWAY)
            .because("HTTP filters are gateway-owned");

    @ArchTest
    static final ArchRule notifiersLiveInGateway = classes()
            .that()
            .haveSimpleNameEndingWith("Notifier")
            .and()
            .areTopLevelClasses()
            .should()
            .resideInAPackage(GATEWAY)
            .because("budget notifiers are gateway-owned");

    @ArchTest
    static final ArchRule publishersLiveInGateway = classes()
            .that()
            .haveSimpleNameEndingWith("Publisher")
            .and()
            .areTopLevelClasses()
            .should()
            .resideInAPackage(GATEWAY)
            .because("SSE publishers are gateway-owned");

    @ArchTest
    static final ArchRule errorMappersLiveInGateway = classes()
            .that()
            .haveSimpleNameEndingWith("ErrorMapper")
            .and()
            .areTopLevelClasses()
            .should()
            .resideInAPackage(GATEWAY)
            .because("face error envelopes are gateway-owned");

    @ArchTest
    static final ArchRule adaptersAreNamedAndLiveInProvider = classes()
            .that()
            .haveSimpleNameEndingWith("Adapter")
            .and()
            .areTopLevelClasses()
            .should()
            .resideInAPackage(PROVIDER)
            .because("upstream adapters (and ProviderAdapter) live in janus-provider");

    @ArchTest
    static final ArchRule callStoresLiveInStore = classes()
            .that()
            .implement(io.amscotti.janus.store.CallStore.class)
            .should()
            .resideInAPackage(STORE)
            .because("CallStore implementations are janus-store");

    @ArchTest
    static final ArchRule keyStoresLiveInStore = classes()
            .that()
            .implement(io.amscotti.janus.store.KeyStore.class)
            .should()
            .resideInAPackage(STORE)
            .because("KeyStore implementations are janus-store");

    @ArchTest
    static final ArchRule spendLedgersLiveInStore = classes()
            .that()
            .implement(io.amscotti.janus.store.SpendLedger.class)
            .should()
            .resideInAPackage(STORE)
            .because("SpendLedger implementations are janus-store");

    @ArchTest
    static final ArchRule rateLimitersLiveInStore = classes()
            .that()
            .implement(io.amscotti.janus.store.RateLimiter.class)
            .should()
            .resideInAPackage(STORE)
            .because("RateLimiter implementations are janus-store");

    @ArchTest
    static final ArchRule retryClassifiersAreRouterOrGateway = classes()
            .that()
            .implement(io.amscotti.janus.router.RetryClassifier.class)
            .should()
            .resideInAnyPackage(ROUTER, GATEWAY)
            .because("RetryClassifier is the router SPI; the gateway maps ProviderException");

    @ArchTest
    static final ArchRule upstreamHealthLivesInRouter = classes()
            .that()
            .implement(io.amscotti.janus.router.UpstreamHealth.class)
            .should()
            .resideInAPackage(ROUTER)
            .because("health tracking is janus-router");

    @ArchTest
    static final ArchRule metricsRecordersLiveInGateway = classes()
            .that()
            .haveSimpleNameEndingWith("MetricsRecorder")
            .and()
            .areTopLevelClasses()
            .should()
            .resideInAPackage(GATEWAY)
            .because("metrics recording is gateway-owned");

    @ArchTest
    static final ArchRule gatewayDtosAreRecords = classes()
            .that()
            .resideInAPackage("io.amscotti.janus.gateway.dto")
            .and()
            .areTopLevelClasses()
            .should()
            .beRecords()
            .because("HTTP DTOs are Jackson-mapped records");
}
