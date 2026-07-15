package com.reactor.rust.example.service;

import com.reactor.rust.di.annotation.Primary;
import com.reactor.rust.di.annotation.Service;
import com.reactor.rust.logging.FrameworkLogger;

/**
 * Credit Card Payment Service - PRIMARY implementation.
 *
 * <h2>@Primary Example:</h2>
 * <p>When multiple beans implement the same interface, @Primary marks this
 * as the default choice when no @Qualifier is specified.</p>
 *
 * <pre>{@code
 * // Without @Qualifier, CreditCardPaymentService is injected (it's @Primary)
 * @Autowired
 * private PaymentService paymentService;
 *
 * // This is equivalent to:
 * @Autowired
 * @Qualifier("creditCardPaymentService")
 * private PaymentService paymentService;
 * }</pre>
 */
@Service
@Primary  // <-- This makes it the DEFAULT when multiple candidates exist
public class CreditCardPaymentService implements PaymentService {

    @Override
    public String processPayment(String orderId, double amount) {
        String transactionId = "CC-" + System.currentTimeMillis();
        FrameworkLogger.debug("[CreditCardPaymentService] Processing payment for order: " + orderId);
        FrameworkLogger.debug("[CreditCardPaymentService] Amount: $" + amount);
        FrameworkLogger.debug("[CreditCardPaymentService] Transaction ID: " + transactionId);
        return transactionId;
    }

    @Override
    public String getPaymentMethod() {
        return "CREDIT_CARD";
    }

    @Override
    public boolean refund(String transactionId) {
        FrameworkLogger.debug("[CreditCardPaymentService] Refunding transaction: " + transactionId);
        return true;
    }
}
