package com.reactor.rust.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generates direct JDBC row mappers for record DTOs. */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class JdbcRecordMapperProcessor extends AbstractProcessor {

    private static final String ANNOTATION = "com.reactor.rust.annotations.GenerateJdbcMapper";
    private static final String COLUMN = "com.reactor.rust.annotations.JdbcColumn";
    private final Set<String> generated = new HashSet<>();

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(ANNOTATION);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement marker = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
        if (marker == null) {
            return false;
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(marker)) {
            if (!(element instanceof TypeElement record) || record.getKind() != ElementKind.RECORD) {
                error(element, "@GenerateJdbcMapper requires a record");
                continue;
            }
            generate(record);
        }
        return true;
    }

    private void generate(TypeElement record) {
        String packageName = processingEnv.getElementUtils().getPackageOf(record)
                .getQualifiedName().toString();
        String simpleName = record.getSimpleName() + "JdbcMapper";
        String generatedName = packageName + "." + simpleName;
        if (!generated.add(generatedName)) {
            return;
        }

        List<ComponentModel> components = new ArrayList<>();
        for (RecordComponentElement component : record.getRecordComponents()) {
            String expression = readExpression(component.asType(), columnName(component));
            if (expression == null) {
                error(component, "Unsupported generated JDBC component type: " + component.asType());
                return;
            }
            components.add(new ComponentModel(expression));
        }

        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(generatedName, record);
            try (Writer writer = source.openWriter()) {
                writer.write("package " + packageName + ";\n\n");
                writer.write("public final class " + simpleName + " {\n");
                writer.write("    private " + simpleName + "() {}\n\n");
                writer.write("    public static " + record.getQualifiedName()
                        + " map(java.sql.ResultSet row) throws java.sql.SQLException {\n");
                writer.write("        return new " + record.getQualifiedName() + "(\n");
                for (int index = 0; index < components.size(); index++) {
                    writer.write("                " + components.get(index).expression()
                            + (index + 1 == components.size() ? "\n" : ",\n"));
                }
                writer.write("        );\n");
                writer.write("    }\n\n");
                writer.write("    private static java.time.Instant instant(java.sql.ResultSet row, String column)"
                        + " throws java.sql.SQLException {\n");
                writer.write("        java.sql.Timestamp value = row.getTimestamp(column);\n");
                writer.write("        return value == null ? null : value.toInstant();\n");
                writer.write("    }\n\n");
                writer.write("    private static <E extends java.lang.Enum<E>> E enumValue("
                        + "java.sql.ResultSet row, String column, Class<E> type) throws java.sql.SQLException {\n");
                writer.write("        String value = row.getString(column);\n");
                writer.write("        return value == null ? null : java.lang.Enum.valueOf(type, value);\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
        } catch (IOException failure) {
            error(record, "Failed to generate JDBC mapper: " + failure.getMessage());
        }
    }

    private String readExpression(TypeMirror type, String column) {
        String quoted = "\"" + escape(column) + "\"";
        TypeKind kind = type.getKind();
        if (kind == TypeKind.BYTE) return "row.getByte(" + quoted + ")";
        if (kind == TypeKind.SHORT) return "row.getShort(" + quoted + ")";
        if (kind == TypeKind.INT) return "row.getInt(" + quoted + ")";
        if (kind == TypeKind.LONG) return "row.getLong(" + quoted + ")";
        if (kind == TypeKind.FLOAT) return "row.getFloat(" + quoted + ")";
        if (kind == TypeKind.DOUBLE) return "row.getDouble(" + quoted + ")";
        if (kind == TypeKind.BOOLEAN) return "row.getBoolean(" + quoted + ")";
        if (kind == TypeKind.CHAR) return "row.getString(" + quoted + ").charAt(0)";

        String name = type.toString();
        return switch (name) {
            case "java.lang.String" -> "row.getString(" + quoted + ")";
            case "java.lang.Byte" -> "(java.lang.Byte) row.getObject(" + quoted + ")";
            case "java.lang.Short" -> "(java.lang.Short) row.getObject(" + quoted + ")";
            case "java.lang.Integer" -> "(java.lang.Integer) row.getObject(" + quoted + ")";
            case "java.lang.Long" -> "(java.lang.Long) row.getObject(" + quoted + ")";
            case "java.lang.Float" -> "(java.lang.Float) row.getObject(" + quoted + ")";
            case "java.lang.Double" -> "(java.lang.Double) row.getObject(" + quoted + ")";
            case "java.lang.Boolean" -> "(java.lang.Boolean) row.getObject(" + quoted + ")";
            case "java.math.BigDecimal" -> "row.getBigDecimal(" + quoted + ")";
            case "java.time.Instant" -> "instant(row, " + quoted + ")";
            case "java.time.LocalDate" -> "row.getObject(" + quoted + ", java.time.LocalDate.class)";
            case "java.time.LocalDateTime" -> "row.getObject(" + quoted + ", java.time.LocalDateTime.class)";
            case "java.time.OffsetDateTime" -> "row.getObject(" + quoted + ", java.time.OffsetDateTime.class)";
            case "java.util.UUID" -> "row.getObject(" + quoted + ", java.util.UUID.class)";
            default -> enumExpression(type, quoted);
        };
    }

    private String enumExpression(TypeMirror type, String quotedColumn) {
        Element element = processingEnv.getTypeUtils().asElement(type);
        if (element != null && element.getKind() == ElementKind.ENUM) {
            return "enumValue(row, " + quotedColumn + ", " + type + ".class)";
        }
        return null;
    }

    private String columnName(RecordComponentElement component) {
        for (AnnotationMirror mirror : component.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString().equals(COLUMN)) {
                continue;
            }
            Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                    processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("value")) {
                    String value = entry.getValue().getValue().toString();
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        return snakeCase(component.getSimpleName().toString());
    }

    private static String snakeCase(String value) {
        StringBuilder result = new StringBuilder(value.length() + 4);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isUpperCase(current)) {
                if (index > 0) result.append('_');
                result.append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private record ComponentModel(String expression) {}
}
