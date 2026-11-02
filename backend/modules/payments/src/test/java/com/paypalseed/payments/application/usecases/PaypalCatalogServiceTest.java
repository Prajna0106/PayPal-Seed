package com.paypalseed.payments.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.usecases.PaypalCatalogService.Ensured;
import com.paypalseed.payments.infrastructure.persistence.CatalogRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaypalCatalogServiceTest {

    private final PayPalGateway paypal = mock(PayPalGateway.class);
    private final PayPalCommandExecutor executor = mock(PayPalCommandExecutor.class);
    private final CatalogRepository recorded = mock(CatalogRepository.class);

    @Test
    void idsFromConfigurationWinAndNothingIsLookedUpOrCreated() {
        PaypalCatalogService service = new PaypalCatalogService(paypal, executor, recorded, "PayPal Seed", "PROD-CFG", "P-SAVE-CFG",
                "P-LOAN-CFG");

        Ensured ensured = service.ensure();

        assertThat(ensured.catalog().productId()).isEqualTo("PROD-CFG");
        assertThat(ensured.catalog().savingsPlanId()).isEqualTo("P-SAVE-CFG");
        assertThat(ensured.catalog().loanPlanId()).isEqualTo("P-LOAN-CFG");
        assertThat(ensured.createdProduct() || ensured.createdSavingsPlan() || ensured.createdLoanPlan()).isFalse();
        assertThat(service.savingsPlanId()).isEqualTo("P-SAVE-CFG");
        verifyNoInteractions(paypal, executor, recorded);
    }

    @Test
    void recordedIdsAreUsedBeforeAskingPayPal() {
        when(recorded.find(CatalogRepository.PRODUCT, "PayPal Seed")).thenReturn(Optional.of("PROD-1"));
        when(recorded.find(CatalogRepository.PLAN, "PROD-1/seed-savings-autodeposit-usd-1")).thenReturn(Optional.of("P-S"));
        when(recorded.find(CatalogRepository.PLAN, "PROD-1/seed-loan-autopay-usd-1")).thenReturn(Optional.of("P-L"));
        PaypalCatalogService service = new PaypalCatalogService(paypal, executor, recorded, "PayPal Seed", "", "", "");

        Ensured ensured = service.ensure();

        assertThat(ensured.catalog().productId()).isEqualTo("PROD-1");
        assertThat(ensured.catalog().savingsPlanId()).isEqualTo("P-S");
        assertThat(ensured.catalog().loanPlanId()).isEqualTo("P-L");
        verifyNoInteractions(paypal, executor);
    }

    @Test
    void anExistingProductAndPlanAreFoundByNameAndRememberedWithoutCreating() {
        when(recorded.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(paypal.listProducts()).thenReturn(java.util.List.of(new PayPalGateway.Product("PROD-X", "Other"),
                new PayPalGateway.Product("PROD-7", "PayPal Seed")));
        when(paypal.listPlans("PROD-7")).thenReturn(java.util.List.of(
                new PayPalGateway.Plan("P-OLD", "PROD-7", "seed-savings-autodeposit-usd-1", "INACTIVE"),
                new PayPalGateway.Plan("P-9", "PROD-7", "seed-savings-autodeposit-usd-1", "ACTIVE"),
                new PayPalGateway.Plan("P-8", "PROD-7", "seed-loan-autopay-usd-1", "ACTIVE")));
        PaypalCatalogService service = new PaypalCatalogService(paypal, executor, recorded, "PayPal Seed", "", "", "");

        Ensured ensured = service.ensure();

        assertThat(ensured.catalog().productId()).isEqualTo("PROD-7");
        assertThat(ensured.catalog().savingsPlanId()).isEqualTo("P-9");
        assertThat(ensured.catalog().loanPlanId()).isEqualTo("P-8");
        assertThat(ensured.createdProduct() || ensured.createdSavingsPlan() || ensured.createdLoanPlan()).isFalse();
        verify(recorded).remember(CatalogRepository.PRODUCT, "PayPal Seed", "PROD-7");
        verify(recorded).remember(CatalogRepository.PLAN, "PROD-7/seed-savings-autodeposit-usd-1", "P-9");
        verify(executor, never()).run(anyString(), any(), anyString(), any(), any(), any());
    }
}
