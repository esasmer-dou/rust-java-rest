package com.reactor.rust.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeImageMojoTest {
    @Test
    void tokenizesJvmArgumentsWithoutCollapsingExecForm() throws Exception {
        assertEquals(
                List.of("-Xss256k", "-Dmessage=hello world", "-Dpath=C:\\runtime"),
                RuntimeImageMojo.tokenize("-Xss256k \"-Dmessage=hello world\" -Dpath=C:\\\\runtime"));
    }

    @Test
    void rejectsUnclosedQuote() {
        assertThrows(MojoExecutionException.class, () -> RuntimeImageMojo.tokenize("-Dvalue=\"broken"));
    }

    @Test
    void emitsValidJsonArray() {
        assertEquals(
                "[\"java\", \"-Dvalue=hello world\", \"app.Main\"]",
                RuntimeImageMojo.jsonArray(List.of("java", "-Dvalue=hello world", "app.Main")));
    }
}
