package re.imc.geysermodelengineextension.managers.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Maintains a stable Bedrock pack identity while versioning content changes. */
public final class PackManifestManager {

    private PackManifestManager() {
    }

    public static ManifestBuild prepare(
            JsonObject template,
            Path currentPackDirectory,
            Path currentPackZip,
            boolean contentChanged
    ) throws IOException {
        JsonObject existing = readExistingManifest(currentPackDirectory, currentPackZip);
        if (existing == null) {
            JsonObject manifest = createNewManifest(template);
            List<Integer> version = validateAndReadVersion(manifest);
            return new ManifestBuild(manifest, version, false, false);
        }

        JsonObject manifest = existing.deepCopy();
        List<Integer> version = validateAndReadVersion(manifest);
        if (contentChanged) {
            version = increment(version);
            setSynchronizedVersion(manifest, version);
        }
        return new ManifestBuild(manifest, version, true, contentChanged);
    }

    private static JsonObject readExistingManifest(Path currentPackDirectory, Path currentPackZip) throws IOException {
        Path manifestPath = currentPackDirectory.resolve("manifest.json");
        if (Files.isRegularFile(manifestPath)) {
            return parseManifest(Files.readString(manifestPath, StandardCharsets.UTF_8), manifestPath.toString());
        }

        if (!Files.isRegularFile(currentPackZip)) {
            return null;
        }

        try (ZipFile zip = new ZipFile(currentPackZip.toFile())) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                try {
                    JsonElement element = JsonParser.parseReader(reader);
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException("manifest root must be an object");
                    }
                    return element.getAsJsonObject();
                } catch (RuntimeException error) {
                    throw new IOException("Invalid existing resource-pack manifest at "
                            + currentPackZip + "!/manifest.json", error);
                }
            }
        }
    }

    private static JsonObject parseManifest(String json, String source) throws IOException {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("manifest root must be an object");
            }
            return element.getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Invalid existing resource-pack manifest at " + source, error);
        }
    }

    private static JsonObject createNewManifest(JsonObject template) {
        if (template == null) {
            throw new IllegalArgumentException("Missing packmanifest template");
        }

        JsonObject manifest = template.deepCopy();
        JsonObject header = requiredObject(manifest, "header");
        header.addProperty("uuid", UUID.randomUUID().toString());

        JsonArray modules = requiredArray(manifest, "modules");
        if (modules.isEmpty()) {
            throw new IllegalArgumentException("manifest modules must not be empty");
        }
        for (JsonElement moduleElement : modules) {
            if (!moduleElement.isJsonObject()) {
                throw new IllegalArgumentException("manifest module must be an object");
            }
            moduleElement.getAsJsonObject().addProperty("uuid", UUID.randomUUID().toString());
        }

        List<Integer> version = readVersion(requiredArray(header, "version"), "header.version");
        setSynchronizedVersion(manifest, version);
        validateAndReadVersion(manifest);
        return manifest;
    }

    /** Validates UUIDs and requires every module version to equal header.version. */
    public static List<Integer> validateAndReadVersion(JsonObject manifest) {
        JsonObject header = requiredObject(manifest, "header");
        String headerUuid = requiredString(header, "uuid");
        validateUuid(headerUuid, "header.uuid");
        List<Integer> headerVersion = readVersion(requiredArray(header, "version"), "header.version");

        JsonArray modules = requiredArray(manifest, "modules");
        if (modules.isEmpty()) {
            throw new IllegalArgumentException("manifest modules must not be empty");
        }
        for (int index = 0; index < modules.size(); index++) {
            JsonElement moduleElement = modules.get(index);
            if (!moduleElement.isJsonObject()) {
                throw new IllegalArgumentException("manifest modules[" + index + "] must be an object");
            }
            JsonObject module = moduleElement.getAsJsonObject();
            String moduleUuid = requiredString(module, "uuid");
            validateUuid(moduleUuid, "modules[" + index + "].uuid");
            if (headerUuid.equals(moduleUuid)) {
                throw new IllegalArgumentException("manifest header and module UUIDs must be distinct");
            }
            List<Integer> moduleVersion = readVersion(
                    requiredArray(module, "version"), "modules[" + index + "].version");
            if (!headerVersion.equals(moduleVersion)) {
                throw new IllegalArgumentException("manifest header/module versions are not synchronized");
            }
        }
        Set<String> moduleUuids = new HashSet<>();
        for (JsonElement moduleElement : modules) {
            String moduleUuid = moduleElement.getAsJsonObject().get("uuid").getAsString();
            if (!moduleUuids.add(moduleUuid)) {
                throw new IllegalArgumentException("manifest module UUIDs must be unique");
            }
        }
        return List.copyOf(headerVersion);
    }

    private static List<Integer> increment(List<Integer> version) {
        List<Integer> result = new ArrayList<>(version);
        int patch = result.get(2);
        if (patch == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("manifest patch version overflow");
        }
        result.set(2, patch + 1);
        return List.copyOf(result);
    }

    private static void setSynchronizedVersion(JsonObject manifest, List<Integer> version) {
        requiredObject(manifest, "header").add("version", toJsonArray(version));
        for (JsonElement module : requiredArray(manifest, "modules")) {
            module.getAsJsonObject().add("version", toJsonArray(version));
        }
    }

    private static JsonArray toJsonArray(List<Integer> version) {
        JsonArray array = new JsonArray();
        version.forEach(array::add);
        return array;
    }

    private static List<Integer> readVersion(JsonArray array, String path) {
        if (array.size() != 3) {
            throw new IllegalArgumentException(path + " must contain exactly three integers");
        }
        List<Integer> version = new ArrayList<>(3);
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
                throw new IllegalArgumentException(path + "[" + index + "] must be an integer");
            }
            long value;
            try {
                value = primitive.getAsBigDecimal().longValueExact();
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException(path + "[" + index + "] must be an integer", error);
            }
            if (value < 0 || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(path + "[" + index + "] is out of range");
            }
            version.add((int) value);
        }
        return version;
    }

    private static void validateUuid(String value, String path) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(path + " is not a valid UUID", error);
        }
    }

    private static JsonObject requiredObject(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("manifest " + key + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("manifest " + key + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("manifest " + key + " must be a string");
        }
        return value.getAsString();
    }

    public record ManifestBuild(
            JsonObject manifest,
            List<Integer> version,
            boolean reusedIdentity,
            boolean versionIncremented
    ) {
        public String versionString() {
            return version.get(0) + "." + version.get(1) + "." + version.get(2);
        }
    }
}
