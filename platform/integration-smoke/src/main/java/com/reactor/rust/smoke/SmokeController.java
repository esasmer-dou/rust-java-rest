package com.reactor.rust.smoke;

import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.annotations.RestController;
import com.reactor.rust.http.ResponseEntity;

@RestController
public final class SmokeController {

    @GetMapping(value = "/smoke/{id}", responseType = ResponseEntity.class)
    public ResponseEntity<SmokeResponse> get(@PathVariable("id") long id) {
        return ResponseEntity.ok(new SmokeResponse(id, "ready"));
    }
}
