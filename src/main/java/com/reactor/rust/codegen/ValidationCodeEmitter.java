package com.reactor.rust.codegen;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.reactor.rust.codegen.ReactorStartupProcessor.*;

/** Emits record validation without changing runtime contracts. */
final class ValidationCodeEmitter {

    private final ReactorStartupProcessor processor;

    ValidationCodeEmitter(ReactorStartupProcessor processor) {
        this.processor = processor;
    }

    final Map<String, TypeElement> validationTypes = new TreeMap<>();

    String generateValidator(TypeElement type) {
        String packageName = processor.environment().getElementUtils().getPackageOf(type)
                .getQualifiedName().toString();
        String localTypeName = type.getQualifiedName().toString();
        if (!packageName.isEmpty()) {
            localTypeName = localTypeName.substring(packageName.length() + 1);
        }
        String simpleName = localTypeName.replace('.', '_') + "__ReactorValidator";
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        Map<String, String> patterns = validatorPatterns(type);
        try {
            JavaFileObject source = processor.environment().getFiler().createSourceFile(qualifiedName, type);
            try (Writer writer = source.openWriter()) {
                if (!packageName.isEmpty()) {
                    writer.write("package " + packageName + ";\n\n");
                }
                writer.write("public final class " + simpleName + " {\n");
                writer.write("    private " + simpleName + "() {}\n\n");
                int patternIndex = 0;
                for (String expression : patterns.values()) {
                    writer.write("    private static final java.util.regex.Pattern PATTERN_"
                            + patternIndex++ + " = java.util.regex.Pattern.compile(\""
                            + processor.escape(expression) + "\");\n");
                }
                if (!patterns.isEmpty()) writer.write("\n");
                writer.write("    public static void register() {\n");
                writer.write("        com.reactor.rust.validation.GeneratedValidators.register("
                        + type.getQualifiedName() + ".class, " + simpleName + "::validate, ");
                writeDefaultValues(writer, type);
                writer.write(");\n");
                writer.write("    }\n\n");
                writer.write("    private static com.reactor.rust.validation.ValidationResult validate(Object raw) {\n");
                writer.write("        " + type.getQualifiedName() + " value = ("
                        + type.getQualifiedName() + ") raw;\n");
                writer.write("        java.util.ArrayList<com.reactor.rust.validation.ConstraintViolation> violations = null;\n");
                int fieldIndex = 0;
                for (RecordComponentElement component : type.getRecordComponents()) {
                    writeFieldValidation(writer, type, component, fieldIndex++, patterns);
                }
                writer.write("        return com.reactor.rust.validation.GeneratedValidationSupport.result(violations);\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
            return qualifiedName;
        } catch (IOException failure) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate validator for " + type.getQualifiedName() + ": " + failure.getMessage(),
                    type);
            return null;
        }
    }

    Map<String, String> validatorPatterns(TypeElement type) {
        Map<String, String> patterns = new LinkedHashMap<>();
        for (RecordComponentElement component : type.getRecordComponents()) {
            AnnotationMirror pattern = validationAnnotation(type, component, PATTERN);
            if (pattern != null) {
                patterns.put(component.getSimpleName() + "#pattern",
                        processor.annotationString(pattern, "regexp", ""));
            }
            AnnotationMirror field = validationAnnotation(type, component, FIELD);
            String fieldPattern = field == null ? "" : processor.annotationString(field, "pattern", "");
            if (!fieldPattern.isEmpty()) {
                patterns.put(component.getSimpleName() + "#field", fieldPattern);
            }
        }
        return patterns;
    }

    void writeDefaultValues(Writer writer, TypeElement type) throws IOException {
        List<String> entries = new ArrayList<>();
        for (RecordComponentElement component : type.getRecordComponents()) {
            AnnotationMirror field = validationAnnotation(type, component, FIELD);
            String defaultValue = field == null ? "" : processor.annotationString(field, "defaultValue", "");
            if (!defaultValue.isEmpty()) {
                entries.add("java.util.Map.entry(\"" + processor.escape(component.getSimpleName().toString())
                        + "\", \"" + processor.escape(defaultValue) + "\")");
            }
        }
        if (entries.isEmpty()) {
            writer.write("java.util.Map.of()");
        } else {
            writer.write("java.util.Map.ofEntries(" + String.join(", ", entries) + ")");
        }
    }

    void writeFieldValidation(
            Writer writer,
            TypeElement owner,
            RecordComponentElement component,
            int fieldIndex,
            Map<String, String> patterns) throws IOException {
        String fieldName = component.getSimpleName().toString();
        String variable = "field" + fieldIndex;
        writer.write("        var " + variable + " = value." + fieldName + "();\n");
        AnnotationMirror notNull = validationAnnotation(owner, component, NOT_NULL);
        AnnotationMirror field = validationAnnotation(owner, component, FIELD);
        boolean required = field != null && processor.annotationBoolean(field, "required", false);
        boolean primitive = component.asType().getKind().isPrimitive();
        if (!primitive) {
            writer.write("        if (" + variable + " == null) {\n");
            if (notNull != null) {
                writeViolation(writer, "            ", fieldName,
                        processor.annotationString(notNull, "message", "must not be null"), "null");
            } else if (required) {
                writeViolation(writer, "            ", fieldName, "is required", "null");
            }
            writer.write("        } else {\n");
            writeNonNullValidations(writer, owner, component, variable, "            ", patterns, field);
            writer.write("        }\n");
        } else {
            writeNonNullValidations(writer, owner, component, variable, "        ", patterns, field);
        }
    }

    void writeNonNullValidations(
            Writer writer,
            TypeElement owner,
            RecordComponentElement component,
            String variable,
            String indent,
            Map<String, String> patterns,
            AnnotationMirror field) throws IOException {
        String name = component.getSimpleName().toString();
        AnnotationMirror notBlank = validationAnnotation(owner, component, NOT_BLANK);
        if (notBlank != null) {
            writeConditionalViolation(writer, indent,
                    "com.reactor.rust.validation.GeneratedValidationSupport.isBlank(" + variable + ")",
                    name, processor.annotationString(notBlank, "message", "must not be blank"), variable);
        }
        AnnotationMirror notEmpty = validationAnnotation(owner, component, NOT_EMPTY);
        if (notEmpty != null) {
            writeConditionalViolation(writer, indent,
                    "com.reactor.rust.validation.GeneratedValidationSupport.isEmpty(" + variable + ")",
                    name, processor.annotationString(notEmpty, "message", "must not be empty"), variable);
        }
        AnnotationMirror size = validationAnnotation(owner, component, SIZE);
        if (size != null) {
            int min = processor.annotationInt(size, "min", 0);
            int max = processor.annotationInt(size, "max", Integer.MAX_VALUE);
            String length = "com.reactor.rust.validation.GeneratedValidationSupport.length(" + variable + ")";
            String message = processor.annotationString(size, "message", "size must be between {min} and {max}")
                    .replace("{min}", Integer.toString(min))
                    .replace("{max}", Integer.toString(max));
            writeConditionalViolation(writer, indent,
                    length + " < " + min + " || " + length + " > " + max,
                    name, message, variable);
        }
        AnnotationMirror email = validationAnnotation(owner, component, EMAIL);
        if (email != null) {
            writeConditionalViolation(writer, indent,
                    "com.reactor.rust.validation.GeneratedValidationSupport.invalidEmailIfString(" + variable + ")",
                    name, processor.annotationString(email, "message", "must be a valid email address"), variable);
        }
        AnnotationMirror pattern = validationAnnotation(owner, component, PATTERN);
        if (pattern != null) {
            writeConditionalViolation(writer, indent,
                    "com.reactor.rust.validation.GeneratedValidationSupport.mismatchesIfString("
                            + patternName(patterns, name + "#pattern") + ", " + variable + ")",
                    name,
                    processor.annotationString(pattern, "message", "must match pattern '{regexp}'")
                            .replace("{regexp}", processor.annotationString(pattern, "regexp", "")),
                    variable);
        }
        writeNumericConstraint(writer, component, validationAnnotation(owner, component, MIN),
                "value", "<", indent, name, variable, true);
        writeNumericConstraint(writer, component, validationAnnotation(owner, component, MAX),
                "value", ">", indent, name, variable, true);
        AnnotationMirror positive = validationAnnotation(owner, component, POSITIVE);
        if (positive != null) {
            writeNumericCondition(writer, component, indent, name, variable, "<=", "0",
                    processor.annotationString(positive, "message", "must be positive"));
        }
        AnnotationMirror negative = validationAnnotation(owner, component, NEGATIVE);
        if (negative != null) {
            writeNumericCondition(writer, component, indent, name, variable, ">=", "0",
                    processor.annotationString(negative, "message", "must be negative"));
        }
        writeNumericConstraint(writer, component, validationAnnotation(owner, component, DECIMAL_MIN),
                "value", "<", indent, name, variable, false);
        writeNumericConstraint(writer, component, validationAnnotation(owner, component, DECIMAL_MAX),
                "value", ">", indent, name, variable, false);
        if (field != null) {
            if (processor.annotationBoolean(field, "required", false)) {
                writeConditionalViolation(writer, indent,
                        "com.reactor.rust.validation.GeneratedValidationSupport.isBlankString(" + variable + ")",
                        name, "is required and cannot be blank", variable);
            }
            String fieldPattern = processor.annotationString(field, "pattern", "");
            if (!fieldPattern.isEmpty()) {
                writeConditionalViolation(writer, indent,
                        "com.reactor.rust.validation.GeneratedValidationSupport.mismatchesIfString("
                                + patternName(patterns, name + "#field") + ", " + variable + ")",
                        name, "does not match pattern: " + fieldPattern, variable);
            }
            double min = processor.annotationDouble(field, "min", Double.MIN_VALUE);
            if (Double.compare(min, Double.MIN_VALUE) != 0) {
                writeNumericCondition(writer, component, indent, name, variable, "<",
                        Double.toString(min), "must be >= " + min);
            }
            double max = processor.annotationDouble(field, "max", Double.MAX_VALUE);
            if (Double.compare(max, Double.MAX_VALUE) != 0) {
                writeNumericCondition(writer, component, indent, name, variable, ">",
                        Double.toString(max), "must be <= " + max);
            }
        }
    }

    void writeNumericConstraint(
            Writer writer,
            RecordComponentElement component,
            AnnotationMirror constraint,
            String key,
            String operator,
            String indent,
            String fieldName,
            String variable,
            boolean integral) throws IOException {
        if (constraint == null) return;
        String threshold = integral
                ? Long.toString(processor.annotationLong(constraint, key, 0))
                : processor.annotationString(constraint, key, "0");
        String message = processor.annotationString(constraint, "message", "invalid numeric value")
                .replace("{value}", threshold);
        writeNumericCondition(writer, component, indent, fieldName, variable,
                operator, integral ? threshold + "L" : threshold, message);
    }

    void writeNumericCondition(
            Writer writer,
            RecordComponentElement component,
            String indent,
            String fieldName,
            String variable,
            String operator,
            String threshold,
            String message) throws IOException {
        boolean primitiveNumber = switch (component.asType().getKind()) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
        String number = primitiveNumber
                ? variable
                : "com.reactor.rust.validation.GeneratedValidationSupport.number(" + variable + ")";
        String condition = number + " " + operator + " " + threshold;
        if (!primitiveNumber) {
            condition = "com.reactor.rust.validation.GeneratedValidationSupport.isNumber("
                    + variable + ") && " + condition;
        }
        writeConditionalViolation(writer, indent, condition, fieldName, message, variable);
    }

    void writeConditionalViolation(
            Writer writer,
            String indent,
            String condition,
            String fieldName,
            String message,
            String invalidValue) throws IOException {
        writer.write(indent + "if (" + condition + ") {\n");
        writeViolation(writer, indent + "    ", fieldName, message, invalidValue);
        writer.write(indent + "}\n");
    }

    void writeViolation(
            Writer writer,
            String indent,
            String fieldName,
            String message,
            String invalidValue) throws IOException {
        writer.write(indent + "violations = com.reactor.rust.validation.GeneratedValidationSupport.add("
                + "violations, \"" + processor.escape(fieldName) + "\", \"" + processor.escape(message)
                + "\", " + invalidValue + ");\n");
    }

    AnnotationMirror validationAnnotation(
            TypeElement owner,
            RecordComponentElement component,
            String annotationName) {
        AnnotationMirror direct = processor.annotation(component, annotationName);
        if (direct != null) return direct;
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && enclosed.getSimpleName().contentEquals(component.getSimpleName())) {
                return processor.annotation(enclosed, annotationName);
            }
        }
        return null;
    }

    String patternName(Map<String, String> patterns, String key) {
        int index = 0;
        for (String candidate : patterns.keySet()) {
            if (candidate.equals(key)) return "PATTERN_" + index;
            index++;
        }
        throw new IllegalStateException("Missing generated validation pattern " + key);
    }

}
