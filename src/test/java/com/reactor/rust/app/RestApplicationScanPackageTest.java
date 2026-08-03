package com.reactor.rust.app;

import com.reactor.rust.annotations.ReactorApplication;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestApplicationScanPackageTest {

    @Test
    void explicitScanPackagesReplaceTheApplicationPackage() throws Exception {
        RestApplication.Builder builder = configuredBuilder(ExplicitPackagesApplication.class);

        assertEquals(List.of("com.example.handlers", "com.example.services"), basePackages(builder));
    }

    @Test
    void applicationPackageIsTheDefaultWhenNoScanPackageIsConfigured() throws Exception {
        RestApplication.Builder builder = configuredBuilder(DefaultPackageApplication.class);

        assertEquals(List.of(getClass().getPackageName()), basePackages(builder));
    }

    private static RestApplication.Builder configuredBuilder(Class<?> applicationType) throws Exception {
        Method configure = RestApplication.class.getDeclaredMethod(
                "configureApplication", RestApplication.Builder.class, Class.class);
        configure.setAccessible(true);
        return (RestApplication.Builder) configure.invoke(null, RestApplication.builder(), applicationType);
    }

    @SuppressWarnings("unchecked")
    private static List<String> basePackages(RestApplication.Builder builder) throws Exception {
        Field field = RestApplication.Builder.class.getDeclaredField("basePackages");
        field.setAccessible(true);
        return List.copyOf((List<String>) field.get(builder));
    }

    @ReactorApplication(scanBasePackages = {"com.example.handlers", "com.example.services"})
    private static final class ExplicitPackagesApplication {
    }

    @ReactorApplication
    private static final class DefaultPackageApplication {
    }
}
