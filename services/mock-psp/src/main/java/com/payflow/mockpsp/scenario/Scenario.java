package com.payflow.mockpsp.scenario;

/** What mock-psp does with a request. The amount's last four digits choose it (see {@link ScenarioResolver}). */
public enum Scenario {

    APPROVE,
    DECLINE_INSUFFICIENT_FUNDS("insufficient_funds", "Your card has insufficient funds."),
    DECLINE_DO_NOT_HONOR("do_not_honor", "The bank declined the transaction."),
    /** Records the approval first, then waits before replying: the reply arrives too late for the caller. */
    SLOW,
    /** Always unavailable, and nothing is recorded. */
    DOWN,
    /** Unavailable for the first few attempts with one idempotency key, then approves. */
    FLAKY;

    private final String declineCode;
    private final String declineMessage;

    Scenario() {
        this(null, null);
    }

    Scenario(String declineCode, String declineMessage) {
        this.declineCode = declineCode;
        this.declineMessage = declineMessage;
    }

    public boolean isDecline() {
        return declineCode != null;
    }

    public String declineCode() {
        return declineCode;
    }

    public String declineMessage() {
        return declineMessage;
    }
}
