package com.reactor.rust.codegen;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.reactor.rust.codegen.ReactorStartupProcessor.*;

/** Collects property bindings and emits configuration metadata. */
final class PropertyMetadataEmitter {

    private final ReactorStartupProcessor processor;

    PropertyMetadataEmitter(ReactorStartupProcessor processor) {
        this.processor = processor;
    }

    final Map<String, ConfigurationRecordModel> configurationRecords = new TreeMap<>();
    final Set<String> properties = new TreeSet<>();

    void collectProperty(TypeElement owner, Element field) {
        AnnotationMirror property = processor.annotation(field, RUST_PROPERTY);
        if (property == null) {
            return;
        }
        String key = processor.annotationString(property, "value", "");
        String defaultValue = processor.annotationString(property, "defaultValue", "");
        properties.add(key + "\t" + field.asType() + "\t" + defaultValue + "\t"
                + owner.getQualifiedName() + "#" + field.getSimpleName());
    }

    void writeConfigurationMetadata() {
        StringBuilder json = new StringBuilder(256).append("{\"groups\":[");
        boolean firstGroup = true;
        for (Map.Entry<String, ConfigurationRecordModel> entry : configurationRecords.entrySet()) {
            if (!firstGroup) json.append(',');
            firstGroup = false;
            json.append("{\"name\":\"").append(processor.jsonEscape(entry.getValue().prefix()))
                    .append("\",\"type\":\"").append(processor.jsonEscape(entry.getKey())).append("\"}");
        }
        json.append("],\"properties\":[");
        boolean firstProperty = true;
        for (Map.Entry<String, ConfigurationRecordModel> entry : configurationRecords.entrySet()) {
            for (ConfigurationPropertyModel property : entry.getValue().properties()) {
                if (!firstProperty) json.append(',');
                firstProperty = false;
                json.append("{\"name\":\"").append(processor.jsonEscape(property.key()))
                        .append("\",\"type\":\"").append(processor.jsonEscape(property.type()))
                        .append("\",\"sourceType\":\"").append(processor.jsonEscape(entry.getKey())).append('"');
                if (property.defaultValue() != null) {
                    json.append(",\"defaultValue\":\"")
                            .append(processor.jsonEscape(property.defaultValue())).append('"');
                }
                json.append('}');
            }
        }
        json.append("]}");
        processor.writeResource("META-INF/reactor/configuration-metadata.json", List.of(json.toString()));
    }

    ConfigurationRecordModel configurationRecordModel(
            TypeElement type,
            AnnotationMirror annotation) {
        if (type.getKind() != ElementKind.RECORD) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@ConfigurationProperties requires an immutable record",
                    type);
        }
        String prefix = processor.annotationString(annotation, "value", "").trim();
        while (prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        List<ConfigurationPropertyModel> values = new ArrayList<>();
        for (RecordComponentElement component : type.getRecordComponents()) {
            String localName = processor.annotationString(component, CONFIG_NAME, "value", "");
            if (localName.isBlank()) {
                localName = processor.kebabCase(component.getSimpleName().toString());
            }
            String key = prefix.isBlank() ? localName : prefix + "." + localName;
            AnnotationMirror defaultAnnotation = processor.annotation(component, CONFIG_DEFAULT);
            String defaultValue = defaultAnnotation == null
                    ? null
                    : processor.annotationString(defaultAnnotation, "value", "");
            String expression = configurationExpression(component, key, defaultValue);
            values.add(new ConfigurationPropertyModel(
                    key,
                    component.asType().toString(),
                    defaultValue,
                    expression));
            properties.add(key + "\t" + component.asType() + "\t"
                    + (defaultValue == null ? "" : defaultValue) + "\t"
                    + type.getQualifiedName() + "#" + component.getSimpleName());
        }
        return new ConfigurationRecordModel(prefix, List.copyOf(values));
    }

    String configurationExpression(
            RecordComponentElement component,
            String key,
            String defaultValue) {
        TypeMirror type = component.asType();
        String erased = processor.environment().getTypeUtils().erasure(type).toString();
        String keyLiteral = processor.javaString(key);
        String defaultLiteral = processor.javaString(defaultValue);
        return switch (type.getKind()) {
            case INT -> "com.reactor.rust.config.ConfigurationBinder.integer(" + keyLiteral + ", "
                    + defaultLiteral + ")";
            case LONG -> "com.reactor.rust.config.ConfigurationBinder.longValue(" + keyLiteral + ", "
                    + defaultLiteral + ")";
            case SHORT -> "com.reactor.rust.config.ConfigurationBinder.shortValue(" + keyLiteral + ", "
                    + defaultLiteral + ")";
            case DOUBLE -> "com.reactor.rust.config.ConfigurationBinder.doubleValue(" + keyLiteral + ", "
                    + defaultLiteral + ")";
            case BOOLEAN -> "com.reactor.rust.config.ConfigurationBinder.booleanValue(" + keyLiteral + ", "
                    + defaultLiteral + ")";
            case DECLARED -> declaredConfigurationExpression(component, erased, keyLiteral, defaultLiteral,
                    defaultValue);
            default -> unsupportedConfigurationType(component);
        };
    }

    String declaredConfigurationExpression(
            RecordComponentElement component,
            String erased,
            String keyLiteral,
            String defaultLiteral,
            String defaultValue) {
        if (erased.equals(String.class.getName())) {
            return "com.reactor.rust.config.ConfigurationBinder.string(" + keyLiteral + ", "
                    + defaultLiteral + ", " + (defaultValue == null) + ")";
        }
        if (erased.equals(Integer.class.getName())) {
            return "java.lang.Integer.valueOf(com.reactor.rust.config.ConfigurationBinder.integer("
                    + keyLiteral + ", " + defaultLiteral + "))";
        }
        if (erased.equals(Long.class.getName())) {
            return "java.lang.Long.valueOf(com.reactor.rust.config.ConfigurationBinder.longValue("
                    + keyLiteral + ", " + defaultLiteral + "))";
        }
        if (erased.equals(Short.class.getName())) {
            return "java.lang.Short.valueOf(com.reactor.rust.config.ConfigurationBinder.shortValue("
                    + keyLiteral + ", " + defaultLiteral + "))";
        }
        if (erased.equals(Double.class.getName())) {
            return "java.lang.Double.valueOf(com.reactor.rust.config.ConfigurationBinder.doubleValue("
                    + keyLiteral + ", " + defaultLiteral + "))";
        }
        if (erased.equals(Boolean.class.getName())) {
            return "java.lang.Boolean.valueOf(com.reactor.rust.config.ConfigurationBinder.booleanValue("
                    + keyLiteral + ", " + defaultLiteral + "))";
        }
        if (erased.equals("java.time.Duration")) {
            return "com.reactor.rust.config.ConfigurationBinder.duration(" + keyLiteral + ", "
                    + defaultLiteral + ")";
        }
        if (erased.equals("java.util.Optional")
                && component.asType().toString().equals("java.util.Optional<java.lang.String>")) {
            return "com.reactor.rust.config.ConfigurationBinder.optionalString(" + keyLiteral + ")";
        }
        if (erased.equals("java.util.List")
                && component.asType().toString().equals("java.util.List<java.lang.String>")) {
            return "com.reactor.rust.config.ConfigurationBinder.stringList(" + keyLiteral + ", "
                    + defaultLiteral + ")";
        }
        Element declared = processor.environment().getTypeUtils().asElement(component.asType());
        if (declared != null && declared.getKind() == ElementKind.ENUM) {
            return "com.reactor.rust.config.ConfigurationBinder.enumValue(" + keyLiteral + ", "
                    + defaultLiteral + ", " + erased + ".class)";
        }
        return unsupportedConfigurationType(component);
    }

    String unsupportedConfigurationType(RecordComponentElement component) {
        processor.environment().getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                "Unsupported @ConfigurationProperties component type: " + component.asType()
                        + "; use String, primitive/boxed scalar, Duration, enum, Optional<String> or List<String>",
                component);
        return "null";
    }

    String configurationArguments(ConfigurationRecordModel configuration) {
        return configuration.properties().stream()
                .map(ConfigurationPropertyModel::expression)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    record ConfigurationRecordModel(
            String prefix,
            List<ConfigurationPropertyModel> properties) {}

    record ConfigurationPropertyModel(
            String key,
            String type,
            String defaultValue,
            String expression) {}

}
