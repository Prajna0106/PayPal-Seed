package com.paypalseed.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** ADR 0014: the credit record imports no business module, so lending can read it without a cycle. */
class CreditRecordArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.paypalseed");

    /**
     * Facts reach the record through its facade, from the wiring module. It reads no savings, lending or hardship
     * class, so it cannot see a hardship signal either (ADR 0011: asking for help never hurts standing).
     */
    @Test
    void creditRecordImportsNoOtherBusinessModule() {
        noClasses().that().resideInAPackage("com.paypalseed.creditrecord..")
                .should().dependOnClassesThat().resideInAnyPackage("com.paypalseed.savings..", "com.paypalseed.lending..",
                        "com.paypalseed.hardship..", "com.paypalseed.underwriting..", "com.paypalseed.payments..",
                        "com.paypalseed.ledger..", "com.paypalseed.governance..", "com.paypalseed.engagement..",
                        "com.paypalseed.guardian..", "com.paypalseed.agentgateway..")
                .check(CLASSES);
    }

    /** Only the wiring module and the record itself may reach the record's own classes beyond its api. */
    @Test
    void nobodyReachesIntoTheRecordsInternals() {
        noClasses().that().resideOutsideOfPackages("com.paypalseed.creditrecord..", "com.paypalseed.app..")
                .should().dependOnClassesThat().resideInAnyPackage("com.paypalseed.creditrecord.application..",
                        "com.paypalseed.creditrecord.domain..", "com.paypalseed.creditrecord.infrastructure..")
                .check(CLASSES);
    }
}
