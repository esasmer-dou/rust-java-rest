package com.reactor.rust.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generates exact-class record writers for the direct ByteBuffer JSON path. */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class DirectJsonWriterProcessor extends AbstractProcessor {

    private static final String ANNOTATION =
            "com.reactor.rust.annotations.GenerateDirectJsonWriter";
    private static final String PROVIDER_PREFIX = "ReactorDirectJsonWriterProvider_";
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
                generateWriter(record, true);
            }
        }
        if (roundEnv.processingOver() && !providerGenerated && !writers.isEmpty()) {
            providerGenerated = true;
            generateProvider();
        }
        return true;
    }

    private void generateWriter(TypeElement record, boolean explicit) {
        String packageName = processingEnv.getElementUtils().getPackageOf(record)
                .getQualifiedName().toString();
        String simpleName = record.getSimpleName() + "DirectJsonWriter";
        String generatedName = packageName + "." + simpleName;
        if (!generated.add(generatedName)) {
            return;
        }

        LinkedHashSet<String> visiting = new LinkedHashSet<>();
        visiting.add(record.getQualifiedName().toString());
        List<ComponentModel> components = components(record, visiting);
        if (components == null) {
            if (explicit) {
                error(record, "Direct JSON generation found an unsupported or recursive component. "
                        + "Supported values are scalars, nested records, Optional, arrays and Iterable values. "
                        + "Use an explicit business writer for maps, byte arrays or cyclic graphs.");
            }
            return;
        }

        try {
            FieldTable fieldTable = fieldTable(components);
            JavaFileObject source = processingEnv.getFiler().createSourceFile(generatedName, record);
            try (Writer writer = source.openWriter()) {
                writer.write("package " + packageName + ";\n\n");
                writer.write("public final class " + simpleName
                        + " implements com.reactor.rust.json.DirectJsonWriter<"
                        + record.getQualifiedName() + "> {\n");
                writeFieldTable(writer, fieldTable.bytes());
                writer.write("    public static final " + simpleName + " INSTANCE = new "
                        + simpleName + "();\n\n");
                writer.write("    @Override\n");
                writer.write("    public int write(" + record.getQualifiedName()
                        + " value, java.nio.ByteBuffer out, int offset) {\n");
                writer.write("        var json = com.reactor.rust.json.JsonBufferWriter.reusable(out, offset);\n");
                writer.write("        if (value == null) {\n");
                writer.write("            return json.nullValue().result();\n");
                writer.write("        }\n");
                writer.write("        json.beginObject();\n");
                int[] sequence = {0};
                writeComponents(writer, components, "value", "        ", sequence, fieldTable);
                writer.write("        return json.endObject().result();\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
            writers.add(new WriterModel(record.getQualifiedName().toString(), generatedName));
        } catch (IOException failure) {
            error(record, "Failed to generate direct JSON writer: " + failure.getMessage());
        }
    }

    private List<ComponentModel> components(TypeElement record, LinkedHashSet<String> visiting) {
        List<ComponentModel> components = new ArrayList<>();
        for (RecordComponentElement component : record.getRecordComponents()) {
            ValueModel value = valueModel(component.asType(), visiting);
            if (value == null) {
                return null;
            }
            components.add(new ComponentModel(component.getSimpleName().toString(), value));
        }
        return components;
    }

    private ValueModel valueModel(TypeMirror type, LinkedHashSet<String> visiting) {
        TypeKind kind = type.getKind();
        if (kind == TypeKind.BYTE || kind == TypeKind.SHORT || kind == TypeKind.INT) {
            return ValueModel.scalar(ValueKind.INT);
        }
        if (kind == TypeKind.LONG) {
            return ValueModel.scalar(ValueKind.LONG);
        }
        if (kind == TypeKind.FLOAT || kind == TypeKind.DOUBLE) {
            return ValueModel.scalar(ValueKind.DOUBLE);
        }
        if (kind == TypeKind.BOOLEAN) {
            return ValueModel.scalar(ValueKind.BOOLEAN);
        }
        if (kind == TypeKind.CHAR) {
            return ValueModel.scalar(ValueKind.CHARACTER);
        }
        if (kind == TypeKind.ARRAY) {
            TypeMirror component = ((ArrayType) type).getComponentType();
            if (component.getKind() == TypeKind.BYTE || component.getKind() == TypeKind.CHAR) {
                return null;
            }
            ValueModel element = valueModel(component, visiting);
            return element == null ? null : ValueModel.container(ValueKind.ARRAY, element);
        }

        String name = processingEnv.getTypeUtils().erasure(type).toString();
        if (name.equals("java.lang.String")) {
            return ValueModel.scalar(ValueKind.STRING);
        }
        if (name.equals("java.lang.Byte") || name.equals("java.lang.Short")
                || name.equals("java.lang.Integer")) {
            return ValueModel.scalar(ValueKind.BOXED_INT);
        }
        if (name.equals("java.lang.Long")) {
            return ValueModel.scalar(ValueKind.BOXED_LONG);
        }
        if (name.equals("java.lang.Float") || name.equals("java.lang.Double")) {
            return ValueModel.scalar(ValueKind.BOXED_DOUBLE);
        }
        if (name.equals("java.lang.Boolean")) {
            return ValueModel.scalar(ValueKind.BOXED_BOOLEAN);
        }
        if (name.equals("java.lang.Character")) {
            return ValueModel.scalar(ValueKind.TO_STRING);
        }
        if (name.equals("java.math.BigDecimal") || name.equals("java.math.BigInteger")) {
            return ValueModel.scalar(ValueKind.DECIMAL);
        }
        if (name.startsWith("java.time.") || name.equals("java.util.UUID")) {
            return ValueModel.scalar(ValueKind.TO_STRING);
        }

        Element element = processingEnv.getTypeUtils().asElement(type);
        if (element != null && element.getKind() == ElementKind.ENUM) {
            return ValueModel.scalar(ValueKind.TO_STRING);
        }
        if (name.equals("java.util.Optional")) {
            ValueModel elementValue = genericValue(type, visiting);
            return elementValue == null ? null : ValueModel.container(ValueKind.OPTIONAL, elementValue);
        }
        if (isIterable(type)) {
            ValueModel elementValue = genericValue(type, visiting);
            return elementValue == null ? null : ValueModel.container(ValueKind.ITERABLE, elementValue);
        }
        if (element instanceof TypeElement nestedRecord && nestedRecord.getKind() == ElementKind.RECORD) {
            String recordName = nestedRecord.getQualifiedName().toString();
            if (!visiting.add(recordName)) {
                return null;
            }
            List<ComponentModel> nestedComponents = components(nestedRecord, visiting);
            visiting.remove(recordName);
            return nestedComponents == null ? null : ValueModel.record(nestedComponents);
        }
        return null;
    }

    private ValueModel genericValue(TypeMirror type, LinkedHashSet<String> visiting) {
        if (!(type instanceof DeclaredType declared) || declared.getTypeArguments().size() != 1) {
            return null;
        }
        TypeMirror argument = declared.getTypeArguments().get(0);
        if (argument.getKind() == TypeKind.WILDCARD || argument.getKind() == TypeKind.TYPEVAR) {
            return null;
        }
        return valueModel(argument, visiting);
    }

    private boolean isIterable(TypeMirror type) {
        TypeElement iterable = processingEnv.getElementUtils().getTypeElement("java.lang.Iterable");
        return iterable != null && processingEnv.getTypeUtils().isAssignable(
                processingEnv.getTypeUtils().erasure(type),
                processingEnv.getTypeUtils().erasure(iterable.asType()));
    }

    private void writeComponents(
            Writer writer,
            List<ComponentModel> components,
            String owner,
            String indent,
            int[] sequence,
            FieldTable fieldTable) throws IOException {
        for (int index = 0; index < components.size(); index++) {
            ComponentModel component = components.get(index);
            if (index > 0) {
                writer.write(indent + "json.comma();\n");
            }
            String accessor = owner + "." + component.name() + "()";
            FieldSlice field = fieldTable.fields().get(component.name());
            writer.write(indent + "json.fieldPrefix(FIELD_PREFIXES, "
                    + field.offset() + ", " + field.length() + ");\n");
            writeValue(writer, component.value(), accessor, indent, sequence, fieldTable);
        }
    }

    private void writeValue(
            Writer writer,
            ValueModel model,
            String expression,
            String indent,
            int[] sequence,
            FieldTable fieldTable) throws IOException {
        switch (model.kind()) {
            case INT -> writer.write(indent + "json.number(" + expression + ");\n");
            case LONG -> writer.write(indent + "json.number(" + expression + ");\n");
            case DOUBLE -> writer.write(indent + "json.number(" + expression + ");\n");
            case BOOLEAN -> writer.write(indent + "json.bool(" + expression + ");\n");
            case CHARACTER -> writer.write(indent + "json.string(String.valueOf(" + expression + "));\n");
            case STRING -> writer.write(indent + "json.string(" + expression + ");\n");
            case TO_STRING -> writeNullableToString(writer, expression, indent, false, sequence);
            case DECIMAL -> writeNullableToString(writer, expression, indent, true, sequence);
            case BOXED_INT -> writeNullableNumber(writer, expression, indent, "intValue", sequence);
            case BOXED_LONG -> writeNullableNumber(writer, expression, indent, "longValue", sequence);
            case BOXED_DOUBLE -> writeNullableNumber(writer, expression, indent, "doubleValue", sequence);
            case BOXED_BOOLEAN -> writeNullableBoolean(writer, expression, indent, sequence);
            case RECORD -> writeRecord(writer, model, expression, indent, sequence, fieldTable);
            case ARRAY, ITERABLE -> writeArrayLike(writer, model, expression, indent, sequence, fieldTable);
            case OPTIONAL -> writeOptional(writer, model, expression, indent, sequence, fieldTable);
        }
    }

    private void writeRecord(
            Writer writer,
            ValueModel model,
            String expression,
            String indent,
            int[] sequence,
            FieldTable fieldTable) throws IOException {
        String local = "nested_" + sequence[0]++;
        writer.write(indent + "var " + local + " = " + expression + ";\n");
        writer.write(indent + "if (" + local + " == null) {\n");
        writer.write(indent + "    json.nullValue();\n");
        writer.write(indent + "} else {\n");
        writer.write(indent + "    json.beginObject();\n");
        writeComponents(writer, model.components(), local, indent + "    ", sequence, fieldTable);
        writer.write(indent + "    json.endObject();\n");
        writer.write(indent + "}\n");
    }

    private void writeArrayLike(
            Writer writer,
            ValueModel model,
            String expression,
            String indent,
            int[] sequence,
            FieldTable fieldTable) throws IOException {
        String local = "values_" + sequence[0]++;
        String first = "first_" + sequence[0]++;
        String item = "item_" + sequence[0]++;
        writer.write(indent + "var " + local + " = " + expression + ";\n");
        writer.write(indent + "if (" + local + " == null) {\n");
        writer.write(indent + "    json.nullValue();\n");
        writer.write(indent + "} else {\n");
        writer.write(indent + "    json.beginArray();\n");
        writer.write(indent + "    boolean " + first + " = true;\n");
        writer.write(indent + "    for (var " + item + " : " + local + ") {\n");
        writer.write(indent + "        if (!" + first + ") json.comma();\n");
        writer.write(indent + "        " + first + " = false;\n");
        writeValue(writer, model.element(), item, indent + "        ", sequence, fieldTable);
        writer.write(indent + "    }\n");
        writer.write(indent + "    json.endArray();\n");
        writer.write(indent + "}\n");
    }

    private void writeOptional(
            Writer writer,
            ValueModel model,
            String expression,
            String indent,
            int[] sequence,
            FieldTable fieldTable) throws IOException {
        String local = "optional_" + sequence[0]++;
        writer.write(indent + "var " + local + " = " + expression + ";\n");
        writer.write(indent + "if (" + local + " == null || " + local + ".isEmpty()) {\n");
        writer.write(indent + "    json.nullValue();\n");
        writer.write(indent + "} else {\n");
        writeValue(writer, model.element(), local + ".get()", indent + "    ", sequence, fieldTable);
        writer.write(indent + "}\n");
    }

    private void writeNullableToString(
            Writer writer,
            String expression,
            String indent,
            boolean rawNumber,
            int[] sequence) throws IOException {
        String local = "scalar_" + sequence[0]++;
        writer.write(indent + "var " + local + " = " + expression + ";\n");
        writer.write(indent + "if (" + local + " == null) json.nullValue(); else json."
                + (rawNumber ? "rawAscii" : "string") + "(" + local + ".toString());\n");
    }

    private void writeNullableNumber(
            Writer writer, String expression, String indent, String method, int[] sequence) throws IOException {
        String local = "number_" + sequence[0]++;
        writer.write(indent + "var " + local + " = " + expression + ";\n");
        writer.write(indent + "if (" + local + " == null) json.nullValue(); else json.number("
                + local + "." + method + "());\n");
    }

    private void writeNullableBoolean(
            Writer writer, String expression, String indent, int[] sequence) throws IOException {
        String local = "boolean_" + sequence[0]++;
        writer.write(indent + "var " + local + " = " + expression + ";\n");
        writer.write(indent + "if (" + local + " == null) json.nullValue(); else json.bool("
                + local + ".booleanValue());\n");
    }

    private static FieldTable fieldTable(List<ComponentModel> components) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collectFieldNames(components, names);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Map<String, FieldSlice> fields = new LinkedHashMap<>();
        for (String name : names) {
            byte[] encoded = ("\"" + name + "\":").getBytes(StandardCharsets.UTF_8);
            fields.put(name, new FieldSlice(bytes.size(), encoded.length));
            bytes.writeBytes(encoded);
        }
        return new FieldTable(bytes.toByteArray(), Map.copyOf(fields));
    }

    private static void collectFieldNames(List<ComponentModel> components, Set<String> names) {
        for (ComponentModel component : components) {
            names.add(component.name());
            collectNestedFieldNames(component.value(), names);
        }
    }

    private static void collectNestedFieldNames(ValueModel value, Set<String> names) {
        if (value.kind() == ValueKind.RECORD) {
            collectFieldNames(value.components(), names);
        }
        if (value.element() != null) {
            collectNestedFieldNames(value.element(), names);
        }
    }

    private static void writeFieldTable(Writer writer, byte[] bytes) throws IOException {
        writer.write("    private static final byte[] FIELD_PREFIXES = {");
        for (int index = 0; index < bytes.length; index++) {
            if (index > 0) {
                writer.write(", ");
            }
            writer.write(Byte.toString(bytes[index]));
        }
        writer.write("};\n");
    }

    private void generateProvider() {
        writers.sort(Comparator.comparing(WriterModel::recordType));
        String simpleName = PROVIDER_PREFIX + stableSuffix(writers);
        String provider = "com.reactor.generated." + simpleName;
        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(provider);
            try (Writer writer = source.openWriter()) {
                writer.write("package com.reactor.generated;\n\n");
                writer.write("public final class " + simpleName
                        + " implements com.reactor.rust.json.DirectJsonWriterProvider {\n");
                writer.write("    @Override\n");
                writer.write("    public com.reactor.rust.json.DirectJsonWriter<?> findWriter(Class<?> type) {\n");
                for (WriterModel model : writers) {
                    writer.write("        if (type == " + model.recordType() + ".class) return "
                            + model.writerType() + ".INSTANCE;\n");
                }
                writer.write("        return null;\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
            FileObject service = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", SERVICE);
            try (Writer writer = service.openWriter()) {
                writer.write(provider);
                writer.write('\n');
            }
        } catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate direct JSON writer provider: " + failure.getMessage());
        }
    }

    private static String stableSuffix(List<WriterModel> models) {
        long hash = 0xcbf29ce484222325L;
        for (WriterModel model : models) {
            String value = model.recordType();
            for (int index = 0; index < value.length(); index++) {
                hash ^= value.charAt(index);
                hash *= 0x100000001b3L;
            }
            hash ^= '\n';
            hash *= 0x100000001b3L;
        }
        return Long.toUnsignedString(hash, 16);
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
        DECIMAL,
        CHARACTER,
        RECORD,
        ARRAY,
        ITERABLE,
        OPTIONAL
    }

    private record ValueModel(
            ValueKind kind,
            ValueModel element,
            List<ComponentModel> components) {

        static ValueModel scalar(ValueKind kind) {
            return new ValueModel(kind, null, List.of());
        }

        static ValueModel container(ValueKind kind, ValueModel element) {
            return new ValueModel(kind, element, List.of());
        }

        static ValueModel record(List<ComponentModel> components) {
            return new ValueModel(ValueKind.RECORD, null, List.copyOf(components));
        }
    }

    private record ComponentModel(String name, ValueModel value) {}

    private record FieldSlice(int offset, int length) {}

    private record FieldTable(byte[] bytes, Map<String, FieldSlice> fields) {}

    private record WriterModel(String recordType, String writerType) {}
}
