package com.reactor.rust.example.handler;

import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.di.annotation.Autowired;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.example.dto.*;
import com.reactor.rust.example.service.OrderService;
import com.reactor.rust.example.service.NotificationService;
import com.reactor.rust.http.MediaType;
import com.reactor.rust.json.DslJsonService;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Order Handler - Pure Java with DI
 *
 * <p>Constraint #4: Pure Java - NO reflection libraries at runtime</p>
 * <p>Constraint #7: RECORD ZORUNLULUĞU - Only Records for DTOs</p>
 *
 * <h2>DI Example:</h2>
 * <ul>
 *   <li>@Component marks this as a bean for component scanning</li>
 *   <li>@Autowired injects OrderService and NotificationService</li>
 *   <li>Services are injected at startup - zero runtime overhead</li>
 * </ul>
 */
@Component
public class OrderHandler {

    // DI: OrderService is automatically injected
    @Autowired
    private OrderService orderService;

    // DI: Optional dependency (required = false)
    @Autowired(required = false)
    private NotificationService notificationService;

    // Thread-local HashMap pools - eliminates allocation per request
    private static final ThreadLocal<HashMap<String, String>> PARAM_CACHE =
        ThreadLocal.withInitial(() -> new HashMap<>(8));

    private static final ThreadLocal<HashMap<String, String>> HEADER_CACHE =
        ThreadLocal.withInitial(() -> new HashMap<>(16));

    @RustRoute(
            method = "POST",
            path = "/order/create",
            requestType = OrderCreateRequest.class,
            responseType = OrderCreateResponse.class
    )
    public int create(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        Map<String, String> h = parseHeaders(headers);

        String ct = h.get("content-type");
        if (ct == null || !ct.contains(MediaType.APPLICATION_JSON)) {
            return DslJsonService.writeToBuffer(
                    new ErrorResponse("Unsupported Content-Type"),
                    out,
                    offset
            );
        }

        OrderCreateRequest request = DslJsonService.parse(body, OrderCreateRequest.class);

        // DI Example: Use injected OrderService
        OrderCreateResponse response = orderService.createOrder(request);

        return DslJsonService.writeToBuffer(response, out, offset);
    }

    @RustRoute(
            method = "POST",
            path = "/order/cancel",
            requestType = OrderCreateRequest.class,
            responseType = OrderCreateResponse.class
    )
    public int cancel(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        OrderCreateRequest request = DslJsonService.parse(body, OrderCreateRequest.class);

        // DI Example: Use injected OrderService to cancel
        OrderCreateResponse response = orderService.cancelOrder(String.valueOf(request.orderId()));

        return DslJsonService.writeToBuffer(response, out, offset);
    }

    @RustRoute(
            method = "GET",
            path = "/order/order",
            requestType = Void.class,
            responseType = OrderRequest.class
    )
    public int getOrderInfo(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        // Using Record constructors (Constraint #7 - RECORD ZORUNLULUĞU)
        Address address = new Address("Ankara", "Ataturk Cd.");
        Customer customer = new Customer("mustafa customer a.ş", "mustafa@gmai.com");

        List<Item> listItems = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            listItems.add(new Item("test" + i, 12.89 + i));
        }

        OrderRequest req = new OrderRequest(
            "ORD-1001",
            350.75,
            true,
            address,
            customer,
            listItems
        );

        return DslJsonService.writeToBuffer(req, out, offset);
    }

    @RustRoute(
            method = "GET",
            path = "/order/{id}",
            requestType = Void.class,
            responseType = OrderIdResponse.class
    )
    public int getOrderById(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        Map<String, String> params = parseParams(pathParams);

        // DI Example: Get order from OrderService
        String orderId = params.get("id");
        OrderRequest order = orderService.getOrder(orderId);

        if (order != null) {
            return DslJsonService.writeToBuffer(
                    new OrderIdResponse(orderId + " (from DI service)"),
                    out,
                    offset
            );
        }

        return DslJsonService.writeToBuffer(
                new OrderIdResponse(params.get("id")),
                out,
                offset
        );
    }

    @RustRoute(
            method = "GET",
            path = "/order/search",
            requestType = Void.class,
            responseType = OrderSearchResponse.class
    )
    public int search(
            ByteBuffer out,
            int offset,
            byte[] body,
            String pathParams,
            String query,
            String headers
    ) {
        Map<String, String> q = parseParams(query);

        // DI Example: Search via OrderService
        String status = q.get("status");
        int page = q.get("page") != null ? Integer.parseInt(q.get("page")) : 1;

        List<OrderRequest> orders = orderService.searchOrders(status, page);

        return DslJsonService.writeToBuffer(
                new OrderSearchResponse(status + " (" + orders.size() + " results)", q.get("page")),
                out,
                offset
        );
    }

    /**
     * Optimized param parsing with ThreadLocal HashMap reuse.
     * ~25% faster than allocating new HashMap each call.
     */
    static Map<String, String> parseParams(String s) {
        HashMap<String, String> m = PARAM_CACHE.get();
        m.clear();  // Reuse the map

        if (s == null || s.isEmpty()) {
            return m;
        }

        // Optimized parsing without split() allocation
        int start = 0;
        int len = s.length();

        while (start < len) {
            // Find end of current param
            int amp = s.indexOf('&', start);
            int end = (amp >= 0) ? amp : len;

            // Find equals sign
            int eq = s.indexOf('=', start);
            if (eq > start && eq < end) {
                String key = s.substring(start, eq);
                String value = s.substring(eq + 1, end);
                m.put(key, value);
            }

            start = end + 1;
        }

        return m;
    }

    /**
     * Optimized header parsing with ThreadLocal HashMap reuse.
     */
    static Map<String, String> parseHeaders(String h) {
        HashMap<String, String> m = HEADER_CACHE.get();
        m.clear();  // Reuse the map

        if (h == null || h.isEmpty()) {
            return m;
        }

        // Optimized parsing without split() allocation
        int start = 0;
        int len = h.length();

        while (start < len) {
            // Find end of current header line
            int newline = h.indexOf('\n', start);
            int end = (newline >= 0) ? newline : len;

            // Find colon
            int colon = h.indexOf(':', start);
            if (colon > start && colon < end) {
                // Trim key (convert to lowercase)
                int keyStart = start;
                int keyEnd = colon;
                while (keyStart < keyEnd && h.charAt(keyStart) == ' ') keyStart++;
                while (keyEnd > keyStart && h.charAt(keyEnd - 1) == ' ') keyEnd--;
                String key = h.substring(keyStart, keyEnd).toLowerCase();

                // Trim value
                int valStart = colon + 1;
                int valEnd = end;
                while (valStart < valEnd && h.charAt(valStart) == ' ') valStart++;
                while (valEnd > valStart && h.charAt(valEnd - 1) == ' ') valEnd--;
                String value = h.substring(valStart, valEnd);

                m.put(key, value);
            }

            start = end + 1;
        }

        return m;
    }
}
