package com.reactor.examples.crud;

import com.reactor.rust.annotations.DeleteMapping;
import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.PatchMapping;
import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.annotations.PostMapping;
import com.reactor.rust.annotations.RequestBody;
import com.reactor.rust.annotations.RequestMapping;
import com.reactor.rust.annotations.Valid;
import com.reactor.rust.http.HttpStatus;
import com.reactor.rust.http.ResponseEntity;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RequestMapping("/api/v1/products")
public final class ProductHandler {

    private final ConcurrentHashMap<Long, Product> products = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @GetMapping("/{id}")
    public ResponseEntity<Product> get(@PathVariable("id") long id) {
        Product product = products.get(id);
        return product == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(null)
                : ResponseEntity.ok(product);
    }

    @GetMapping("")
    public ResponseEntity<Product[]> list() {
        return ResponseEntity.ok(products.values().toArray(Product[]::new));
    }

    @PostMapping("")
    public ResponseEntity<Product> create(@RequestBody @Valid ProductCommand command) {
        long id = ids.incrementAndGet();
        Product product = new Product(id, command.name(), command.priceCents());
        products.put(id, product);
        return ResponseEntity.created(product);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Product> update(
            @PathVariable("id") long id,
            @RequestBody @Valid ProductCommand command) {
        Product updated = products.computeIfPresent(
                id,
                (ignored, current) -> new Product(id, command.name(), command.priceCents()));
        return updated == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(null)
                : ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id) {
        return products.remove(id) == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                : ResponseEntity.noContent();
    }
}
