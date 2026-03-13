package com.reactor.rust.example.handler;

import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.di.annotation.Autowired;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.di.annotation.Qualifier;
import com.reactor.rust.example.dto.PaymentMethodsResponse.PaymentMethodInfo;
import com.reactor.rust.example.dto.*;
import com.reactor.rust.example.service.*;
import com.reactor.rust.json.DslJsonService;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Payment Handler - Demonstrates @Primary and @Qualifier usage.
 *
 * <h2>DI Injection Examples:</h2>
 *
 * <h3>1. @Primary Injection (Default):</h3>
 * <p>When multiple beans implement PaymentService, the @Primary one
 * (CreditCardPaymentService) is injected by default.</p>
 * <pre>{@code
 * @Autowired
 * private PaymentService paymentService; // Gets CreditCardPaymentService (@Primary)
 * }</pre>
 *
 * <h3>2. @Qualifier Injection (Specific):</h3>
 * <p>Use @Qualifier to inject a specific implementation.</p>
 * <pre>{@code
 * @Autowired
 * @Qualifier("payPalPaymentService")
 * private PaymentService payPalService; // Gets PayPalPaymentService specifically
 *
 * @Autowired
 * @Qualifier("bankTransferPaymentService")
 * private PaymentService bankService; // Gets BankTransferPaymentService specifically
 * }</pre>
 *
 * <h3>3. All Implementations at Once:</h3>
 * <p>You can inject all implementations and choose at runtime.</p>
 */
@Component
public class PaymentHandler {

    // ============ @Primary Example ============
    // Since CreditCardPaymentService is marked @Primary, it's injected by default
    // when multiple PaymentService implementations exist
    @Autowired
    private PaymentService paymentService;  // Gets CreditCardPaymentService

    // ============ @Qualifier Examples ============
    // Use @Qualifier to specify which implementation to inject

    @Autowired
    @Qualifier("payPalPaymentService")
    private PaymentService payPalService;  // Explicitly gets PayPalPaymentService

    @Autowired
    @Qualifier("bankTransferPaymentService")
    private PaymentService bankService;  // Explicitly gets BankTransferPaymentService

    /**
     * Process payment using default (@Primary) payment service.
     *
     * <p>Demonstrates @Primary in action - paymentService is CreditCardPaymentService.</p>
     */
    @RustRoute(
            method = "POST",
            path = "/payment/process",
            requestType = PaymentRequest.class,
            responseType = PaymentResponse.class
    )
    public int processPayment(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        PaymentRequest request = DslJsonService.parse(body, PaymentRequest.class);

        // Uses @Primary payment service (CreditCardPaymentService)
        String transactionId = paymentService.processPayment(request.orderId(), request.amount());

        PaymentResponse response = new PaymentResponse(
            transactionId,
            paymentService.getPaymentMethod(),
            "SUCCESS"
        );

        return DslJsonService.writeToBuffer(response, out, offset);
    }

    /**
     * Process payment using PayPal (via @Qualifier).
     *
     * <p>Demonstrates @Qualifier in action - payPalService is PayPalPaymentService.</p>
     */
    @RustRoute(
            method = "POST",
            path = "/payment/paypal",
            requestType = PaymentRequest.class,
            responseType = PaymentResponse.class
    )
    public int processPayPal(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        PaymentRequest request = DslJsonService.parse(body, PaymentRequest.class);

        // Uses @Qualifier("payPalPaymentService")
        String transactionId = payPalService.processPayment(request.orderId(), request.amount());

        PaymentResponse response = new PaymentResponse(
            transactionId,
            payPalService.getPaymentMethod(),
            "SUCCESS"
        );

        return DslJsonService.writeToBuffer(response, out, offset);
    }

    /**
     * Process payment using Bank Transfer (via @Qualifier).
     *
     * <p>Demonstrates @Qualifier in action - bankService is BankTransferPaymentService.</p>
     */
    @RustRoute(
            method = "POST",
            path = "/payment/bank",
            requestType = PaymentRequest.class,
            responseType = PaymentResponse.class
    )
    public int processBankTransfer(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        PaymentRequest request = DslJsonService.parse(body, PaymentRequest.class);

        // Uses @Qualifier("bankTransferPaymentService")
        String transactionId = bankService.processPayment(request.orderId(), request.amount());

        PaymentResponse response = new PaymentResponse(
            transactionId,
            bankService.getPaymentMethod(),
            "SUCCESS"
        );

        return DslJsonService.writeToBuffer(response, out, offset);
    }

    /**
     * Get available payment methods.
     *
     * <p>Demonstrates having access to all implementations.</p>
     */
    @RustRoute(
            method = "GET",
            path = "/payment/methods",
            requestType = Void.class,
            responseType = PaymentMethodsResponse.class
    )
    public int getPaymentMethods(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        // Show all available payment methods
        PaymentMethodsResponse response = new PaymentMethodsResponse(
            List.of(
                new PaymentMethodInfo("credit-card", paymentService.getPaymentMethod(), true),
                new PaymentMethodInfo("paypal", payPalService.getPaymentMethod(), false),
                new PaymentMethodInfo("bank-transfer", bankService.getPaymentMethod(), false)
            )
        );

        return DslJsonService.writeToBuffer(response, out, offset);
    }
}
