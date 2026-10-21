package com.payflow.mockpsp.scenario;

/**
 * Maps an amount in minor units to a {@link Scenario} using its last four digits, the way
 * payment companies reserve special test card numbers. The same amount always gives the
 * same outcome, so a test (or a plain curl) picks the behaviour just by choosing an amount.
 *
 * <pre>
 * ...0051 decline insufficient_funds    ...0091 slow
 * ...0052 decline do_not_honor          ...0092 down
 *                                       ...0093 flaky (fails twice, then approves)
 * anything else approves
 * </pre>
 */
public final class ScenarioResolver {

    private ScenarioResolver() {
    }

    public static Scenario resolve(long amountMinor) {
        return switch ((int) Math.floorMod(amountMinor, 10_000L)) {
            case 51 -> Scenario.DECLINE_INSUFFICIENT_FUNDS;
            case 52 -> Scenario.DECLINE_DO_NOT_HONOR;
            case 91 -> Scenario.SLOW;
            case 92 -> Scenario.DOWN;
            case 93 -> Scenario.FLAKY;
            default -> Scenario.APPROVE;
        };
    }
}
