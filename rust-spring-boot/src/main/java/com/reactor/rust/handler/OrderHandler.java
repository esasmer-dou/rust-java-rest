package com.reactor.rust.handler;

import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.dto.Address;
import com.reactor.rust.dto.Customer;
import com.reactor.rust.dto.ErrorResponse;
import com.reactor.rust.dto.Item;
import com.reactor.rust.dto.OrderCreateRequest;
import com.reactor.rust.dto.OrderCreateResponse;
import com.reactor.rust.dto.OrderIdResponse;
import com.reactor.rust.dto.OrderRequest;
import com.reactor.rust.dto.OrderSearchResponse;
import com.reactor.rust.json.DslJsonService;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Order Handler - Pure Java, No Spring
 * Constraint #4: Pure Java - NO reflection libraries
 * Constraint #7: RECORD ZORUNLULUĞU - Only Records for DTOs
 */
public class OrderHandler {

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
        if (ct == null || !ct.contains("application/json")) {
            return DslJsonService.writeToBuffer(
                    new ErrorResponse("Unsupported Content-Type"),
                    out,
                    offset
            );
        }

        DslJsonService.parse(body, OrderCreateRequest.class); // Validate request

        return DslJsonService.writeToBuffer(
                new OrderCreateResponse(1, "OK", 15),
                out,
                offset
        );
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
        DslJsonService.parse(body, OrderCreateRequest.class);
        return DslJsonService.writeToBuffer(
                new OrderCreateResponse(2, "CANCELLED", 0),
                out,
                offset
        );
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
        return DslJsonService.writeToBuffer(
                new OrderSearchResponse(q.get("status"), q.get("page")),
                out,
                offset
        );
    }

    static Map<String, String> parseParams(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) {
            return m;
        }
        for (String p : s.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) {
                m.put(p.substring(0, i), p.substring(i + 1));
            }
        }
        return m;
    }

    static Map<String, String> parseHeaders(String h) {
        Map<String, String> map = new HashMap<>();
        if (h == null || h.isEmpty()) {
            return map;
        }

        for (String line : h.split("\n")) {
            int i = line.indexOf(':');
            if (i > 0) {
                map.put(
                        line.substring(0, i).trim().toLowerCase(),
                        line.substring(i + 1).trim()
                );
            }
        }
        return map;
    }
}
