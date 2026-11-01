package com.paypalseed.payments.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.api.dto.PaymentsDtos.OrderPurpose;
import com.paypalseed.payments.infrastructure.persistence.CommandRepository;
import com.paypalseed.payments.infrastructure.persistence.CommandRepository.Command;
import com.paypalseed.platform.kernel.DomainException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Regression: a request fingerprint built from {@code Map.of(a, b, c, d)} depended on the map's iteration
 * order, which changes between JVM runs, so a replay after a restart could be refused with {@code 409}.
 */
class FingerprintsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void recordsSerialiseInDeclarationOrderSoTheFingerprintIsTheSameInEveryRun() throws Exception {
        assertThat(json.writeValueAsString(new Fingerprints.Order(500, OrderPurpose.CATCH_UP)))
                .isEqualTo("{\"amountCents\":500,\"purpose\":\"CATCH_UP\"}");
        assertThat(json.writeValueAsString(new Fingerprints.Refund("CAP-1", 250)))
                .isEqualTo("{\"parent\":\"CAP-1\",\"amountCents\":250}");
    }

    @Test
    void theExecutorSortsMapKeysSoInsertionOrderNeverChangesTheFingerprint() {
        PayPalCommandExecutor executor = new PayPalCommandExecutor(mock(CommandRepository.class), json);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("purpose", "CATCH_UP");
        one.put("amountCents", 500L);
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("amountCents", 500L);
        other.put("purpose", "CATCH_UP");

        assertThat(executor.fingerprint(one)).isEqualTo(executor.fingerprint(other))
                .isEqualTo("{\"amountCents\":500,\"purpose\":\"CATCH_UP\"}");
    }

    @Test
    void aReplayWithTheSameBodyInAnotherMapOrderIsNotMistakenForAnotherRequest() {
        CommandRepository commands = mock(CommandRepository.class);
        AtomicReference<String> storedHash = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(call -> {
            storedHash.set(call.getArgument(4));
            return null;
        }).when(commands).insertPending(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        PayPalCommandExecutor executor = new PayPalCommandExecutor(commands, json);
        UUID aggregate = UUID.randomUUID();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", 2);
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("b", 2);
        replay.put("a", 1);

        executor.run("REFUND", aggregate, "key-1", first, Boolean.class, rid -> true);
        when(commands.byKey("key-1")).thenAnswer(call -> Optional.of(
                new Command(UUID.randomUUID(), "REFUND", aggregate, "key-1", storedHash.get(), "SUCCEEDED", 1, "true")));

        assertThatCode(() -> executor.run("REFUND", aggregate, "key-1", replay, Boolean.class, rid -> false))
                .doesNotThrowAnyException();
        assertThat(executor.run("REFUND", aggregate, "key-1", replay, Boolean.class, rid -> false)).isTrue();
        assertThatThrownBy(() -> executor.run("REFUND", aggregate, "key-1", Map.of("a", 1, "b", 3), Boolean.class, rid -> true))
                .isInstanceOf(DomainException.class);
    }
}
