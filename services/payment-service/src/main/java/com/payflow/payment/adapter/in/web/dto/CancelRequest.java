package com.payflow.payment.adapter.in.web.dto;

import com.payflow.payment.domain.model.Payment;
import jakarta.validation.constraints.Size;

public record CancelRequest(@Size(max = Payment.MAX_REASON_LENGTH) String reason) {
}
