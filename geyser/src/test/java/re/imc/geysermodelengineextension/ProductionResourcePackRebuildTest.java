package re.imc.geysermodelengineextension;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import re.imc.geysermodelengineextension.managers.resourcepack.ResourcePackValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in full generator audit against a copied runtime data directory. */
class ProductionResourcePackRebuildTest {

    @TempDir
    Path temp;

    @Test
    void rebuildsCopiedRuntimePackThroughAllProductionGates() throws Exception {
        String runtimePath = System.getenv("GME_RUNTIME_DATA");
        Assumptions.assumeTrue(runtimePath != null && !runtimePath.isBlank(),
                "Set GME_RUNTIME_DATA to run the production-pack rebuild audit");

        Path runtime = Path.of(runtimePath);
        Path data = temp.resolve("geysermodelengineextension");
        Files.createDirectories(data);
        Files.createSymbolicLink(data.resolve("input"), runtime.resolve("input"));
        copyTree(runtime.resolve("ResourcePack/generated_pack"), data.resolve("ResourcePack/generated_pack"));
        copyTree(runtime.resolve("ResourcePack/Templates"), data.resolve("ResourcePack/Templates"));
        copyFile(runtime.resolve("ResourcePack/generated_pack.zip"), data.resolve("ResourcePack/generated_pack.zip"));
        copyFile(runtime.resolve("config.yml"), data.resolve("config.yml"));
        copyFile(runtime.resolve("Lang/messages.yml"), data.resolve("Lang/messages.yml"));

        JsonObject oldManifest = readManifest(data.resolve("ResourcePack/generated_pack/manifest.json"));
        int oldPatch = oldManifest.getAsJsonObject("header").getAsJsonArray("version").get(2).getAsInt();

        CapturingLogger logger = new CapturingLogger();
        TestExtension extension = new TestExtension(data, logger);
        extension.onLoad(null);

        Path generated = data.resolve("ResourcePack/generated_pack");
        Path generatedZip = data.resolve("ResourcePack/generated_pack.zip");
        ResourcePackValidator.ValidationReport report = ResourcePackValidator.validate(generated);
        report.throwIfInvalid();
        ResourcePackValidator.validateArchiveMatches(generated, generatedZip);

        assertEquals(0, report.remainingMythicCommands());
        assertEquals(extension.getResourcePackManager().getEntityCache().size(), report.entities());
        JsonObject newManifest = readManifest(generated.resolve("manifest.json"));
        assertEquals(oldPatch + 1,
                newManifest.getAsJsonObject("header").getAsJsonArray("version").get(2).getAsInt());
        assertEquals(oldManifest.getAsJsonObject("header").get("uuid"),
                newManifest.getAsJsonObject("header").get("uuid"));
        assertTrue(logger.info.stream().anyMatch(message -> message.contains("P0 semantic migration guard passed")));

        byte[] firstZip = Files.readAllBytes(generatedZip);
        extension.getResourcePackManager().loadPack();
        assertArrayEquals(firstZip, Files.readAllBytes(generatedZip));
        assertEquals(newManifest,
                readManifest(generated.resolve("manifest.json")));

        String outputPath = System.getenv("GME_CANDIDATE_OUTPUT");
        if (outputPath != null && !outputPath.isBlank()) {
            Path output = Path.of(outputPath);
            Files.createDirectories(output);
            try (Stream<Path> children = Files.list(output)) {
                if (children.findAny().isPresent()) {
                    throw new IllegalStateException("GME_CANDIDATE_OUTPUT must be empty: " + output);
                }
            }
            copyTree(generated, output.resolve("generated_pack"));
            copyFile(generatedZip, output.resolve("generated_pack.zip"));
        }
    }

    private static JsonObject readManifest(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void copyFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    copyFile(path, destination);
                }
            }
        }
    }

    private static final class TestExtension extends GeyserModelEngineExtension {
        private final Path dataFolder;
        private final ExtensionLogger logger;

        private TestExtension(Path dataFolder, ExtensionLogger logger) {
            this.dataFolder = dataFolder;
            this.logger = logger;
        }

        @Override
        public Path dataFolder() {
            return dataFolder;
        }

        @Override
        public ExtensionLogger logger() {
            return logger;
        }
    }

    private static final class CapturingLogger implements ExtensionLogger {
        private final List<String> info = new ArrayList<>();

        @Override
        public String prefix() {
            return "test";
        }

        @Override
        public void severe(String message) {
            throw new AssertionError(message);
        }

        @Override
        public void severe(String message, Throwable error) {
            throw new AssertionError(message, error);
        }

        @Override
        public void error(String message) {
            throw new AssertionError(message);
        }

        @Override
        public void error(String message, Throwable error) {
            throw new AssertionError(message, error);
        }

        @Override
        public void warning(String message) {
            // Expected for retained but currently unreferenced P1 assets.
        }

        @Override
        public void info(String message) {
            info.add(message);
        }

        @Override
        public void debug(String message) {
        }

        @Override
        public boolean isDebug() {
            return false;
        }
    }
}
