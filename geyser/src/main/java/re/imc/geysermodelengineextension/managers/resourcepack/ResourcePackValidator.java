package re.imc.geysermodelengineextension.managers.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Fail-closed validation for generated Bedrock resource packs. */
public final class ResourcePackValidator {

    private ResourcePackValidator() {
    }

    public static ValidationReport validate(Path packRoot) throws IOException {
        ValidationState state = new ValidationState(packRoot);
        state.validate();
        return state.report();
    }

    public static void validateArchiveMatches(Path packRoot, Path archive) throws IOException {
        List<Path> relativeFiles = ResourcePackFileOps.relativeFiles(packRoot, false);
        Map<String, Path> expected = new LinkedHashMap<>();
        for (Path relativeFile : relativeFiles) {
            expected.put(entryName(relativeFile), packRoot.resolve(relativeFile));
        }

        Map<String, ZipEntry> actual = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (actual.putIfAbsent(entry.getName(), entry) != null) {
                    throw new IOException("Duplicate ZIP entry: " + entry.getName());
                }
            }

            if (!expected.keySet().equals(actual.keySet())) {
                Set<String> missing = new TreeSet<>(expected.keySet());
                missing.removeAll(actual.keySet());
                Set<String> extra = new TreeSet<>(actual.keySet());
                extra.removeAll(expected.keySet());
                throw new IOException("ZIP entries do not match generated directory; missing=" + missing + ", extra=" + extra);
            }

            for (Map.Entry<String, Path> expectedEntry : expected.entrySet()) {
                try (InputStream disk = Files.newInputStream(expectedEntry.getValue());
                     InputStream zipped = zip.getInputStream(actual.get(expectedEntry.getKey()))) {
                    if (!streamsEqual(disk, zipped)) {
                        throw new IOException("ZIP content differs for " + expectedEntry.getKey());
                    }
                }
            }
        }
    }

    private static boolean streamsEqual(InputStream left, InputStream right) throws IOException {
        byte[] leftBuffer = new byte[8192];
        byte[] rightBuffer = new byte[8192];
        while (true) {
            int leftRead = left.readNBytes(leftBuffer, 0, leftBuffer.length);
            int rightRead = right.readNBytes(rightBuffer, 0, rightBuffer.length);
            if (leftRead != rightRead) {
                return false;
            }
            if (leftRead == 0) {
                return true;
            }
            for (int index = 0; index < leftRead; index++) {
                if (leftBuffer[index] != rightBuffer[index]) {
                    return false;
                }
            }
        }
    }

    private static String entryName(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    public record ValidationReport(
            int jsonFiles,
            int entities,
            int geometries,
            int animations,
            int animationControllers,
            int renderControllers,
            int textures,
            int remainingMythicCommands,
            List<String> warnings,
            List<String> errors
    ) {
        public ValidationReport {
            warnings = List.copyOf(warnings);
            errors = List.copyOf(errors);
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public void throwIfInvalid() {
            if (!isValid()) {
                throw new IllegalStateException("Generated resource pack validation failed:\n - "
                        + String.join("\n - ", errors));
            }
        }
    }

    private static final class ValidationState {

        private final Path root;
        private final List<String> warnings = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final Map<Path, JsonObject> jsonFiles = new LinkedHashMap<>();

        private final Map<String, JsonObject> geometries = new LinkedHashMap<>();
        private final Map<String, JsonObject> animations = new LinkedHashMap<>();
        private final Map<String, JsonObject> animationControllers = new LinkedHashMap<>();
        private final Map<String, JsonObject> renderControllers = new LinkedHashMap<>();
        private final Set<String> textures = new LinkedHashSet<>();
        private final List<EntityDefinition> entities = new ArrayList<>();

        private final Set<String> usedGeometries = new HashSet<>();
        private final Set<String> usedAnimations = new HashSet<>();
        private final Set<String> usedAnimationControllers = new HashSet<>();
        private final Set<String> usedRenderControllers = new HashSet<>();
        private final Set<String> usedTextures = new HashSet<>();

        private int remainingMythicCommands;

        private ValidationState(Path root) {
            this.root = root;
        }

        private void validate() throws IOException {
            if (!Files.isDirectory(root)) {
                errors.add("Pack root is not a directory: " + root);
                return;
            }

            loadJsonFiles();
            validateManifest();
            collectDefinitions();
            validateEntities();
            addUnusedWarnings();

            if (remainingMythicCommands > 0) {
                errors.add("Found " + remainingMythicCommands + " remaining mm: command(s) in generated JSON");
            }
            if (entities.isEmpty()) {
                errors.add("Pack contains no minecraft:client_entity definitions");
            }
            if (geometries.isEmpty()) {
                errors.add("Pack contains no minecraft:geometry definitions");
            }
        }

        private void loadJsonFiles() throws IOException {
            for (Path relative : ResourcePackFileOps.relativeFiles(root, false)) {
                if (!relative.toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                    continue;
                }
                Path file = root.resolve(relative);
                try {
                    JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                    if (!parsed.isJsonObject()) {
                        errors.add(entryName(relative) + " root is not a JSON object");
                        continue;
                    }
                    JsonObject object = parsed.getAsJsonObject();
                    jsonFiles.put(relative, object);
                    int fileMythicCommands = countMythicCommands(object);
                    if (fileMythicCommands > 0) {
                        errors.add(entryName(relative) + " contains " + fileMythicCommands + " mm: command(s)");
                        remainingMythicCommands += fileMythicCommands;
                    }
                } catch (RuntimeException error) {
                    errors.add(entryName(relative) + " is invalid JSON: " + error.getMessage());
                }
            }
        }

        private void validateManifest() {
            JsonObject manifest = jsonFiles.get(Path.of("manifest.json"));
            if (manifest == null) {
                errors.add("Missing or invalid manifest.json");
                return;
            }
            try {
                PackManifestManager.validateAndReadVersion(manifest);
            } catch (IllegalArgumentException error) {
                errors.add("Invalid manifest.json: " + error.getMessage());
            }
        }

        private void collectDefinitions() {
            for (Map.Entry<Path, JsonObject> file : jsonFiles.entrySet()) {
                JsonObject rootObject = file.getValue();
                collectGeometryDefinitions(file.getKey(), rootObject);
                collectObjectDefinitions(file.getKey(), rootObject, "animations", animations);
                collectObjectDefinitions(file.getKey(), rootObject, "animation_controllers", animationControllers);
                collectObjectDefinitions(file.getKey(), rootObject, "render_controllers", renderControllers);
                collectEntityDefinition(file.getKey(), rootObject);
            }

            try {
                for (Path relative : ResourcePackFileOps.relativeFiles(root, false)) {
                    String path = entryName(relative);
                    if (path.toLowerCase(Locale.ROOT).endsWith(".png")) {
                        textures.add(path.substring(0, path.length() - 4));
                    }
                }
            } catch (IOException error) {
                errors.add("Unable to enumerate textures: " + error.getMessage());
            }
        }

        private void collectGeometryDefinitions(Path file, JsonObject object) {
            JsonElement geometryElement = object.get("minecraft:geometry");
            if (geometryElement == null) {
                return;
            }
            if (!geometryElement.isJsonArray()) {
                errors.add(entryName(file) + " minecraft:geometry must be an array");
                return;
            }
            for (JsonElement geometryElementEntry : geometryElement.getAsJsonArray()) {
                if (!geometryElementEntry.isJsonObject()) {
                    errors.add(entryName(file) + " contains a non-object geometry");
                    continue;
                }
                JsonObject geometry = geometryElementEntry.getAsJsonObject();
                String id = nestedString(geometry, "description", "identifier");
                if (id == null) {
                    errors.add(entryName(file) + " contains geometry without description.identifier");
                    continue;
                }
                registerDefinition(geometries, id, geometry, file, "geometry");
            }
        }

        private void collectObjectDefinitions(
                Path file,
                JsonObject object,
                String key,
                Map<String, JsonObject> destination
        ) {
            JsonElement definitionsElement = object.get(key);
            if (definitionsElement == null) {
                return;
            }
            if (!definitionsElement.isJsonObject()) {
                errors.add(entryName(file) + " " + key + " must be an object");
                return;
            }
            for (Map.Entry<String, JsonElement> definition : definitionsElement.getAsJsonObject().entrySet()) {
                if (!definition.getValue().isJsonObject()) {
                    errors.add(entryName(file) + " " + key + "." + definition.getKey() + " must be an object");
                    continue;
                }
                registerDefinition(destination, definition.getKey(), definition.getValue().getAsJsonObject(), file, key);
            }
        }

        private void collectEntityDefinition(Path file, JsonObject object) {
            JsonElement entityElement = object.get("minecraft:client_entity");
            if (entityElement == null) {
                return;
            }
            if (!entityElement.isJsonObject()) {
                errors.add(entryName(file) + " minecraft:client_entity must be an object");
                return;
            }
            JsonElement description = entityElement.getAsJsonObject().get("description");
            if (description == null || !description.isJsonObject()) {
                errors.add(entryName(file) + " client entity is missing description");
                return;
            }
            entities.add(new EntityDefinition(file, description.getAsJsonObject()));
        }

        private void registerDefinition(
                Map<String, JsonObject> destination,
                String id,
                JsonObject definition,
                Path file,
                String type
        ) {
            if (destination.putIfAbsent(id, definition) != null) {
                errors.add("Duplicate " + type + " identifier " + id + " in " + entryName(file));
            }
        }

        private void validateEntities() {
            Set<String> entityIds = new HashSet<>();
            for (EntityDefinition entity : entities) {
                JsonObject description = entity.description();
                String entityId = stringValue(description.get("identifier"));
                if (entityId == null) {
                    errors.add(entryName(entity.file()) + " client entity is missing identifier");
                } else if (!entityIds.add(entityId)) {
                    errors.add("Duplicate client entity identifier " + entityId);
                }

                JsonObject geometryAliases = optionalObject(description, "geometry", entity.file());
                JsonObject textureAliases = optionalObject(description, "textures", entity.file());
                JsonObject animationAliases = optionalObject(description, "animations", entity.file());

                validateAliasReferences(entity.file(), "geometry", geometryAliases, geometries, usedGeometries);
                validateAliasReferences(entity.file(), "texture", textureAliases, textures, usedTextures);

                Map<String, String> referencedControllers = new HashMap<>();
                if (animationAliases != null) {
                    for (Map.Entry<String, JsonElement> alias : animationAliases.entrySet()) {
                        String reference = stringValue(alias.getValue());
                        if (reference == null) {
                            errors.add(entryName(entity.file()) + " animation alias " + alias.getKey() + " is not a string");
                        } else if (animations.containsKey(reference)) {
                            usedAnimations.add(reference);
                        } else if (animationControllers.containsKey(reference)) {
                            usedAnimationControllers.add(reference);
                            referencedControllers.put(reference, alias.getKey());
                        } else {
                            errors.add(entryName(entity.file()) + " references missing animation/controller " + reference);
                        }
                    }
                    validateAnimateScript(entity.file(), description, animationAliases.keySet());
                }

                for (String controllerId : referencedControllers.keySet()) {
                    validateControllerAliases(entity.file(), controllerId, animationAliases == null
                            ? Set.of() : animationAliases.keySet());
                }

                validateRenderControllers(entity.file(), description, geometryAliases, textureAliases);
            }
        }

        private void validateAliasReferences(
                Path file,
                String type,
                JsonObject aliases,
                Map<String, JsonObject> definitions,
                Set<String> used
        ) {
            if (aliases == null) {
                return;
            }
            validateAliasReferences(file, type, aliases, definitions.keySet(), used);
        }

        private void validateAliasReferences(
                Path file,
                String type,
                JsonObject aliases,
                Set<String> definitions,
                Set<String> used
        ) {
            if (aliases == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> alias : aliases.entrySet()) {
                String reference = stringValue(alias.getValue());
                if (reference == null) {
                    errors.add(entryName(file) + " " + type + " alias " + alias.getKey() + " is not a string");
                } else if (!definitions.contains(reference)) {
                    errors.add(entryName(file) + " references missing " + type + " " + reference);
                } else {
                    used.add(reference);
                }
            }
        }

        private void validateAnimateScript(Path file, JsonObject description, Set<String> animationAliases) {
            JsonObject scripts = optionalObject(description, "scripts", file);
            if (scripts == null || !scripts.has("animate")) {
                return;
            }
            JsonElement animate = scripts.get("animate");
            if (!animate.isJsonArray()) {
                errors.add(entryName(file) + " scripts.animate must be an array");
                return;
            }
            for (JsonElement element : animate.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    validateLocalAlias(file, "scripts.animate", element.getAsString(), animationAliases);
                } else if (element.isJsonObject()) {
                    for (String alias : element.getAsJsonObject().keySet()) {
                        validateLocalAlias(file, "scripts.animate", alias, animationAliases);
                    }
                }
            }
        }

        private void validateControllerAliases(Path file, String controllerId, Set<String> animationAliases) {
            JsonObject controller = animationControllers.get(controllerId);
            JsonObject states = optionalObject(controller, "states", file);
            if (states == null) {
                return;
            }
            for (JsonElement stateElement : states.asMap().values()) {
                if (!stateElement.isJsonObject()) {
                    continue;
                }
                JsonElement stateAnimations = stateElement.getAsJsonObject().get("animations");
                if (stateAnimations == null || !stateAnimations.isJsonArray()) {
                    continue;
                }
                for (JsonElement animation : stateAnimations.getAsJsonArray()) {
                    if (animation.isJsonPrimitive() && animation.getAsJsonPrimitive().isString()) {
                        validateLocalAlias(file, controllerId, animation.getAsString(), animationAliases);
                    } else if (animation.isJsonObject()) {
                        for (String alias : animation.getAsJsonObject().keySet()) {
                            validateLocalAlias(file, controllerId, alias, animationAliases);
                        }
                    }
                }
            }
        }

        private void validateLocalAlias(Path file, String owner, String alias, Set<String> aliases) {
            if (!aliases.contains(alias)) {
                errors.add(entryName(file) + " " + owner + " references missing local animation alias " + alias);
            }
        }

        private void validateRenderControllers(
                Path file,
                JsonObject description,
                JsonObject geometryAliases,
                JsonObject textureAliases
        ) {
            JsonElement references = description.get("render_controllers");
            if (references == null) {
                return;
            }
            if (!references.isJsonArray()) {
                errors.add(entryName(file) + " render_controllers must be an array");
                return;
            }

            for (JsonElement referenceElement : references.getAsJsonArray()) {
                Set<String> ids = new LinkedHashSet<>();
                if (referenceElement.isJsonPrimitive() && referenceElement.getAsJsonPrimitive().isString()) {
                    ids.add(referenceElement.getAsString());
                } else if (referenceElement.isJsonObject()) {
                    ids.addAll(referenceElement.getAsJsonObject().keySet());
                }
                for (String id : ids) {
                    JsonObject controller = renderControllers.get(id);
                    if (controller == null) {
                        errors.add(entryName(file) + " references missing render controller " + id);
                        continue;
                    }
                    usedRenderControllers.add(id);
                    validateRenderAlias(file, id, controller.get("geometry"), "Geometry.", geometryAliases);
                    JsonElement controllerTextures = controller.get("textures");
                    if (controllerTextures != null && controllerTextures.isJsonArray()) {
                        for (JsonElement texture : controllerTextures.getAsJsonArray()) {
                            validateRenderAlias(file, id, texture, "Texture.", textureAliases);
                        }
                    }
                }
            }
        }

        private void validateRenderAlias(
                Path file,
                String controllerId,
                JsonElement element,
                String prefix,
                JsonObject aliases
        ) {
            String value = stringValue(element);
            if (value == null || !value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return;
            }
            String alias = value.substring(prefix.length());
            if (aliases == null || !aliases.has(alias)) {
                errors.add(entryName(file) + " " + controllerId + " references missing " + prefix + alias);
            }
        }

        private void addUnusedWarnings() {
            addUnusedWarning("geometry", geometries.keySet(), usedGeometries);
            addUnusedWarning("animation", animations.keySet(), usedAnimations);
            addUnusedWarning("animation controller", animationControllers.keySet(), usedAnimationControllers);
            addUnusedWarning("render controller", renderControllers.keySet(), usedRenderControllers);
            addUnusedWarning("texture", textures, usedTextures);
        }

        private void addUnusedWarning(String type, Set<String> defined, Set<String> used) {
            int count = 0;
            for (String id : defined) {
                if (!used.contains(id)) {
                    count++;
                }
            }
            if (count > 0) {
                warnings.add(count + " unreferenced " + type + " definition(s) retained");
            }
        }

        private ValidationReport report() {
            return new ValidationReport(
                    jsonFiles.size(), entities.size(), geometries.size(), animations.size(),
                    animationControllers.size(), renderControllers.size(), textures.size(),
                    remainingMythicCommands, warnings, errors);
        }

        private JsonObject optionalObject(JsonObject parent, String key, Path file) {
            if (parent == null) {
                return null;
            }
            JsonElement value = parent.get(key);
            if (value == null) {
                return null;
            }
            if (!value.isJsonObject()) {
                errors.add(entryName(file) + " " + key + " must be an object");
                return null;
            }
            return value.getAsJsonObject();
        }

        private String nestedString(JsonObject parent, String objectKey, String valueKey) {
            JsonElement nested = parent.get(objectKey);
            if (nested == null || !nested.isJsonObject()) {
                return null;
            }
            return stringValue(nested.getAsJsonObject().get(valueKey));
        }

        private String stringValue(JsonElement element) {
            return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    ? element.getAsString() : null;
        }

        private int countMythicCommands(JsonElement element) {
            if (element == null || element.isJsonNull()) {
                return 0;
            }
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString();
                int count = 0;
                int from = 0;
                while ((from = value.indexOf("mm:", from)) >= 0) {
                    count++;
                    from += 3;
                }
                return count;
            }
            int count = 0;
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    count += countMythicCommands(child);
                }
            } else if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    count += countOccurrences(entry.getKey(), "mm:");
                    count += countMythicCommands(entry.getValue());
                }
            }
            return count;
        }

        private int countOccurrences(String value, String needle) {
            int count = 0;
            int from = 0;
            while ((from = value.indexOf(needle, from)) >= 0) {
                count++;
                from += needle.length();
            }
            return count;
        }
    }

    private record EntityDefinition(Path file, JsonObject description) {
    }
}
