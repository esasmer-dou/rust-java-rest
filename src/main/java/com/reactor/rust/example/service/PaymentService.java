package com.reactor.rust.example.service;

/**
 * Payment Service Interface - Demonstrates multiple bean implementations.
 *
 * <p>This interface has multiple implementations:
 * <ul>
 *   <li>{@link CreditCardPaymentService} - Marked with @Primary (default)</li>
 *   <li>{@link PayPalPaymentService} - Alternative implementation</li>
 *   <li>{@link BankTransferPaymentService} - Another alternative</li>
 * </ul>
 *
 * <p>Use @Qualifier to select specific implementation when injecting.</p>
 */
public interface PaymentService {

    /**
     * Process a payment.
     *
     * @param orderId Order ID
     * @param amount Payment amount
     * @return Transaction ID
     */
    String processPayment(String orderId, double amount);

    /**
     * Get the payment method name.
     */
    String getPaymentMethod();

    /**
     * Refund a payment.
     */
    boolean refund(String transactionId);
}
