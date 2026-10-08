package com.payflow.payment.idempotency;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private static final byte[] BODY = "{\"amountMinor\": 100}".getBytes(UTF_8);

    @Test
    void theSameRequestGivesTheSameFingerprint() {
        assertThat(RequestFingerprint.of("POST", "/v1/payments", BODY))
                .isEqualTo(RequestFingerprint.of("POST", "/v1/payments", BODY.clone()));
    }

    @Test
    void isASixtyFourCharacterHexDigest() {
        assertThat(RequestFingerprint.of("POST", "/v1/payments", BODY)).matches("[0-9a-f]{64}");
    }

    @Test
    void anyDifferenceInMethodPathOrBodyChangesIt() {
        String original = RequestFingerprint.of("POST", "/v1/payments", BODY);

        assertThat(RequestFingerprint.of("PUT", "/v1/payments", BODY)).isNotEqualTo(original);
        assertThat(RequestFingerprint.of("POST", "/v1/payments/1/capture", BODY)).isNotEqualTo(original);
        assertThat(RequestFingerprint.of("POST", "/v1/payments", "{\"amountMinor\":100}".getBytes(UTF_8)))
                .as("exact bytes: even whitespace counts")
                .isNotEqualTo(original);
    }

    @Test
    void partsCannotBleedIntoEachOther() {
        assertThat(RequestFingerprint.of("POST", "/v1/ab", new byte[0]))
                .isNotEqualTo(RequestFingerprint.of("POST", "/v1/a", "b".getBytes(UTF_8)));
    }
}
