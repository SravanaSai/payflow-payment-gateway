package com.payflow.payment.adapter.in.web;

import com.payflow.common.core.domain.Money;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.adapter.in.web.dto.CancelRequest;
import com.payflow.payment.adapter.in.web.dto.CaptureRequest;
import com.payflow.payment.adapter.in.web.dto.CreatePaymentRequest;
import com.payflow.payment.adapter.in.web.dto.PaymentResponse;
import com.payflow.payment.application.port.in.AuthorizePaymentUseCase;
import com.payflow.payment.application.port.in.AuthorizePaymentUseCase.AuthorizePaymentCommand;
import com.payflow.payment.application.port.in.CancelPaymentUseCase;
import com.payflow.payment.application.port.in.CancelPaymentUseCase.CancelPaymentCommand;
import com.payflow.payment.application.port.in.CapturePaymentUseCase;
import com.payflow.payment.application.port.in.CapturePaymentUseCase.CapturePaymentCommand;
import com.payflow.payment.application.port.in.CreatePaymentUseCase;
import com.payflow.payment.application.port.in.CreatePaymentUseCase.CreatePaymentCommand;
import com.payflow.payment.application.port.in.GetPaymentUseCase;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
@Tag(name = "Payments", description = "Payment lifecycle")
public class PaymentController {

    private final CreatePaymentUseCase createPaymentUseCase;
    private final GetPaymentUseCase getPaymentUseCase;
    private final AuthorizePaymentUseCase authorizePaymentUseCase;
    private final CapturePaymentUseCase capturePaymentUseCase;
    private final CancelPaymentUseCase cancelPaymentUseCase;

    public PaymentController(
            CreatePaymentUseCase createPaymentUseCase,
            GetPaymentUseCase getPaymentUseCase,
            AuthorizePaymentUseCase authorizePaymentUseCase,
            CapturePaymentUseCase capturePaymentUseCase,
            CancelPaymentUseCase cancelPaymentUseCase) {
        this.createPaymentUseCase = createPaymentUseCase;
        this.getPaymentUseCase = getPaymentUseCase;
        this.authorizePaymentUseCase = authorizePaymentUseCase;
        this.capturePaymentUseCase = capturePaymentUseCase;
        this.cancelPaymentUseCase = cancelPaymentUseCase;
    }

    @PostMapping
    @Operation(summary = "Create a payment", description = "Creates a PENDING payment. Amounts are in minor units.")
    public ResponseEntity<PaymentResponse> create(
            PaymentOwner owner,
            @Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = createPaymentUseCase.create(new CreatePaymentCommand(
                owner,
                Money.ofMinorUnits(request.amountMinor(), request.currency()),
                request.description(),
                request.reference()));
        return ResponseEntity.created(URI.create("/v1/payments/" + payment.getId()))
                .body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a payment")
    public PaymentResponse get(PaymentOwner owner, @PathVariable("id") UUID id) {
        return PaymentResponse.from(getPaymentUseCase.get(new PaymentId(id), owner));
    }

    @PostMapping("/{id}/authorize")
    @Operation(summary = "Authorize a pending payment")
    public PaymentResponse authorize(PaymentOwner owner, @PathVariable("id") UUID id) {
        return PaymentResponse.from(authorizePaymentUseCase.authorize(
                new AuthorizePaymentCommand(new PaymentId(id), owner)));
    }

    @PostMapping("/{id}/capture")
    @Operation(summary = "Capture an authorized payment",
            description = "Omit the body or amountMinor to capture the full authorized amount.")
    public PaymentResponse capture(
            PaymentOwner owner,
            @PathVariable("id") UUID id,
            @Valid @RequestBody(required = false) CaptureRequest request) {
        Long amountMinor = request == null ? null : request.amountMinor();
        return PaymentResponse.from(capturePaymentUseCase.capture(
                new CapturePaymentCommand(new PaymentId(id), owner, amountMinor)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending payment or void an authorization")
    public PaymentResponse cancel(
            PaymentOwner owner,
            @PathVariable("id") UUID id,
            @Valid @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.reason();
        return PaymentResponse.from(cancelPaymentUseCase.cancel(
                new CancelPaymentCommand(new PaymentId(id), owner, reason)));
    }
}
