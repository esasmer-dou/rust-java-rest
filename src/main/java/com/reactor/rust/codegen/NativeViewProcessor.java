package com.reactor.rust.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Opt-in compile-time views. Has no runtime dependencies on annotation classes. */
@SupportedAnnotationTypes("com.reactor.rust.view.NativeView")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class NativeViewProcessor extends AbstractProcessor {
    private static final String MARKER = "com.reactor.rust.view.NativeView";
    private static final String FIELD = "com.reactor.rust.view.ViewField";
    private static final String SLICE = "com.reactor.rust.view.Utf8Slice";
    private static final Set<String> RESERVED = Set.of("wrap", "clear", "close", "backingBuffer",
            "baseOffset", "byteSize", "beginWrap", "checkBound", "threadLocalView");
    private final Map<String, Model> models = new HashMap<>();
    private final Set<String> emitted = new HashSet<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        TypeElement marker = processingEnv.getElementUtils().getTypeElement(MARKER);
        if (marker == null || round.processingOver()) return true;
        for (Element element : round.getElementsAnnotatedWith(marker)) {
            try {
                Model model = model((TypeElement) element, new HashSet<>());
                emit(model);
            } catch (InvalidSchema ignored) {
                // A diagnostic has already been attached to the offending declaration.
            } catch (IOException failure) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "NativeView generation failed: " + failure.getMessage(), element);
            }
        }
        return true;
    }

    private Model model(TypeElement type, Set<String> visiting) {
        String name = type.getQualifiedName().toString();
        if (models.containsKey(name)) return models.get(name);
        if (!visiting.add(name)) fail(type, "Cyclic @NativeView graph");
        if (visiting.size() > 32) fail(type, "NativeView nesting exceeds the bounded depth of 32");
        if (type.getKind() != ElementKind.INTERFACE || !type.getTypeParameters().isEmpty()
                || type.getModifiers().contains(Modifier.SEALED)) {
            fail(type, "@NativeView requires a non-generic, non-sealed interface");
        }
        for (Element enclosing = type; enclosing instanceof TypeElement; enclosing = enclosing.getEnclosingElement()) {
            if (enclosing.getModifiers().contains(Modifier.PRIVATE)) fail(type, "View must be accessible to generated peers");
        }
        validateParents(type, new HashSet<>());
        int version = integer(type, MARKER, "schemaVersion");
        if (version <= 0) fail(type, "schemaVersion must be positive (versions are out-of-band)");
        List<Field> fields = new ArrayList<>();
        Set<Integer> indices = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Element member : processingEnv.getElementUtils().getAllMembers(type)) {
            if (!(member instanceof ExecutableElement method) || member.getKind() != ElementKind.METHOD) continue;
            boolean annotated = annotation(method, FIELD) != null;
            if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
                if (annotated) fail(method, "@ViewField requires an abstract instance getter");
                continue;
            }
            if (!annotated || !method.getParameters().isEmpty() || !method.getTypeParameters().isEmpty()
                    || !method.getThrownTypes().isEmpty() || RESERVED.contains(method.getSimpleName().toString())) {
                fail(method, "Every abstract view getter needs @ViewField, no parameters, generics, throws or reserved names");
            }
            int index = integer(method, FIELD, "index");
            int offset = integer(method, FIELD, "offset");
            if (index < 0 || !indices.add(index)) fail(method, "ViewField index must be nonnegative and unique");
            if (offset < -1) fail(method, "ViewField offset must be -1 or nonnegative");
            String fieldName = method.getSimpleName().toString();
            if (!names.add(fieldName)) fail(method, "Duplicate view getter");
            String fieldType = method.getReturnType().toString();
            Kind kind;
            Model nested = null;
            TypeKind primitive = method.getReturnType().getKind();
            if (primitive.isPrimitive()) {
                kind = Kind.valueOf(primitive.name());
            } else if (fieldType.equals(SLICE)) {
                kind = Kind.UTF8;
            } else if (method.getReturnType() instanceof DeclaredType declared
                    && declared.getTypeArguments().isEmpty() && declared.asElement() instanceof TypeElement target) {
                if (target.getKind() == ElementKind.ENUM) {
                    kind = Kind.ENUM;
                    int enumCount = 0;
                    for (Element constant : target.getEnclosedElements()) {
                        if (constant.getKind() == ElementKind.ENUM_CONSTANT) enumCount++;
                    }
                    if (enumCount == 0 || enumCount > 256) fail(method, "Enum must have 1..256 constants");
                } else if (annotation(target, MARKER) != null) {
                    kind = Kind.NESTED;
                    nested = model(target, visiting);
                } else {
                    fail(method, "Unsupported view field: use primitives, Utf8Slice, enum or source-visible @NativeView");
                    return null;
                }
            } else {
                fail(method, "Unsupported view field type");
                return null;
            }
            fields.add(new Field(method, fieldName, fieldType, kind, index, offset, nested));
        }
        if (fields.size() > 256) fail(type, "NativeView supports at most 256 fields per interface");
        int depth = 1;
        int retainedObjects = 1;
        for (Field field : fields) {
            if (field.kind == Kind.UTF8) retainedObjects++;
            if (field.nested != null) {
                depth = Math.max(depth, field.nested.depth + 1);
                retainedObjects += field.nested.retainedObjects;
            }
        }
        if (depth > 32 || retainedObjects > 1024) {
            fail(type, "NativeView preallocated graph exceeds depth 32 or 1024 retained view/slice objects");
        }
        fields.sort(Comparator.comparingInt(field -> field.index));
        long cursor = 0;
        for (Field field : fields) {
            int alignment = field.kind.alignment;
            long aligned = (cursor + alignment - 1) & -((long) alignment);
            long offset = field.offset == -1 ? aligned : field.offset;
            if (offset % alignment != 0 || offset > Integer.MAX_VALUE - field.kind.width) {
                fail(field.method, "Misaligned or overflowing ViewField offset");
            }
            for (Field previous : fields) {
                if (previous == field) break;
                if (offset < (long) previous.offset + previous.kind.width
                        && previous.offset < offset + field.kind.width) {
                    fail(field.method, "Overlapping ViewField offsets");
                }
            }
            field.offset = (int) offset;
            cursor = Math.max(cursor, offset + field.kind.width);
        }
        String pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String local = pkg.isEmpty() ? name : name.substring(pkg.length() + 1);
        Model result = new Model(type, name, pkg, local.replace('.', '_'), version, (int) cursor,
                depth, retainedObjects, fields);
        visiting.remove(name);
        models.put(name, result);
        return result;
    }

    private void validateParents(TypeElement type, Set<String> seen) {
        if (!seen.add(type.getQualifiedName().toString())) return;
        for (var parent : type.getInterfaces()) {
            DeclaredType declared = (DeclaredType) parent;
            TypeElement element = (TypeElement) declared.asElement();
            if (!declared.getTypeArguments().isEmpty() || !element.getTypeParameters().isEmpty()) {
                fail(type, "Generic view inheritance is not supported");
            }
            validateParents(element, seen);
        }
    }

    private void emit(Model model) throws IOException {
        if (!emitted.add(model.name)) return;
        for (Field field : model.fields) if (field.nested != null) emit(field.nested);
        write(model, "_NativeView", view(model));
        write(model, "_NativeWriter", writer(model));
    }

    private void write(Model model, String suffix, String body) throws IOException {
        var source = processingEnv.getFiler().createSourceFile(model.generated(suffix), model.element);
        try (Writer writer = source.openWriter()) {
            if (!model.pkg.isEmpty()) writer.write("package " + model.pkg + ";\n");
            writer.write("import java.nio.ByteBuffer;\nimport com.reactor.rust.view.*;\n");
            writer.write(body);
        }
    }

    private String view(Model model) {
        String cls = model.local + "_NativeView";
        StringBuilder out = new StringBuilder("/** Borrowed, thread-confined view. Close BEFORE native lease release; never retain across borrows. */\n");
        out.append("public final class ").append(cls).append(" extends AbstractNativeView implements ")
                .append(model.name).append(" {\n");
        constants(out, model);
        out.append(" private static final ThreadLocal<").append(cls).append("> LOCAL = ThreadLocal.withInitial(")
                .append(cls).append("::new);\n");
        for (int i = 0; i < model.fields.size(); i++) {
            Field f = model.fields.get(i);
            if (f.kind == Kind.UTF8) out.append(" private final Utf8Slice f").append(i).append(" = new Utf8Slice();\n");
            if (f.kind == Kind.NESTED) out.append(" private final ").append(f.nested.generated("_NativeView"))
                    .append(" f").append(i).append(" = new ").append(f.nested.generated("_NativeView")).append("();\n");
            if (f.kind == Kind.ENUM) out.append(" private static final ").append(f.type).append("[] E").append(i)
                    .append(" = ").append(f.type).append(".values();\n");
        }
        out.append(" /** One outstanding borrow per type/thread. Reentrant borrows fail, never overwrite. */\n")
                .append(" public static ").append(cls).append(" threadLocalView(ByteBuffer buffer, int offset) {\n")
                .append("  return threadLocalView(buffer, offset, ViewSupport.remaining(buffer, offset));\n }\n")
                .append(" public static ").append(cls).append(" threadLocalView(ByteBuffer buffer, int offset, int length) {\n")
                .append("  ").append(cls).append(" view = LOCAL.get(); view.wrap(buffer, offset, length); return view;\n }\n")
                .append(" @Override public void wrap(ByteBuffer source, int offset) { wrap(source, offset, ViewSupport.remaining(source, offset)); }\n")
                .append(" @Override public void wrap(ByteBuffer source, int offset, int length) {\n")
                .append("  beginWrap(source, offset, length, HEADER_SIZE);\n  try {\n");
        for (int i = 0; i < model.fields.size(); i++) {
            Field f = model.fields.get(i);
            if (f.kind.variable()) {
                out.append("   int r").append(i).append(" = (int) ViewSupport.read(buffer, base + ").append(f.offset).append(", 4);\n")
                        .append("   int n").append(i).append(" = (int) ViewSupport.read(buffer, base + ").append(f.offset + 4).append(", 4);\n")
                        .append("   size = Math.max(size, ViewSupport.descriptorEnd(r").append(i).append(", n").append(i)
                        .append(", HEADER_SIZE, length));\n   f").append(i)
                        .append(f.kind == Kind.UTF8 ? ".reset(buffer, base + r" : ".wrap(buffer, base + r")
                        .append(i).append(", n").append(i).append(");\n");
            } else if (f.kind == Kind.BOOLEAN || f.kind == Kind.ENUM) {
                out.append("   ").append(f.name).append("();\n");
            }
        }
        out.append("  } catch (RuntimeException | Error failure) { clear(); throw failure; }\n }\n")
                .append(" @Override public void clear() {\n  if (buffer != null) checkBound();\n");
        for (int i = 0; i < model.fields.size(); i++) {
            if (model.fields.get(i).kind.variable()) out.append("  f").append(i).append(".clear();\n");
        }
        out.append("  super.clear();\n }\n");
        for (int i = 0; i < model.fields.size(); i++) {
            Field f = model.fields.get(i);
            out.append(" @Override public ").append(f.type).append(' ').append(f.name).append("() {\n  checkBound();\n");
            String read = "ViewSupport.read(buffer, base + " + f.offset + ", " + f.kind.width + ")";
            if (f.kind.variable()) {
                out.append("  return f").append(i).append(";\n");
            } else if (f.kind == Kind.ENUM) {
                out.append("  int ordinal = (int) ").append(read).append(";\n  if (ordinal >= E").append(i)
                        .append(".length) throw new IllegalArgumentException(\"Invalid enum ordinal\");\n  return E").append(i).append("[ordinal];\n");
            } else if (f.kind == Kind.BOOLEAN) {
                out.append("  long value = ").append(read)
                        .append(";\n  if (value > 1) throw new IllegalArgumentException(\"Invalid boolean byte\");\n  return value == 1;\n");
            } else {
                String expr = switch (f.kind) {
                    case FLOAT -> "Float.intBitsToFloat((int) " + read + ")";
                    case DOUBLE -> "Double.longBitsToDouble(" + read + ")";
                    default -> "(" + f.type + ") " + read;
                };
                out.append("  return ").append(expr).append(";\n");
            }
            out.append(" }\n");
        }
        return out.append("}\n").toString();
    }

    private void constants(StringBuilder out, Model model) {
        out.append(" public static final int SCHEMA_VERSION = ").append(model.version).append(";\n")
                .append(" public static final int HEADER_SIZE = ").append(model.header).append(";\n");
        for (int i = 0; i < model.fields.size(); i++) {
            out.append(" public static final int FIELD_").append(i).append("_OFFSET = ").append(model.fields.get(i).offset).append(";\n");
        }
    }

    private String writer(Model model) {
        int argumentSlots = 0;
        for (Field field : model.fields) {
            argumentSlots += field.kind == Kind.LONG || field.kind == Kind.DOUBLE ? 2 : 1;
        }
        boolean fieldOverload = argumentSlots <= 253;
        StringBuilder out = new StringBuilder("/** Big-endian writer. Source storage must be stable and must not alias output (including duplicate/slice aliases). */\n");
        out.append("public final class ").append(model.local).append("_NativeWriter {\n");
        constants(out, model);
        out.append(" private ").append(model.local).append("_NativeWriter() {}\n")
                .append(" public static int byteSize(").append(model.name).append(" value) {\n")
                .append("  java.util.Objects.requireNonNull(value, \"value\");\n  int size = HEADER_SIZE;\n");
        for (Field f : model.fields) {
            if (f.kind.variable()) out.append("  size = ViewSupport.addSize(size, ")
                    .append(f.kind == Kind.UTF8 ? "value." + f.name + "().byteLength()" : f.nested.generated("_NativeWriter") + ".byteSize(value." + f.name + "())")
                    .append(");\n");
        }
        out.append("  return size;\n }\n")
                .append(" /** Absolute write; preserves output position, limit and byte order. No automatic retry. */\n")
                .append(" public static int write(").append(model.name).append(" value, ByteBuffer out, int offset) {\n")
                .append("  java.util.Objects.requireNonNull(value, \"value\");\n")
                .append("  if (value instanceof NativeFlyweightView borrowed && borrowed.backingBuffer() == out) throw new IllegalArgumentException(\"Aliased view write\");\n");
        if (fieldOverload) {
            out.append("  return write(out, offset");
            for (Field f : model.fields) out.append(", value.").append(f.name).append("()");
            out.append(");\n }\n public static int write(ByteBuffer out, int offset");
            for (int i = 0; i < model.fields.size(); i++) out.append(", ").append(model.fields.get(i).type).append(" v").append(i);
            out.append(") {\n");
        } else {
            // The JVM limits method descriptors to 255 parameter slots (long/double use two).
            for (int i = 0; i < model.fields.size(); i++) {
                Field f = model.fields.get(i);
                out.append("  ").append(f.type).append(" v").append(i).append(" = value.").append(f.name).append("();\n");
            }
        }
        out.append("  int size = HEADER_SIZE;\n");
        for (int i = 0; i < model.fields.size(); i++) {
            Field f = model.fields.get(i);
            if (f.kind == Kind.ENUM) out.append("  java.util.Objects.requireNonNull(v").append(i).append(", \"enum\");\n");
            if (f.kind.variable()) {
                if (f.kind == Kind.UTF8) {
                    out.append("  v").append(i).append(".requireDistinctStorage(out);\n");
                } else {
                    out.append("  if (v").append(i).append(" instanceof NativeFlyweightView borrowed").append(i)
                            .append(" && borrowed").append(i).append(".backingBuffer() == out) throw new IllegalArgumentException(\"Aliased nested view write\");\n");
                }
                out.append("  int n").append(i).append(" = ")
                        .append(f.kind == Kind.UTF8 ? "v" + i + ".byteLength()" : f.nested.generated("_NativeWriter") + ".byteSize(v" + i + ")")
                        .append(";\n  size = ViewSupport.addSize(size, n").append(i).append(");\n");
            }
        }
        out.append("  ViewSupport.checkWritable(out, offset, size);\n")
                .append("  for (int i = 0; i < HEADER_SIZE; i++) out.put(offset + i, (byte) 0);\n  int tail = HEADER_SIZE;\n");
        for (int i = 0; i < model.fields.size(); i++) {
            Field f = model.fields.get(i);
            if (f.kind.variable()) {
                out.append("  ViewSupport.write(out, offset + ").append(f.offset).append(", 4, tail);\n")
                        .append("  ViewSupport.write(out, offset + ").append(f.offset + 4).append(", 4, n").append(i).append(");\n");
                if (f.kind == Kind.UTF8) out.append("  v").append(i).append(".writeTo(out, offset + tail);\n");
                else out.append("  ").append(f.nested.generated("_NativeWriter")).append(".write(v").append(i).append(", out, offset + tail);\n");
                out.append("  tail += n").append(i).append(";\n");
            } else {
                String expr = switch (f.kind) {
                    case BOOLEAN -> "v" + i + " ? 1 : 0";
                    case ENUM -> "v" + i + ".ordinal()";
                    case FLOAT -> "Float.floatToRawIntBits(v" + i + ")";
                    case DOUBLE -> "Double.doubleToRawLongBits(v" + i + ")";
                    default -> "v" + i;
                };
                out.append("  ViewSupport.write(out, offset + ").append(f.offset).append(", ").append(f.kind.width).append(", ").append(expr).append(");\n");
            }
        }
        out.append("  return size;\n }\n")
                .append(" /** Appends JSON. Overflow/non-finite numbers fail explicitly and may leave partial output. Float/double formatting may allocate. */\n")
                .append(" public static int writeJson(").append(model.name).append(" value, ByteBuffer out) {\n")
                .append("  java.util.Objects.requireNonNull(value, \"value\");\n")
                .append("  if (value instanceof NativeFlyweightView borrowed && borrowed.backingBuffer() == out) throw new IllegalArgumentException(\"Aliased view JSON output\");\n")
                .append("  int start = out.position();\n  out.put((byte) '{');\n");
        for (int i = 0; i < model.fields.size(); i++) {
            Field f = model.fields.get(i);
            if (i > 0) out.append("  out.put((byte) ',');\n");
            out.append("  ViewSupport.jsonString(out, ").append(literal(f.name)).append(");\n  out.put((byte) ':');\n")
                    .append("  ").append(f.type).append(" j").append(i).append(" = value.").append(f.name).append("();\n");
            String value = "j" + i;
            String code = switch (f.kind) {
                case UTF8 -> value + ".writeJson(out)";
                case NESTED -> f.nested.generated("_NativeWriter") + ".writeJson(" + value + ", out)";
                case ENUM -> "ViewSupport.jsonString(out, " + value + ".name())";
                case BOOLEAN -> "ViewSupport.ascii(out, " + value + " ? \"true\" : \"false\")";
                case FLOAT -> "ViewSupport.jsonFloat(out, " + value + ")";
                case DOUBLE -> "ViewSupport.jsonDouble(out, " + value + ")";
                case CHAR -> "ViewSupport.jsonEscape(out, " + value + ")";
                default -> "ViewSupport.jsonLong(out, " + value + ")";
            };
            if (f.kind == Kind.CHAR) out.append("  out.put((byte) '\"');\n");
            out.append("  ").append(code).append(";\n");
            if (f.kind == Kind.CHAR) out.append("  out.put((byte) '\"');\n");
        }
        return out.append("  out.put((byte) '}');\n  return out.position() - start;\n }\n}\n").toString();
    }

    private static String literal(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\' || ch == '"') out.append('\\').append(ch);
            else if (ch < 32 || ch > 126) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }

    private AnnotationMirror annotation(Element element, String name) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (annotation.getAnnotationType().toString().equals(name)) return annotation;
        }
        return null;
    }

    private int integer(Element element, String name, String property) {
        var annotation = annotation(element, name);
        if (annotation == null) { fail(element, "Missing " + name); return 0; }
        for (var entry : processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(property)) return (Integer) entry.getValue().getValue();
        }
        throw new IllegalStateException("Missing annotation property " + property);
    }

    private void fail(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
        throw new InvalidSchema();
    }

    private static final class InvalidSchema extends RuntimeException {}

    private enum Kind {
        BOOLEAN(1, 1), BYTE(1, 1), SHORT(2, 2), CHAR(2, 2), INT(4, 4), LONG(8, 8),
        FLOAT(4, 4), DOUBLE(8, 8), ENUM(1, 1), UTF8(8, 4), NESTED(8, 4);
        final int width;
        final int alignment;
        Kind(int width, int alignment) { this.width = width; this.alignment = alignment; }
        boolean variable() { return this == UTF8 || this == NESTED; }
    }

    private static final class Field {
        final ExecutableElement method;
        final String name;
        final String type;
        final Kind kind;
        final int index;
        int offset;
        final Model nested;
        Field(ExecutableElement method, String name, String type, Kind kind, int index,
              int offset, Model nested) {
            this.method = method; this.name = name; this.type = type; this.kind = kind;
            this.index = index; this.offset = offset; this.nested = nested;
        }
    }

    private record Model(TypeElement element, String name, String pkg, String local,
                         int version, int header, int depth, int retainedObjects, List<Field> fields) {
        String generated(String suffix) { return (pkg.isEmpty() ? "" : pkg + ".") + local + suffix; }
    }
}
