package com.reactor.rust.exception;

import com.reactor.rust.http.ErrorResponses;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class CommonErrorsTest {
    @Test void syncAndAsyncHaveSamePublicContract() {
        for (Throwable error : new Throwable[]{new ConflictException("Already exists"),
                new TimeoutException("private"), new DependencyUnavailableException(new Exception("password=secret"))}) {
            assertEquals(HttpErrorMapper.map(error), HttpErrorMapper.map(new CompletionException(error)));
            assertEquals(HttpErrorMapper.map(error).status(), ErrorResponses.from(error).getStatus().getCode());
        }
        assertEquals(409, HttpErrorMapper.map(new ConflictException("Already exists")).status());
        assertFalse(HttpErrorMapper.map(new DependencyUnavailableException(new Exception("password=secret"))).message().contains("secret"));
    }
}
