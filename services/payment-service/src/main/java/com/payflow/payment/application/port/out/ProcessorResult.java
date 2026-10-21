package com.payflow.payment.application.port.out;

/**
 * PayFlow-owned processor outcome. Adapters translate PSP JSON into one of these
 * variants so the application layer never branches on external status strings.
 */
public sealed interface ProcessorResult permits ProcessorResult.Approved, ProcessorResult.Declined,
        ProcessorResult.Unavailable {

    record Approved(String processorReference) implements ProcessorResult {
    }

    record Declined(String code, String message) implements ProcessorResult {
    }

    record Unavailable(String reason) implements ProcessorResult {
    }
}
