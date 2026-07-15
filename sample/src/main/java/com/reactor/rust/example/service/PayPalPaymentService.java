package com.reactor.rust.example.service;

import com.reactor.rust.di.annotation.Service;
import com.reactor.rust.logging.FrameworkLogger;

/**
 * PayPal Payment Service - Alternative implementation (NOT primary).
 *
 * <h2>Without @Primary:</h2>
 * <p>This service is NOT marked as @Primary, so it won't be selected
 * by default. Use @Qualifier to explicitly inject this implementation.</p>
 *
 * <pre>{@code
 * // Use @Qualifier to get PayPalPaymentService specifically
 * @Autowired
 * @Qualifier("payPalPaymentService")
 * private PaymentService payPalService;
 * }</pre>
 */
@Service  // Bean name defaults to "payPalPaymentService" (camelCase class name)
public class PayPalPaymentService implements PaymentService {

    @Override
    public String processPayment(String orderId, double amount) {
        String transactionId = "PP-" + System.currentTimeMillis();
        FrameworkLogger.debug("[PayPalPaymentService] Processing PayPal payment for order: " + orderId);
        FrameworkLogger.debug("[PayPalPaymentService] Amount: $" + amount);
        FrameworkLogger.debug("[PayPalPaymentService] Transaction ID: " + transactionId);
        return transactionId;
    }

    @Override
    public String getPaymentMethod() {
        return "PAYPAL";
    }

    @Override
    public boolean refund(String transactionId) {
        FrameworkLogger.debug("[PayPalPaymentService] Refunding PayPal transaction: " + transactionId);
        return true;
    }
}
