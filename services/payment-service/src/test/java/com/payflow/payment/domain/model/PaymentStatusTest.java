package com.payflow.payment.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.payflow.payment.domain.model.PaymentStatus.AUTHORIZED;
import static com.payflow.payment.domain.model.PaymentStatus.CANCELLED;
import static com.payflow.payment.domain.model.PaymentStatus.CAPTURED;
import static com.payflow.payment.domain.model.PaymentStatus.FAILED;
import static com.payflow.payment.domain.model.PaymentStatus.PENDING;
import static com.payflow.payment.domain.model.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FSM specification, written out independently of {@link PaymentStatus} so the test
 * cannot pass by reusing the table it is meant to check.
 */
class PaymentStatusTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> SPECIFICATION = Map.of(
            PENDING, EnumSet.of(AUTHORIZED, FAILED, CANCELLED),
            AUTHORIZED, EnumSet.of(CAPTURED, CANCELLED),
            CAPTURED, EnumSet.of(REFUNDED),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELLED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    @Test
    void specificationCoversEveryStatus() {
        assertThat(SPECIFICATION.keySet()).containsExactlyInAnyOrder(PaymentStatus.values());
    }

    @ParameterizedTest(name = "{0} -> {1} allowed: {2}")
    @MethodSource("everyPair")
    void transitionMatchesSpecification(PaymentStatus from, PaymentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void terminalStatesAreExactlyThoseWithNoWayOut() {
        assertThat(Arrays.stream(PaymentStatus.values()).filter(PaymentStatus::isTerminal))
                .containsExactlyInAnyOrder(FAILED, CANCELLED, REFUNDED);
    }

    static Stream<Arguments> everyPair() {
        return Arrays.stream(PaymentStatus.values()).flatMap(from -> Arrays.stream(PaymentStatus.values())
                .map(to -> Arguments.of(from, to, SPECIFICATION.get(from).contains(to))));
    }
}
