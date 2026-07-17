package com.reactor.rust.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Generates exact-class scalar record writers for the direct ByteBuffer JSON path. */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class DirectJsonWriterProcessor extends AbstractProcessor {

    private static final String ANNOTATION =
            "com.reactor.rust.annotations.GenerateDirectJsonWriter";
    private static final String PROVIDER = "com.reactor.generated.ReactorDirectJsonWriterProvider";
    private static final String SERVICE =
            "META-INF/services/com.reactor.rust.json.DirectJsonWriterProvider";

    private final List<WriterModel> writers = new ArrayList<>();
    private final Set<String> generated = new HashSet<>();
    private boolean providerGenerated;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(ANNOTATION);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement marker = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
        if (marker != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(marker)) {
                if (!(element instanceof TypeElement record) || record.getKind() != ElementKind.RECORD) {
                    error(element, "@GenerateDirectJsonWriter requires a record");
                    continue;
                }
                generateWriter(record);
            }
        }
        if (roundEnv.processingOver() && !providerGenerated && !writers.isEmpty()) {
            providerGenerated = true;
            generateProvider();
        }
        return true;
    }

    private void generateWriter(TypeElement record) {
        String packageName = processingEnv.getElementUtils().getPackageOf(record)
                .getQualifiedName().toString();
        String simpleName = record.getSimpleName() + "DirectJsonWriter";
        String generatedName = packageName + "." + simpleName;
        if (!generated.add(generatedName)) {
            return;
        }
        List<ComponentModel> components = new ArrayList<>();
        for (RecordComponentElement component : record.getRecordComponents()) {
            ValueKind kind = valueKind(component.asType());
            if (kind == null) {
                error(component, "Direct JSON generation supports scalar record components only; "
                        + component.asType() + " requires an explicit business writer");
                return;
            }
            components.add(new ComponentModel(component.getSimpleName().toString(), kind));
        }

        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(generatedName, record);
            try (Writer writer = source.openWriter()) {
                writer.write("package " + packageName + ";\n\n");
                writer.write("public final class " + simpleName
                        + " implements com.reactor.rust.json.DirectJsonWriter<"
                        + record.getQualifiedName() + "> {\n");
                writer.write("    @Override\n");
                writer.write("    public int write(" + record.getQualifiedName()
                        + " value, java.nio.ByteBuffer out, int offset) {\n");
                writer.write("        var json = com.reactor.rust.json.JsonBufferWriter.reusable(out, offset);\n");
                writer.write("        if (value == null) {\n");
                writer.write("            return json.nullValue().result();\n");
                writer.write("        }\n");
                writer.write("        json.beginObject();\n");
                for (int index = 0; index < components.size(); index++) {
                    ComponentModel component = components.get(index);
                    if (index > 0) {
                        writer.write("        json.comma();\n");
                    }
                    writer.write("        " + writeField(component) + "\n");
                }
                writer.write("        return json.endObject().result();\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
            writers.add(new WriterModel(
                    record.getQualifiedName().toString(),
                    generatedName));
        } catch (IOException failure) {
            error(record, "Failed to generate direct JSON writer: " + failure.getMessage());
        }
    }

    private void generateProvider() {
        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(PROVIDER);
            try (Writer writer = source.openWriter()) {
                writer.write("package com.reactor.generated;\n\n");
                writer.write("public final class ReactorDirectJsonWriterProvider"
                        + " implements com.reactor.rust.json.DirectJsonWriterProvider {\n");
                for (int index = 0; index < writers.size(); index++) {
                    WriterModel model = writers.get(index);
                    writer.write("    private static final " + model.writerType() + " WRITER_" + index
                            + " = new " + model.writerType() + "();\n");
                }
                writer.write("    @Override\n");
                writer.write("    public com.reactor.rust.json.DirectJsonWriter<?> findWriter(Class<?> type) {\n");
                for (int index = 0; index < writers.size(); index++) {
                    WriterModel model = writers.get(index);
                    writer.write("        if (type == " + model.recordType() + ".class) return WRITER_"
                            + index + ";\n");
                }
                writer.write("        return null;\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
            FileObject service = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", SERVICE);
            try (Writer writer = service.openWriter()) {
                writer.write(PROVIDER);
                writer.write('\n');
            }
        } catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate direct JSON writer provider: " + failure.getMessage());
        }
    }

    private String writeField(ComponentModel component) {
        String name = component.name();
        String accessor = "value." + name + "()";
        return switch (component.kind()) {
            case INT -> "json.fieldInt(\"" + name + "\", " + accessor + ");";
            case LONG -> "json.fieldLong(\"" + name + "\", " + accessor + ");";
            case DOUBLE -> "json.fieldDouble(\"" + name + "\", " + accessor + ");";
            case BOOLEAN -> "json.fieldBoolean(\"" + name + "\", " + accessor + ");";
            case BOXED_INT -> boxedField(name, accessor, "number", "intValue");
            case BOXED_LONG -> boxedField(name, accessor, "number", "longValue");
            case BOXED_DOUBLE -> boxedField(name, accessor, "number", "doubleValue");
            case BOXED_BOOLEAN -> boxedField(name, accessor, "bool", "booleanValue");
            case STRING -> "json.fieldString(\"" + name + "\", " + accessor + ");";
            case TO_STRING -> "json.fieldString(\"" + name + "\", " + accessor
                    + " == null ? null : " + accessor + ".toString());";
            case CHARACTER -> "json.fieldString(\"" + name + "\", String.valueOf(" + accessor + "));";
        };
    }

    private static String boxedField(
            String name,
            String accessor,
            String writerMethod,
            String valueMethod) {
        return "json.fieldName(\"" + name + "\");\n"
                + "        if (" + accessor + " == null) json.nullValue(); "
                + "else json." + writerMethod + "(" + accessor + "." + valueMethod + "());";
    }

    private ValueKind valueKind(TypeMirror type) {
        TypeKind kind = type.getKind();
        if (kind == TypeKind.BYTE || kind == TypeKind.SHORT || kind == TypeKind.INT) {
            return ValueKind.INT;
        }
        if (kind == TypeKind.LONG) {
            return ValueKind.LONG;
        }
        if (kind == TypeKind.FLOAT || kind == TypeKind.DOUBLE) {
            return ValueKind.DOUBLE;
        }
        if (kind == TypeKind.BOOLEAN) {
            return ValueKind.BOOLEAN;
        }
        if (kind == TypeKind.CHAR) {
            return ValueKind.CHARACTER;
        }
        String name = type.toString();
        if (name.equals("java.lang.String")) {
            return ValueKind.STRING;
        }
        if (name.equals("java.lang.Byte") || name.equals("java.lang.Short")
                || name.equals("java.lang.Integer")) {
            return ValueKind.BOXED_INT;
        }
        if (name.equals("java.lang.Long")) {
            return ValueKind.BOXED_LONG;
        }
        if (name.equals("java.lang.Float") || name.equals("java.lang.Double")) {
            return ValueKind.BOXED_DOUBLE;
        }
        if (name.equals("java.lang.Boolean")) {
            return ValueKind.BOXED_BOOLEAN;
        }
        if (name.equals("java.lang.Character")) {
            return ValueKind.TO_STRING;
        }
        Element element = processingEnv.getTypeUtils().asElement(type);
        if (element != null && element.getKind() == ElementKind.ENUM) {
            return ValueKind.TO_STRING;
        }
        if (name.startsWith("java.time.")
                || name.equals("java.util.UUID")
                || name.equals("java.math.BigDecimal")
                || name.equals("java.math.BigInteger")) {
            return ValueKind.TO_STRING;
        }
        return null;
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private enum ValueKind {
        INT,
        LONG,
        DOUBLE,
        BOOLEAN,
        BOXED_INT,
        BOXED_LONG,
        BOXED_DOUBLE,
        BOXED_BOOLEAN,
        STRING,
        TO_STRING,
        CHARACTER
    }

    private record ComponentModel(String name, ValueKind kind) {}

    private record WriterModel(String recordType, String writerType) {}
}
