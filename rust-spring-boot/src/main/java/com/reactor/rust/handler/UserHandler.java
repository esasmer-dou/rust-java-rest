package com.reactor.rust.handler;

import com.reactor.rust.annotations.*;
import com.reactor.rust.dto.UserCreateRequest;
import com.reactor.rust.dto.UserResponse;
import com.reactor.rust.http.HttpStatus;
import com.reactor.rust.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * User Handler - Demonstrates the new Spring Boot-like annotation approach.
 *
 * Features:
 * - @RequestMapping for class-level base path
 * - @GetMapping, @PostMapping, @PutMapping, @DeleteMapping for HTTP methods
 * - @PathVariable for path parameters
 * - @RequestParam for query parameters
 * - @HeaderParam for HTTP headers
 * - @RequestBody for request body with @Valid validation
 * - @ResponseStatus for custom HTTP status codes
 * - ResponseEntity<T> return type
 */
@RequestMapping("/users")
public class UserHandler {

    private final Map<Integer, UserResponse> users = new HashMap<>();
    private final AtomicInteger idGenerator = new AtomicInteger(1);

    /**
     * GET /users/{id}
     * Get user by ID using @PathVariable.
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(
            @PathVariable("id") int id
    ) {
        UserResponse user = users.get(id);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new UserResponse(id, "User not found", null));
        }
        return ResponseEntity.ok(user);
    }

    /**
     * GET /users/search
     * Search users using @RequestParam.
     */
    @GetMapping("/search")
    public ResponseEntity<UserResponse> searchUsers(
            @RequestParam(value = "name") String name,
            @RequestParam(value = "page") String page,
            @HeaderParam(value = "X-Request-ID") String requestId
    ) {
        // Simulated search - returns a sample user
        return ResponseEntity.ok(new UserResponse(
                1,
                "Search results for: " + name + " (page " + page + ")",
                "request-" + requestId
        ));
    }

    /**
     * POST /users
     * Create user using @RequestBody with @Valid validation.
     */
    @PostMapping("")
    @ResponseStatus(201) // HttpStatus.CREATED
    public ResponseEntity<UserResponse> createUser(
            @RequestBody @Valid UserCreateRequest request,
            @HeaderParam("X-Request-ID") String requestId
    ) {
        int id = idGenerator.getAndIncrement();
        UserResponse response = new UserResponse(
                id,
                request.name(),
                requestId
        );
        users.put(id, response);
        return ResponseEntity.created(response);
    }

    /**
     * DELETE /users/{id}
     * Delete user by ID.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<UserResponse> deleteUser(
            @PathVariable("id") int id
    ) {
        UserResponse removed = users.remove(id);
        if (removed == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new UserResponse(id, "User not found", null));
        }
        return ResponseEntity.noContent();
    }

    /**
     * PUT /users/{id}
     * Update user.
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable("id") int id,
            @RequestBody @Valid UserCreateRequest request
    ) {
        if (!users.containsKey(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new UserResponse(id, "User not found", null));
        }
        UserResponse updated = new UserResponse(id, request.name(), request.email());
        users.put(id, updated);
        return ResponseEntity.ok(updated);
    }
}
