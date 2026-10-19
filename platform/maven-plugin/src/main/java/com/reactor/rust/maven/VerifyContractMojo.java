package com.reactor.rust.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Optional strict compatibility gate against an unpacked previous artifact manifest. */
@Mojo(name = "verify-contract", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class VerifyContractMojo extends AbstractMojo {
    @Parameter(property = "reactor.contract.baseline") private File baseline;
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true) private File outputDirectory;

    @Override public void execute() throws MojoExecutionException {
        if (baseline == null) return;
        try {
            var previous = read(Files.readAllLines(baseline.toPath()));
            var current = read(Files.readAllLines(outputDirectory.toPath().resolve("META-INF/reactor/contracts.tsv")));
            if (previous.isEmpty()) throw new IllegalArgumentException("Empty baseline manifest");
            List<String> changes = incompatible(previous, current);
            if (!changes.isEmpty()) throw new MojoExecutionException("Contract changed: " + changes
                    + ". Review JSON/Hessian/cache compatibility separately and run old/new consumer tests. Do not overwrite the baseline to bypass this gate.");
            getLog().info("Existing contract signatures preserved; semantic round-trip tests are still required.");
        } catch (MojoExecutionException error) { throw error; }
        catch (Exception error) { throw new MojoExecutionException("Cannot verify contract manifest", error); }
    }

    static Map<String, String> read(List<String> lines) {
        Map<String, String> result = new TreeMap<>();
        for (String line : lines) {
            if (line.startsWith("#") || line.isBlank()) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0 || result.put(line.substring(0, tab), line.substring(tab + 1)) != null)
                throw new IllegalArgumentException("Invalid or duplicate contract entry");
        }
        return result;
    }
    static List<String> incompatible(Map<String, String> previous, Map<String, String> current) {
        return previous.entrySet().stream().filter(e -> !e.getValue().equals(current.get(e.getKey())))
                .map(Map.Entry::getKey).toList();
    }
}
