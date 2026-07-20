package re.imc.geysermodelengineextension.managers.resourcepack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.AnimationTimelineSanitizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * One-time P0 migration gate. While the currently published pack still
 * contains ModelEngine {@code mm:} commands, the candidate may differ only by
 * removing those commands (manifest is versioned separately).
 */
final class ResourcePackMigrationGuard {

    private ResourcePackMigrationGuard() {
    }

    static MigrationCheck verify(Path currentPack, Path candidatePack) throws IOException {
        if (!Files.isDirectory(currentPack)) {
            return new MigrationCheck(false, 0);
        }

        List<Path> currentFiles = ResourcePackFileOps.relativeFiles(currentPack, true);
        List<Path> candidateFiles = ResourcePackFileOps.relativeFiles(candidatePack, true);
        if (!containsMythicCommand(currentPack, currentFiles)) {
            return new MigrationCheck(false, 0);
        }
        if (!currentFiles.equals(candidateFiles)) {
            throw new IllegalStateException("P0 migration changed the generated pack file set");
        }

        int removedCommands = 0;
        for (Path relative : currentFiles) {
            Path current = currentPack.resolve(relative);
            Path candidate = candidatePack.resolve(relative);
            if (relative.toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                JsonElement currentJson = parse(current);
                JsonElement candidateJson = parse(candidate);
                if (currentJson.isJsonObject()) {
                    removedCommands += AnimationTimelineSanitizer.sanitize(currentJson.getAsJsonObject());
                }
                if (!currentJson.equals(candidateJson)) {
                    String difference = firstDifference(currentJson, candidateJson, "$");
                    throw new IllegalStateException("P0 migration changed resource-pack semantics outside mm: timeline at "
                            + relative.toString().replace('\\', '/') + " (first difference: " + difference + ")");
                }
            } else if (Files.mismatch(current, candidate) != -1) {
                throw new IllegalStateException("P0 migration changed binary resource "
                        + relative.toString().replace('\\', '/'));
            }
        }

        if (removedCommands == 0) {
            throw new IllegalStateException("Current pack contains mm: text but no standalone timeline command was removed");
        }
        return new MigrationCheck(true, removedCommands);
    }

    private static boolean containsMythicCommand(Path root, List<Path> files) throws IOException {
        for (Path relative : files) {
            if (!relative.toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                continue;
            }
            if (Files.readString(root.resolve(relative), StandardCharsets.UTF_8).contains("mm:")) {
                return true;
            }
        }
        return false;
    }

    private static JsonElement parse(Path file) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw new IOException("Invalid JSON in migration comparison: " + file, error);
        }
    }

    private static String firstDifference(JsonElement current, JsonElement candidate, String path) {
        if (current == null || candidate == null) {
            return path + " (one side is missing)";
        }
        if (current.getClass() != candidate.getClass()) {
            return path + " (type " + current.getClass().getSimpleName() + " -> "
                    + candidate.getClass().getSimpleName() + ")";
        }
        if (current.isJsonObject()) {
            JsonObject currentObject = current.getAsJsonObject();
            JsonObject candidateObject = candidate.getAsJsonObject();
            for (String key : currentObject.keySet()) {
                if (!candidateObject.has(key)) {
                    return path + "." + key + " (removed)";
                }
                String nested = firstDifference(currentObject.get(key), candidateObject.get(key), path + "." + key);
                if (nested != null) {
                    return nested;
                }
            }
            for (String key : candidateObject.keySet()) {
                if (!currentObject.has(key)) {
                    return path + "." + key + " (added)";
                }
            }
            return null;
        }
        if (current.isJsonArray()) {
            if (current.getAsJsonArray().size() != candidate.getAsJsonArray().size()) {
                return path + " (array size " + current.getAsJsonArray().size() + " -> "
                        + candidate.getAsJsonArray().size() + ")";
            }
            for (int index = 0; index < current.getAsJsonArray().size(); index++) {
                String nested = firstDifference(current.getAsJsonArray().get(index),
                        candidate.getAsJsonArray().get(index), path + "[" + index + "]");
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        return current.equals(candidate) ? null : path + " (" + current + " -> " + candidate + ")";
    }

    record MigrationCheck(boolean applied, int removedCommands) {
    }
}
