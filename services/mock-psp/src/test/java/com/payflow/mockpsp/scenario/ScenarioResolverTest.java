package com.payflow.mockpsp.scenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioResolverTest {

    @ParameterizedTest
    @CsvSource({
            "150051, DECLINE_INSUFFICIENT_FUNDS",
            "150052, DECLINE_DO_NOT_HONOR",
            "150091, SLOW",
            "150092, DOWN",
            "150093, FLAKY"
    })
    void theLastFourDigitsChooseTheScenario(long amountMinor, Scenario expected) {
        assertThat(ScenarioResolver.resolve(amountMinor)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 100, 150_000, 150_050, 150_053, 150_090, 150_094, 9_999})
    void everyOtherAmountApproves(long amountMinor) {
        assertThat(ScenarioResolver.resolve(amountMinor)).isEqualTo(Scenario.APPROVE);
    }

    @Test
    void onlyTheLastFourDigitsMatter() {
        assertThat(ScenarioResolver.resolve(51)).isEqualTo(Scenario.DECLINE_INSUFFICIENT_FUNDS);
        assertThat(ScenarioResolver.resolve(10_051)).isEqualTo(Scenario.DECLINE_INSUFFICIENT_FUNDS);
        assertThat(ScenarioResolver.resolve(9_876_500_051L)).isEqualTo(Scenario.DECLINE_INSUFFICIENT_FUNDS);
    }

    @Test
    void onlyDeclinesCarryDeclineDetails() {
        assertThat(Scenario.DECLINE_INSUFFICIENT_FUNDS.isDecline()).isTrue();
        assertThat(Scenario.DECLINE_INSUFFICIENT_FUNDS.declineCode()).isEqualTo("insufficient_funds");
        assertThat(Scenario.DECLINE_DO_NOT_HONOR.declineCode()).isEqualTo("do_not_honor");
        assertThat(Scenario.APPROVE.isDecline()).isFalse();
        assertThat(Scenario.SLOW.isDecline()).isFalse();
        assertThat(Scenario.DOWN.isDecline()).isFalse();
        assertThat(Scenario.FLAKY.isDecline()).isFalse();
    }
}
