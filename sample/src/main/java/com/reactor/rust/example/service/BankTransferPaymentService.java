package com.reactor.rust.example.service;

import com.reactor.rust.di.annotation.Service;
import com.reactor.rust.logging.FrameworkLogger;

/**
 * Bank Transfer Payment Service - Another alternative implementation.
 *
 * <h2>Multiple Implementations Example:</h2>
 * <p>When you have multiple implementations of an interface, you can
 * inject specific ones using @Qualifier with the bean name.</p>
 *
 * <pre>{@code
 * @Autowired
 * @Qualifier("bankTransferPaymentService")
 * private PaymentService bankService;
 * }</pre>
 */
@Service
public class BankTransferPaymentService implements PaymentService {

    @Override
    public String processPayment(String orderId, double amount) {
        String transactionId = "BT-" + System.currentTimeMillis();
        FrameworkLogger.debug("[BankTransferPaymentService] Processing bank transfer for order: " + orderId);
        FrameworkLogger.debug("[BankTransferPaymentService] Amount: $" + amount);
        FrameworkLogger.debug("[BankTransferPaymentService] Transaction ID: " + transactionId);
        return transactionId;
    }

    @Override
    public String getPaymentMethod() {
        return "BANK_TRANSFER";
    }

    @Override
    public boolean refund(String transactionId) {
        FrameworkLogger.debug("[BankTransferPaymentService] Refunding bank transfer: " + transactionId);
        return true;
    }
}
