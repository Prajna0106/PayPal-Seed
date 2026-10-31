package com.paypalseed.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.paypalseed.SeedApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
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
}
