package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Request;
import java.util.List;

@CompiledJson
@Request
public record OrderRequest(
    String orderId,
    double amount,
    boolean paid,
    Address address,
    Customer customer,
    List<Item> items
) {}
