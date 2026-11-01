package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.dto.PaymentsDtos.PaypalCatalog;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.CreatePlan;
import com.paypalseed.payments.application.ports.PayPalGateway.Plan;
import com.paypalseed.payments.application.ports.PayPalGateway.Product;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.payments.infrastructure.persistence.CatalogRepository;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes sure the PayPal product and the two {@code $1 x quantity} monthly plans (savings deposits and loan
 * autopay) exist, and creates only what is missing. Order of preference for each object: the id set in
 * configuration, the id Seed recorded earlier, a lookup by name at PayPal, and last a create that carries a
 * stable {@code PayPal-Request-Id}, so even two racing bootstraps end with one product and one plan each.
 */
@Service
public class PaypalCatalogService {

    public static final String SAVINGS_PLAN_NAME = "seed-savings-autodeposit-usd-1";
    public static final String LOAN_PLAN_NAME = "seed-loan-autopay-usd-1";
    private static final UUID CATALOG_AGGREGATE = UUID.nameUUIDFromBytes("paypal-catalog".getBytes(StandardCharsets.UTF_8));

    /** What {@code ensure} found or made. The {@code created} flags are true only for objects this call created. */
    public record Ensured(PaypalCatalog catalog, boolean createdProduct, boolean createdSavingsPlan, boolean createdLoanPlan) {
    }

    private record Resolved(String id, boolean created) {
    }

    private record NamedRequest(String name, String description) {
    }

    private final PayPalGateway paypal;
    private final PayPalCommandExecutor executor;
    private final CatalogRepository catalog;
    private final String productName;
    private final String configuredProductId;
    private final String configuredSavingsPlanId;
    private final String configuredLoanPlanId;

    public PaypalCatalogService(PayPalGateway paypal, PayPalCommandExecutor executor, CatalogRepository catalog,
                                @Value("${seed.paypal.product-name:PayPal Seed}") String productName,
                                @Value("${seed.paypal.product-id:}") String configuredProductId,
                                @Value("${seed.paypal.savings-plan-id:}") String configuredSavingsPlanId,
                                @Value("${seed.paypal.loan-plan-id:}") String configuredLoanPlanId) {
        this.paypal = paypal;
        this.executor = executor;
        this.catalog = catalog;
        this.productName = productName;
        this.configuredProductId = configuredProductId;
        this.configuredSavingsPlanId = configuredSavingsPlanId;
        this.configuredLoanPlanId = configuredLoanPlanId;
    }

    /** The product and both plans: what the bootstrap endpoint returns. */
    @Transactional
    public Ensured ensure() {
        Resolved product = product();
        Resolved savings = plan(product.id(), SAVINGS_PLAN_NAME, configuredSavingsPlanId,
                "Monthly deposit into a Seed savings goal. Quantity is the deposit in whole dollars.");
        Resolved loan = plan(product.id(), LOAN_PLAN_NAME, configuredLoanPlanId,
                "Monthly instalment of a Seed microloan. Quantity is the instalment in whole dollars.");
        return new Ensured(new PaypalCatalog(product.id(), savings.id(), loan.id()), product.created(), savings.created(),
                loan.created());
    }

    /** Only what a savings subscription needs; creates the product and the savings plan if missing. */
    @Transactional
    public String savingsPlanId() {
        Resolved product = product();
        return plan(product.id(), SAVINGS_PLAN_NAME, configuredSavingsPlanId,
                "Monthly deposit into a Seed savings goal. Quantity is the deposit in whole dollars.").id();
    }

    private Resolved product() {
        return resolve(CatalogRepository.PRODUCT, productName, configuredProductId,
                () -> paypal.listProducts().stream().filter(p -> productName.equals(p.name())).findFirst().map(Product::id),
                () -> executor.run("CREATE_PRODUCT", CATALOG_AGGREGATE, "catalog-product:" + productName,
                        new NamedRequest(productName, "Savings goals and fair microloans"), Product.class,
                        rid -> paypal.createProduct(productName, "Savings goals and fair microloans", rid)).id());
    }

    private Resolved plan(String productId, String planName, String configuredId, String description) {
        return resolve(CatalogRepository.PLAN, productId + "/" + planName, configuredId,
                () -> paypal.listPlans(productId).stream()
                        .filter(p -> planName.equals(p.name()) && "ACTIVE".equals(p.status())).findFirst().map(Plan::id),
                () -> {
                    CreatePlan request = new CreatePlan(productId, planName, description, 100);
                    return executor.run("CREATE_PLAN", CATALOG_AGGREGATE, "catalog-plan:" + productId + ":" + planName, request,
                            Plan.class, rid -> paypal.createPlan(request, rid)).id();
                });
    }

    private Resolved resolve(String kind, String name, String configuredId, Supplier<Optional<String>> findAtPaypal,
                             Supplier<String> create) {
        if (!configuredId.isBlank()) {
            return new Resolved(configuredId, false);
        }
        Optional<String> known = catalog.find(kind, name);
        if (known.isPresent()) {
            return new Resolved(known.get(), false);
        }
        Optional<String> found;
        try {
            found = findAtPaypal.get();
        } catch (PayPalException e) {
            if (e.isTransient()) {
                throw new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "PayPal is unavailable; try again");
            }
            throw new DomainException(PaymentsErrors.PAYPAL_REJECTED, "PayPal refused the catalog lookup",
                    e.paypalIssue() == null ? Map.of() : Map.of("paypalIssue", e.paypalIssue()));
        }
        if (found.isPresent()) {
            catalog.remember(kind, name, found.get());
            return new Resolved(found.get(), false);
        }
        String id = create.get();
        catalog.remember(kind, name, id);
        return new Resolved(id, true);
    }
}
