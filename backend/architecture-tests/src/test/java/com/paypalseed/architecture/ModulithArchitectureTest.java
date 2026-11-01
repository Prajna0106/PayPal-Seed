package com.paypalseed.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.paypalseed.SeedApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** TC-ARC-*: module boundaries and engine purity, enforced by the build. */
class ModulithArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.paypalseed");

    /** TC-ARC-03: modules talk only through each other's api packages; no cycles. */
    @Test
    void modulesOnlyUseEachOthersPublicApi() {
        ApplicationModules.of(SeedApplication.class).verify();
    }

    /** TC-ARC-02: the schedule engine is pure: no Spring, no JDBC, no network. */
    @Test
    void engineIsPure() {
        noClasses().that().resideInAPackage("com.paypalseed.engines..").and().doNotHaveSimpleName("package-info")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..", "java.net..",
                        "javax.sql..", "com.paypalseed.modules..", "com.paypalseed.lending..", "com.paypalseed.payments..")
                .check(CLASSES);
    }

    /**
     * Every route asks for an Actor or a scoped agent token, so identity can't come from anything the caller types.
     * The only exceptions are webhooks, which prove the caller with a signature.
     */
    @Test
    void everyRouteAuthenticatesItsCaller() {
        Set<String> provenAnotherWay = Set.of("PayPalWebhookController", "SmsInboundController");
        Set<String> identities = Set.of("com.paypalseed.platform.security.Actor", "com.paypalseed.agentgateway.api.AgentPrincipal");
        Set<String> mappings = Set.of("GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping",
                "RequestMapping");
        ArchCondition<JavaMethod> takeAnIdentity = new ArchCondition<>("take an authenticated identity") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean hasIdentity = method.getRawParameterTypes().stream()
                        .anyMatch(type -> identities.contains(type.getFullName()));
                events.add(new SimpleConditionEvent(method, hasIdentity, method.getFullName() + " takes no identity"));
            }
        };
        methods().that(new DescribedPredicate<JavaMethod>("are routes") {
                    @Override
                    public boolean test(JavaMethod method) {
                        return !provenAnotherWay.contains(method.getOwner().getSimpleName())
                                && method.getAnnotations().stream()
                                        .anyMatch(a -> mappings.contains(a.getRawType().getSimpleName()));
                    }
                })
                .should(takeAnIdentity)
                .check(CLASSES);
    }

    /** TC-ARC-01, INV-10: only the payments module talks to PayPal. */
    @Test
    void onlyPaymentsTalksToPayPal() {
        noClasses().that().resideOutsideOfPackage("com.paypalseed.payments..")
                .should().dependOnClassesThat().resideInAPackage("com.paypalseed.payments.application.ports..")
                .orShould().dependOnClassesThat().resideInAPackage("com.paypalseed.payments.infrastructure.clients..")
                .check(CLASSES);
    }

    /** Only the ledger module writes journal tables: nobody else reaches its persistence code. */
    @Test
    void onlyLedgerTouchesTheJournal() {
        noClasses().that().resideOutsideOfPackage("com.paypalseed.ledger..")
                .should().dependOnClassesThat().resideInAPackage("com.paypalseed.ledger.infrastructure..")
                .check(CLASSES);
    }

    /** SQL stays behind the repositories: use cases and controllers only ever see plain records. */
    @Test
    void jooqStaysInPersistence() {
        noClasses().that().resideOutsideOfPackages("..infrastructure.persistence..", "com.paypalseed.platform.persistence..")
                .and().doNotHaveSimpleName("package-info")
                .should().dependOnClassesThat().resideInAPackage("org.jooq..")
                .check(CLASSES);
    }

    /** The Guardian sees the platform only through the agent gateway and hardship's read API: no write path exists. */
    @Test
    void guardianReachesNothingButTheGatewayAndHardshipApis() {
        noClasses().that().resideInAPackage("com.paypalseed.guardian..")
                .should().dependOnClassesThat().resideInAnyPackage("com.paypalseed.lending..", "com.paypalseed.governance..",
                        "com.paypalseed.payments..", "com.paypalseed.ledger..", "com.paypalseed.underwriting..",
                        "com.paypalseed.engagement..", "com.paypalseed.agentgateway.application..",
                        "com.paypalseed.agentgateway.infrastructure..", "com.paypalseed.hardship.application..",
                        "com.paypalseed.hardship.infrastructure..", "org.jooq..", "java.sql..")
                .check(CLASSES);
    }
}
