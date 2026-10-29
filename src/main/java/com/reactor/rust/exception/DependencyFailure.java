package com.reactor.rust.exception;

/**
 * Marks a temporary dependency failure for the common HTTP 503 error contract.
 * Optional integrations can implement this on their own exception without allocating
 * a wrapping exception. It does not authorize replay or retry of the failed operation.
 */
public interface DependencyFailure {
}
