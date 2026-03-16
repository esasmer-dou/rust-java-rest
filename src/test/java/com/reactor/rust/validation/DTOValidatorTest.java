package com.reactor.rust.validation;

import com.reactor.rust.annotations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DTOValidator.
 * Note: Validation annotations need RECORD_COMPONENT target to work on record fields.
 */
class DTOValidatorTest {

    private final DTOValidator validator = DTOValidator.getInstance();

    @Request
    public record SimpleRequest(String name) {}

    @Response
    public record TestResponse(String data) {}

    // Not a DTO
    public record NotADto(String value) {}

    @Test
    @DisplayName("isDTO returns true for @Request record")
    void testIsDTORequest() {
        assertTrue(validator.isDTO(SimpleRequest.class));
    }

    @Test
    @DisplayName("isDTO returns true for @Response record")
    void testIsDTOResponse() {
        assertTrue(validator.isDTO(TestResponse.class));
    }

    @Test
    @DisplayName("isDTO returns false for non-annotated record")
    void testIsDTONotADto() {
        assertFalse(validator.isDTO(NotADto.class));
    }

    @Test
    @DisplayName("Validate returns success for simple DTO")
    void testValidateSimpleDTO() {
        SimpleRequest request = new SimpleRequest("test");
        ValidationResult result = validator.validate(request);

        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("Singleton instance")
    void testSingletonInstance() {
        DTOValidator instance1 = DTOValidator.getInstance();
        DTOValidator instance2 = DTOValidator.getInstance();

        assertSame(instance1, instance2);
    }

    @Test
    @DisplayName("Validate non-record object returns success (only validates records)")
    void testValidateNonRecord() {
        Object obj = new Object();
        ValidationResult result = validator.validate(obj);

        // Non-record objects return success (not a DTO)
        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("ValidationResult success factory")
    void testValidationResultSuccess() {
        ValidationResult result = ValidationResult.success();

        assertTrue(result.isValid());
        assertTrue(result.getViolations().isEmpty());
        assertNull(result.getFirstErrorMessage());
        assertEquals("", result.getErrorMessages());
    }

    @Test
    @DisplayName("ValidationResult failure factory")
    void testValidationResultFailure() {
        ValidationResult result = ValidationResult.failure("field", "is invalid", "bad_value");

        assertFalse(result.isValid());
        assertEquals(1, result.getViolations().size());
        assertEquals("field", result.getViolations().get(0).getField());
        assertEquals("is invalid", result.getViolations().get(0).getMessage());
        assertEquals("bad_value", result.getViolations().get(0).getInvalidValue());
    }

    @Test
    @DisplayName("ValidationResult of empty list returns success")
    void testValidationResultOfEmpty() {
        ValidationResult result = ValidationResult.of(java.util.List.of());

        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("ValidationResult of null returns success")
    void testValidationResultOfNull() {
        ValidationResult result = ValidationResult.of(null);

        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("ValidationResult hasErrors")
    void testValidationResultHasErrors() {
        ValidationResult success = ValidationResult.success();
        ValidationResult failure = ValidationResult.failure("field", "error", null);

        assertFalse(success.hasErrors());
        assertTrue(failure.hasErrors());
    }

    @Test
    @DisplayName("ConstraintViolation toString")
    void testConstraintViolationToString() {
        ConstraintViolation violation = new ConstraintViolation("name", "must not be blank", "");

        String str = violation.toString();
        assertTrue(str.contains("name"));
        assertTrue(str.contains("must not be blank"));
    }

    @Test
    @DisplayName("ValidationResult getErrorMessages formats errors")
    void testValidationResultGetErrorMessages() {
        java.util.List<ConstraintViolation> violations = java.util.List.of(
            new ConstraintViolation("name", "is required", null),
            new ConstraintViolation("email", "is invalid", "bad")
        );
        ValidationResult result = ValidationResult.of(violations);

        String errors = result.getErrorMessages();
        assertTrue(errors.contains("name"));
        assertTrue(errors.contains("email"));
    }

    @Test
    @DisplayName("ValidationResult getFirstErrorMessage")
    void testValidationResultGetFirstErrorMessage() {
        java.util.List<ConstraintViolation> violations = java.util.List.of(
            new ConstraintViolation("name", "first error", null),
            new ConstraintViolation("email", "second error", "bad")
        );
        ValidationResult result = ValidationResult.of(violations);

        assertEquals("first error", result.getFirstErrorMessage());
    }
}
