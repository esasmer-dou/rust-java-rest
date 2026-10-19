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
    private final SortedMap<String, String> contracts = new TreeMap<>();
    private boolean written;

    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element root : round.getRootElements()) {
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
}
