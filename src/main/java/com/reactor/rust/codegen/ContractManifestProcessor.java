package com.reactor.rust.codegen;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.*;

/** Build-only conservative contract inventory. No runtime schema scanning. */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class ContractManifestProcessor extends AbstractProcessor {
    private static final String NATIVE_VIEW = "com.reactor.rust.view.NativeView";
    private static final String VIEW_FIELD = "com.reactor.rust.view.ViewField";
    private final SortedMap<String, String> contracts = new TreeMap<>();
    private boolean written;

    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        Set<Element> candidates = new LinkedHashSet<>(round.getRootElements());
        TypeElement viewMarker = processingEnv.getElementUtils().getTypeElement(NATIVE_VIEW);
        if (viewMarker != null) {
            for (Element view : round.getElementsAnnotatedWith(viewMarker)) {
                if (view.getKind() == ElementKind.INTERFACE) candidates.add(view);
            }
            // A preceding processor may claim the marker before this processor's first round.
            // Generated view classes still expose their source-visible schema interfaces.
            for (Element root : round.getRootElements()) {
                if (!(root instanceof TypeElement type) || !type.getKind().isClass()) continue;
                for (var parent : type.getInterfaces()) {
                    if (parent instanceof javax.lang.model.type.DeclaredType declared
                            && declared.asElement() instanceof TypeElement view
                            && annotation(view, NATIVE_VIEW) != null) candidates.add(view);
                }
            }
        }
        for (Element root : candidates) {
            if (!(root instanceof TypeElement type) || !type.getModifiers().contains(Modifier.PUBLIC)) continue;
            if (type.getKind() != ElementKind.RECORD && type.getKind() != ElementKind.INTERFACE
                    && type.getKind() != ElementKind.ENUM) continue;
            if (type.getAnnotationMirrors().stream().anyMatch(a -> a.getAnnotationType().toString().endsWith(".Generated"))) continue;
            StringBuilder signature = new StringBuilder(type.getKind().name());
            if (type.getKind() == ElementKind.RECORD) {
                for (RecordComponentElement component : type.getRecordComponents())
                    signature.append('|').append(component.getSimpleName()).append(':').append(component.asType())
                            .append(':').append(component.getAnnotationMirrors());
            } else if (type.getKind() == ElementKind.ENUM) {
                for (Element item : type.getEnclosedElements())
                    if (item.getKind() == ElementKind.ENUM_CONSTANT) signature.append('|').append(item.getSimpleName());
            } else {
                var methods = new TreeSet<String>();
                for (Element item : processingEnv.getElementUtils().getAllMembers(type)) {
                    if (item instanceof ExecutableElement method && item.getKind() == ElementKind.METHOD
                            && method.getModifiers().contains(Modifier.ABSTRACT)) {
                        methods.add(method.getSimpleName() + ":" + processingEnv.getTypeUtils().asMemberOf(
                                (javax.lang.model.type.DeclaredType) type.asType(), method));
                    }
                }
                methods.forEach(method -> signature.append('|').append(method));
                appendNativeView(signature, type);
            }
            contracts.put(type.getQualifiedName().toString(), signature.toString());
        }
        if (round.processingOver() && !written && !contracts.isEmpty()) {
            written = true;
            try (var writer = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/reactor/contracts.tsv").openWriter()) {
                writer.write("# reactor-contract-v1; conservative structural compatibility, not semantic proof\n");
                for (var entry : contracts.entrySet()) writer.write(entry.getKey() + "\t" + entry.getValue() + "\n");
            } catch (IOException error) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Cannot write contract manifest: " + error.getMessage());
            }
        }
        return false;
    }

    private void appendNativeView(StringBuilder signature, TypeElement type) {
        AnnotationMirror view = annotation(type, NATIVE_VIEW);
        if (view == null) return;
        signature.append('|').append(canonicalAnnotation(view, annotationValues(view)));
        var fields = new ArrayList<ViewFieldContract>();
        for (Element item : processingEnv.getElementUtils().getAllMembers(type)) {
            if (!(item instanceof ExecutableElement method) || item.getKind() != ElementKind.METHOD
                    || !method.getModifiers().contains(Modifier.ABSTRACT)) continue;
            AnnotationMirror field = annotation(method, VIEW_FIELD);
            if (field == null) continue;
            var values = annotationValues(field);
            AnnotationValue index = values.get("index");
            // Invalid schemas are diagnosed by NativeViewProcessor, not by this inventory.
            if (index == null || !(index.getValue() instanceof Integer fieldIndex)) continue;
            fields.add(new ViewFieldContract(fieldIndex,
                    method.getSimpleName() + ":" + processingEnv.getTypeUtils().asMemberOf(
                            (javax.lang.model.type.DeclaredType) type.asType(), method)
                            + canonicalAnnotation(field, values) + enumLayout(method)));
        }
        fields.sort(Comparator.comparingInt(ViewFieldContract::index).thenComparing(ViewFieldContract::signature));
        fields.forEach(field -> signature.append('|').append(field.signature()));
    }

    private AnnotationMirror annotation(Element element, String name) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (annotation.getAnnotationType().toString().equals(name)) return annotation;
        }
        return null;
    }

    private String enumLayout(ExecutableElement method) {
        if (!(method.getReturnType() instanceof javax.lang.model.type.DeclaredType declared)
                || !(declared.asElement() instanceof TypeElement type)
                || type.getKind() != ElementKind.ENUM) return "";
        StringJoiner constants = new StringJoiner(",", ";enumConstants=[", "]");
        for (Element element : type.getEnclosedElements()) {
            if (element.getKind() == ElementKind.ENUM_CONSTANT) constants.add(element.getSimpleName().toString());
        }
        return constants.toString();
    }

    private SortedMap<String, AnnotationValue> annotationValues(AnnotationMirror annotation) {
        var values = new TreeMap<String, AnnotationValue>();
        processingEnv.getElementUtils().getElementValuesWithDefaults(annotation)
                .forEach((key, value) -> values.put(key.getSimpleName().toString(), value));
        return values;
    }

    private String canonicalAnnotation(AnnotationMirror annotation, SortedMap<String, AnnotationValue> values) {
        var text = new StringJoiner(",", "@" + annotation.getAnnotationType() + "(", ")");
        values.forEach((key, value) -> text.add(key + "=" + value.getValue()));
        return text.toString();
    }

    private record ViewFieldContract(int index, String signature) {}
}
