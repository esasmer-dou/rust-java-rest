package com.reactor.rust.example.service;

import com.reactor.rust.di.annotation.Autowired;
import com.reactor.rust.di.annotation.PostConstruct;
import com.reactor.rust.di.annotation.Service;
import com.reactor.rust.example.dto.*;
import com.reactor.rust.logging.FrameworkLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Order Service - Business logic for order operations.
 *
 * <p>Uses @Service annotation for automatic component scanning.</p>
 */
@Service
public class OrderService {

    // In-memory order storage (demo purposes)
    private final Map<String, OrderRequest> orders = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private NotificationService notificationService;

    @PostConstruct
    public void init() {
        FrameworkLogger.debug("[OrderService] Initialized");
    }

    /**
     * Create a new order.
     */
    public OrderCreateResponse createOrder(OrderCreateRequest request) {
        String orderId = "ORD-" + System.currentTimeMillis() + "-" + request.orderId();

        // Store order (using existing DTO structure)
        OrderRequest order = new OrderRequest(
            orderId,
            request.amount(),
            true,
            new Address("Istanbul", "Main Street"),
            new Customer("Customer-" + request.orderId(), "customer@example.com"),
            new ArrayList<>()
        );
        orders.put(orderId, order);

        // Send notification (if available)
        if (notificationService != null) {
            notificationService.notify("Order created: " + orderId);
        }

        return new OrderCreateResponse(1, "OK", 15);
    }

    /**
     * Cancel an order.
     */
    public OrderCreateResponse cancelOrder(String orderId) {
        OrderRequest order = orders.remove(orderId);
        if (order == null) {
            return new OrderCreateResponse(0, "Order not found", 0);
        }

        if (notificationService != null) {
            notificationService.notify("Order cancelled: " + orderId);
        }

        return new OrderCreateResponse(2, "CANCELLED", 0);
    }

    /**
     * Get order by ID.
     */
    public OrderRequest getOrder(String orderId) {
        return orders.get(orderId);
    }

    /**
     * Search orders.
     */
    public List<OrderRequest> searchOrders(String status, int page) {
        return new ArrayList<>(orders.values());
    }
}
