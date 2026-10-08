package com.payflow.payment.idempotency;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    private final PaymentOwner owner = new PaymentOwner(MerchantId.generate(), PaymentEnvironment.TEST);

    @Test
    void acceptsAUuid() {
        String uuid = UUID.randomUUID().toString();

        assertThat(new IdempotencyKey(owner, uuid).value()).isEqualTo(uuid);
    }

    @Test
    void acceptsTheMaximumLength() {
        String atLimit = "k".repeat(IdempotencyKey.MAX_LENGTH);

        assertThat(new IdempotencyKey(owner, atLimit).value()).hasSize(IdempotencyKey.MAX_LENGTH);
    }

    @Test
    void rejectsOneCharacterOverTheLimit() {
        assertRejected("k".repeat(IdempotencyKey.MAX_LENGTH + 1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "has space", "tab\there", "naïve"})
    void rejectsMissingOrNonVisibleAsciiKeys(String value) {
        assertRejected(value);
    }

    private void assertRejected(String value) {
        assertThatThrownBy(() -> new IdempotencyKey(owner, value)).isInstanceOfSatisfying(PayFlowException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
