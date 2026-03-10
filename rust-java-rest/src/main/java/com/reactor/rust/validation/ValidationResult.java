package com.reactor.rust.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of validation containing any constraint violations found.
 */
public class ValidationResult {

    private static final ValidationResult SUCCESS = new ValidationResult(Collections.emptyList());

    private final List<ConstraintViolation> violations;

    private ValidationResult(List<ConstraintViolation> violations) {
        this.violations = violations;
    }

    public static ValidationResult success() {
        return SUCCESS;
    }

    public static ValidationResult of(List<ConstraintViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            return SUCCESS;
        }
        return new ValidationResult(Collections.unmodifiableList(violations));
    }

    public static ValidationResult failure(String field, String message, Object invalidValue) {
        List<ConstraintViolation> violations = new ArrayList<>();
        violations.add(new ConstraintViolation(field, message, invalidValue));
        return new ValidationResult(violations);
    }

    public boolean isValid() {
        return violations.isEmpty();
    }

    public boolean hasErrors() {
        return !violations.isEmpty();
    }

    public List<ConstraintViolation> getViolations() {
        return violations;
    }

    public String getFirstErrorMessage() {
        if (violations.isEmpty()) {
            return null;
        }
        return violations.get(0).getMessage();
    }

    public String getErrorMessages() {
        if (violations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            ConstraintViolation v = violations.get(i);
            sb.append(v.getField()).append(" ").append(v.getMessage());
        }
        return sb.toString();
    }
}
