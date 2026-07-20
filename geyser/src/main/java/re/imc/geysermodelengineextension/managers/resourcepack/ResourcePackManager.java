package re.imc.geysermodelengineextension.managers.resourcepack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import re.imc.geysermodelengineextension.GeyserModelEngineExtension;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.*;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.data.TextureData;
import re.imc.geysermodelengineextension.util.ShortHashUtil;
import re.imc.geysermodelengineextension.util.ZipUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ResourcePackManager {

    private final GeyserModelEngineExtension extension;

    private final File inputFolder;
    private final File generatedPack;

    private final Path generatedPackZipPath;
    private final Path compatibilityReportPath;

    private final HashMap<String, Entity> entityCache = new HashMap<>();
    private final HashMap<String, Animation> animationCache = new HashMap<>();
    private final HashMap<String, Geometry> geometryCache = new HashMap<>();
    private final HashMap<String, Map<String, TextureData>> textureCache = new HashMap<>();

    private final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public ResourcePackManager(GeyserModelEngineExtension extension) {
        this.extension = extension;

        this.inputFolder = extension.dataFolder().resolve("input").toFile();
        this.inputFolder.mkdirs();

        this.generatedPack = extension.dataFolder().resolve("ResourcePack/generated_pack").toFile();
        this.generatedPackZipPath = extension.dataFolder().resolve("ResourcePack/generated_pack.zip");
        this.compatibilityReportPath = extension.dataFolder().resolve("ResourcePack/compatibility-report.json");
    }

    public synchronized void loadPack() {
        Path resourcePackDirectory = generatedPack.toPath().getParent();
        Path candidateDirectory = null;
        Path candidateZip = null;
        Path candidateReport = null;

        try {
            Files.createDirectories(resourcePackDirectory);
            validateCurrentPackPair();
            candidateDirectory = Files.createTempDirectory(resourcePackDirectory, ".generated_pack-build-");
            candidateZip = Files.createTempFile(resourcePackDirectory, ".generated_pack-build-", ".zip");
            candidateReport = Files.createTempFile(resourcePackDirectory, ".compatibility-report-build-", ".json");

            clearCaches();
            CompatibilityReport compatibility = generateResourcePack(inputFolder, candidateDirectory.toFile());
            Files.writeString(candidateReport, GSON.toJson(compatibility.json()), StandardCharsets.UTF_8);

            int sanitizedTimelineCommands = animationCache.values().stream()
                    .mapToInt(Animation::getSanitizedMythicTimelineEntries)
                    .sum();
            boolean contentChanged = !ResourcePackFileOps.contentEqualsIgnoringManifest(
                    generatedPack.toPath(), candidateDirectory);

            PackManifestManager.ManifestBuild manifest = PackManifestManager.prepare(
                    extension.getConfigManager().getResourcePackTemplatesCache().get("packmanifest"),
                    generatedPack.toPath(), generatedPackZipPath, contentChanged);
            Files.writeString(candidateDirectory.resolve("manifest.json"),
                    GSON.toJson(manifest.manifest()), StandardCharsets.UTF_8);

            ResourcePackValidator.ValidationReport validation = ResourcePackValidator.validate(candidateDirectory);
            validation.throwIfInvalid();
            ResourcePackMigrationGuard.MigrationCheck migration = ResourcePackMigrationGuard.verify(
                    generatedPack.toPath(), candidateDirectory);

            try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(candidateZip))) {
                ZipUtil.compressFolder(candidateDirectory.toFile(), null, zipOutputStream);
            }
            ResourcePackValidator.validateArchiveMatches(candidateDirectory, candidateZip);

            AtomicPackPublisher.publish(
                    candidateDirectory, candidateZip, candidateReport,
                    generatedPack.toPath(), generatedPackZipPath, compatibilityReportPath);
            candidateDirectory = null;
            candidateZip = null;
            candidateReport = null;

            extension.logger().info("Generated Bedrock model pack: models=" + entityCache.size()
                    + ", sanitized-mm=" + sanitizedTimelineCommands
                    + ", version=" + manifest.versionString()
                    + ", content-changed=" + contentChanged);
            if (migration.applied()) {
                extension.logger().info("P0 semantic migration guard passed: only "
                        + migration.removedCommands() + " mm: timeline command(s) changed");
            }
            for (String warning : validation.warnings()) {
                extension.logger().warning("Resource-pack validation warning: " + warning);
            }
            for (CompatibilityReport.Warning warning : compatibility.warnings()) {
                extension.logger().warning("[COMPATIBILITY WARN] model=" + warning.modelId()
                        + " code=" + warning.code() + " reason=" + warning.message()
                        + "; requires developer Bedrock device test");
            }
        } catch (Exception error) {
            extension.logger().severe("Bedrock model pack rebuild failed; the previous generated pack was preserved: "
                    + error.getMessage());
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(error);
        } finally {
            deleteTemporary(candidateDirectory);
            deleteTemporary(candidateZip);
            deleteTemporary(candidateReport);
        }

        try {
            for (Entity entity : entityCache.values()) {
                entity.register(extension.getConfigManager().getConfig().getString("models.namespace"));
            }
        } catch (RuntimeException error) {
            extension.logger().severe(
                    "Bedrock model pack was published, but custom-entity registration failed", error);
            throw error;
        }
    }

    private void validateCurrentPackPair() throws IOException {
        boolean directoryExists = Files.isDirectory(generatedPack.toPath());
        boolean zipExists = Files.isRegularFile(generatedPackZipPath);
        if (zipExists && !directoryExists) {
            throw new IOException("Current generated_pack directory is missing while generated_pack.zip exists; "
                    + "refusing to bypass the P0 semantic migration guard");
        }
        if (directoryExists && zipExists) {
            ResourcePackValidator.validateArchiveMatches(generatedPack.toPath(), generatedPackZipPath);
        }
    }

    private void clearCaches() {
        entityCache.clear();
        animationCache.clear();
        geometryCache.clear();
        textureCache.clear();
    }

    private void deleteTemporary(Path path) {
        try {
            ResourcePackFileOps.deleteRecursively(path);
        } catch (IOException error) {
            extension.logger().warning("Unable to remove temporary resource-pack path " + path + ": "
                    + error.getMessage());
        }
    }

    private CompatibilityReport generateResourcePack(File inputFolder, File output) {
        generateFromFolder("", inputFolder, true);

        CompatibilityReport compatibility = CompatibilityReport.analyze(
                entityCache, geometryCache, animationCache);
        ensureHeadAnimationsExist(compatibility);

        boolean hashEnabled = extension.getConfigManager().getConfig().getBoolean("options.resource-pack.hash-models-textures", true);

        File animationsFolder = new File(output, "animations");
        File entityFolder = new File(output, "entity");
        File modelsFolder = new File(output, "models/entity");
        File texturesFolder = new File(output, "textures/entity");
        File animationControllersFolder = new File(output, "animation_controllers");
        File renderControllersFolder = new File(output, "render_controllers");
        File materialsFolder = new File(output, "materials");

        output.mkdirs();

        animationsFolder.mkdirs();
        entityFolder.mkdirs();
        modelsFolder.mkdirs();
        texturesFolder.mkdirs();
        animationControllersFolder.mkdirs();
        renderControllersFolder.mkdirs();
        materialsFolder.mkdirs();

        File materialFile = new File(materialsFolder, "entity.material");

        if (!materialFile.exists()) {
            try {
                Files.writeString(materialFile.toPath(), Material.TEMPLATE, StandardCharsets.UTF_8);
            } catch (IOException err) {
                throw new RuntimeException(err);
            }
        }

        for (Map.Entry<String, Animation> entry : animationCache.entrySet()) {
            Entity entity = entityCache.get(entry.getKey());
            Geometry geo = geometryCache.get(entry.getKey());

            if (geo != null && entity != null) {
                HeadModelProfile profile = compatibility.profile(entry.getKey());
                boolean injectHeadLook = entity.getModelConfig().isEnableHeadRotation()
                        && profile != null
                        && entry.getValue().addHeadBind(profile);
                entity.setHasHeadAnimation(injectHeadLook);
                if (injectHeadLook) {
                    entry.getValue().bakeAncestorScaleToHeadAnchors(profile);
                }
            }
            entry.getValue().floorZeroScalesToEpsilon();   // 隐身用 ε 而非精确 0，防 Bedrock 冻结零尺寸实体导致本体永不现身（独立于 geo）

            Path path = animationsFolder.toPath().resolve(entry.getValue().getPath() + entry.getKey() + ".json");
            Path pathController = animationControllersFolder.toPath().resolve(entry.getValue().getPath() + entry.getKey() + ".json");

            pathController.toFile().getParentFile().mkdirs();
            path.toFile().getParentFile().mkdirs();

            if (path.toFile().exists()) continue;

            AnimationController controller = new AnimationController();
            controller.load(extension, entry.getValue(), entity);

            try {
                Files.writeString(path, GSON.toJson(entry.getValue().getJson()), StandardCharsets.UTF_8);
                Files.writeString(pathController, controller.getJson().toString(), StandardCharsets.UTF_8);
            } catch (IOException err) {
                throw new RuntimeException(err);
            }
        }

        for (Map.Entry<String, Geometry> entry : geometryCache.entrySet()) {
            entry.getValue().modify(hashEnabled);
            Path path = modelsFolder.toPath().resolve(entry.getValue().getPath() + (hashEnabled ? ShortHashUtil.hashModelId(entry.getKey()) : entry.getKey()) + ".json");
            path.toFile().getParentFile().mkdirs();

            Entity entity = entityCache.get(entry.getKey());
            if (entity != null) {
                ModelConfig modelConfig = entity.getModelConfig();
                if (!modelConfig.getPerTextureUvSize().isEmpty()) {
                    for (Map.Entry<String, TextureData> textureEntry : entity.getTextureMap().entrySet()) {
                        String name = textureEntry.getKey();

                        Integer[] size = modelConfig.getPerTextureUvSize().getOrDefault(name, new Integer[]{16, 16});
                        String suffix = size[0] + "_" + size[1];
                        entry.getValue().setTextureWidth(size[0]);
                        entry.getValue().setTextureHeight(size[1]);
                        if (hashEnabled) {
                            path = modelsFolder.toPath().resolve(ShortHashUtil.hashModelId(entry.getKey() + "_" + suffix) + ".json");
                            entry.getValue().setId("geometry." + ShortHashUtil.hashModelId(entry.getKey() + "_" + suffix));
                        } else {
                            path = modelsFolder.toPath().resolve(entry.getKey() + "_" + suffix + ".json");
                            entry.getValue().setId("geometry.meg_" + entry.getKey() + "_" + suffix);
                        }

                        if (path.toFile().exists()) continue;

                        try {
                            Files.writeString(path, GSON.toJson(entry.getValue().getJson()), StandardCharsets.UTF_8);
                        } catch (IOException err) {
                            throw new RuntimeException(err);
                        }
                    }
                }
            }

            if (path.toFile().exists()) continue;

            try {
                Files.writeString(path, GSON.toJson(entry.getValue().getJson()), StandardCharsets.UTF_8);
            } catch (IOException err) {
                throw new RuntimeException(err);
            }
        }

        for (Map.Entry<String, Map<String, TextureData>> textures : textureCache.entrySet()) {
            String modelId = textures.getKey();
            for (Map.Entry<String, TextureData> entry : textures.getValue().entrySet()) {
                String textureFileName = hashEnabled ? ShortHashUtil.hashTextureName(modelId, entry.getKey()) : entry.getKey();
                Path path = texturesFolder.toPath().resolve(textureFileName + ".png");
                path.toFile().getParentFile().mkdirs();

                if (path.toFile().exists()) continue;

                try {
                    if (entry.getValue().getImage() != null) Files.write(path, entry.getValue().getImage());
                } catch (IOException err) {
                    throw new RuntimeException(err);
                }
            }
        }

        for (Map.Entry<String, Entity> entry : entityCache.entrySet()) {
            Entity entity = entry.getValue();
            entity.modify(extension.getConfigManager().getConfig().getString("models.namespace"), hashEnabled);

            Path entityPath = entityFolder.toPath().resolve(entity.getPath() + entry.getKey() + ".json");
            entityPath.toFile().getParentFile().mkdirs();

            if (entityPath.toFile().exists()) continue;

            try {
                Files.writeString(entityPath, entity.getJson().toString(), StandardCharsets.UTF_8);
            } catch (IOException err) {
                throw new RuntimeException(err);
            }

            // render controller part

            String id = entity.getModelId();
            if (!geometryCache.containsKey(id)) continue;
            RenderController controller = new RenderController(id, geometryCache.get(id).getBones(), entity);
            entity.setRenderController(controller);
            Path renderPath = new File(renderControllersFolder, id + ".json").toPath();
            if (renderPath.toFile().exists()) continue;

            try {
                Files.writeString(renderPath, controller.generate(extension.getConfigManager().getConfig().getString("models.namespace"), hashEnabled), StandardCharsets.UTF_8);
            } catch (IOException err) {
                throw new RuntimeException(err);
            }
        }

        return compatibility;
    }

    private void ensureHeadAnimationsExist(CompatibilityReport compatibility) {
        for (Map.Entry<String, Entity> entry : entityCache.entrySet()) {
            Entity entity = entry.getValue();
            HeadModelProfile profile = compatibility.profile(entry.getKey());
            if (profile == null || profile.headAnchors().isEmpty()
                    || !entity.getModelConfig().isEnableHeadRotation()
                    || animationCache.containsKey(entry.getKey())) {
                continue;
            }

            JsonObject root = new JsonObject();
            root.addProperty("format_version", "1.8.0");
            root.add("animations", new JsonObject());

            Animation animation = new Animation();
            animation.setModelId(entry.getKey());
            animation.setPath(entity.getPath());
            animation.setJson(root);
            animationCache.put(entry.getKey(), animation);
            entity.setAnimation(animation);
        }
    }

    public void generateFromFolder(String currentPath, File folder, boolean root) {
        if (folder.listFiles() == null) return;

        String modelId = root ? "" : folder.getName().toLowerCase();

        Entity entity = new Entity(modelId);
        ModelConfig modelConfig = new ModelConfig();
        boolean shouldOverrideConfig = false;
        File textureConfigFile = new File(folder, "config.json");

        if (textureConfigFile.exists()) {
            try {
                modelConfig = GSON.fromJson(Files.readString(textureConfigFile.toPath()), ModelConfig.class);
            } catch (IOException err) {
                throw new RuntimeException(err);
            }
        }

        boolean canAdd = false;
        for (File file : folder.listFiles()) {
            if (file.isDirectory()) generateFromFolder(currentPath + (root ? "" : folder.getName() + "/"), file, false);

            if (file.getName().endsWith(".zip")) {
                try (ZipFile zip = new ZipFile(file)) {
                    generateFromZip(currentPath, file.getName().replace(".zip", "").toLowerCase(Locale.ROOT), zip);
                } catch (IOException err) {
                    throw new RuntimeException(err);
                }
            }

            if (entityCache.containsKey(modelId)) continue;

            if (file.getName().endsWith(".png")) {
                String textureName = file.getName().replace(".png", "");
                Set<String> bindingBones = new HashSet<>();
                bindingBones.add("*");
                if (modelConfig.getBingingBones().containsKey(textureName)) bindingBones = modelConfig.getBingingBones().get(textureName);

                Map<String, TextureData> map = textureCache.computeIfAbsent(modelId, s -> new HashMap<>());
                try {
                    map.put(textureName, new TextureData(modelId, currentPath, bindingBones, Files.readAllBytes(file.toPath())));
                } catch (IOException err) {
                    throw new RuntimeException(err);
                }

                entity.setTextureMap(map);
                if (modelConfig.getBingingBones().isEmpty()) {
                    modelConfig.getBingingBones().put(textureName, Set.of("*"));
                    shouldOverrideConfig = true;
                }
            }

            if (file.getName().endsWith(".json")) {
                try {
                    String json = Files.readString(file.toPath());
                    JsonObject inputJson = parseInputJson(file.toPath().toString(), json);
                    if (inputJson.has("animations")) {
                        Animation animation = new Animation();
                        animation.setPath(currentPath);
                        animation.setModelId(modelId);

                        animation.load(json);
                        animationCache.put(modelId, animation);
                        entity.setAnimation(animation);
                    }

                    if (inputJson.has("minecraft:geometry")) {
                        Geometry geometry = new Geometry();
                        geometry.load(json);
                        geometry.setPath(currentPath);
                        geometry.setModelId(modelId);
                        geometryCache.put(modelId, geometry);
                        entity.setGeometry(geometry);
                        canAdd = true;
                    }
                    validateExpectedModelJson(file.getName(), inputJson);
                } catch (IOException err) {
                    throw new RuntimeException(err);
                }
            }
        }

        if (canAdd) {
            // old config
            File oldConfig = new File(folder, "config.properties");
            Properties old = new Properties();
            try {
                if (oldConfig.exists()) {
                    old.load(new FileReader(oldConfig));
                    modelConfig.setMaterial(old.getProperty("material", "entity_alphatest_change_color"));
                    modelConfig.setEnableBlendTransition(Boolean.parseBoolean(old.getProperty("blend-transition", "true")));
                    modelConfig.setEnableHeadRotation(Boolean.parseBoolean(old.getProperty("head-rotation", "true")));
                    shouldOverrideConfig = true;
                    oldConfig.delete();
                }
            } catch (IOException err) {
                throw new RuntimeException(err);
            }

            if (shouldOverrideConfig) {
                try {
                    Files.writeString(textureConfigFile.toPath(), GSON.toJson(modelConfig));
                } catch (IOException err) {
                    throw new RuntimeException(err);
                }
            }

            entity.setModelConfig(modelConfig);
            entity.setPath(currentPath);
            entityCache.put(modelId, entity);
        }
    }

    public void generateFromZip(String currentPath, String modelId, ZipFile zip) {
        Entity entity = new Entity(modelId);
        if (entityCache.containsKey(modelId)) return;

        ModelConfig modelConfig = new ModelConfig();
        ZipEntry textureConfigFile = null;

        for (Iterator<? extends ZipEntry> it = zip.entries().asIterator(); it.hasNext(); ) {
            ZipEntry entry = it.next();
            if (entry.getName().endsWith("config.json")) {
                textureConfigFile = entry;
            }
        }

        if (textureConfigFile != null) {
            try {
                modelConfig = GSON.fromJson(
                        new InputStreamReader(zip.getInputStream(textureConfigFile), StandardCharsets.UTF_8),
                        ModelConfig.class);
            } catch (IOException err) {
                throw new RuntimeException(err);
            }
        }

        boolean canAdd = false;
        for (Iterator<? extends ZipEntry> it = zip.entries().asIterator(); it.hasNext(); ) {
            ZipEntry e = it.next();
            if (e.getName().endsWith(".png")) {
                String[] path = e.getName().split("/");
                String textureName = path[path.length - 1].replace(".png", "");
                Set<String> bindingBones = new HashSet<>();
                bindingBones.add("*");

                if (modelConfig.getBingingBones().containsKey(textureName)) {
                    bindingBones = modelConfig.getBingingBones().get(textureName);
                }

                Map<String, TextureData> map = textureCache.computeIfAbsent(modelId, s -> new HashMap<>());
                try {
                    map.put(textureName, new TextureData(modelId, currentPath, bindingBones, zip.getInputStream(e).readAllBytes()));
                } catch (IOException err) {
                    throw new RuntimeException(err);
                }

                entity.setTextureMap(map);
                if (modelConfig.getBingingBones().isEmpty()) modelConfig.getBingingBones().put(textureName, Set.of("*"));
            }

            if (e.getName().endsWith(".json")) {
                try {
                    String json;
                    try (InputStream stream = zip.getInputStream(e)) {
                        json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    JsonObject inputJson = parseInputJson(e.getName(), json);
                    if (inputJson.has("animations")) {
                        Animation animation = new Animation();
                        animation.setPath(currentPath);
                        animation.setModelId(modelId);

                        animation.load(json);
                        animationCache.put(modelId, animation);
                        entity.setAnimation(animation);
                    }

                    if (inputJson.has("minecraft:geometry")) {
                        Geometry geometry = new Geometry();
                        geometry.load(json);
                        geometry.setPath(currentPath);
                        geometry.setModelId(modelId);
                        geometryCache.put(modelId, geometry);
                        entity.setGeometry(geometry);
                        canAdd = true;
                    }
                    validateExpectedModelJson(e.getName(), inputJson);
                } catch (IOException err) {
                    throw new RuntimeException(err);
                }
            }
        }

        if (canAdd) {
            entity.setModelConfig(modelConfig);
            entity.setPath(currentPath);
            entityCache.put(modelId, entity);
        }
    }

    private JsonObject parseInputJson(String source, String json) {
        try {
            if (!JsonParser.parseString(json).isJsonObject()) {
                throw new IllegalArgumentException("root must be a JSON object");
            }
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid JSON input " + source + ": " + error.getMessage(), error);
        }
    }

    private void validateExpectedModelJson(String fileName, JsonObject json) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".animation.json") && !json.has("animations")) {
            throw new IllegalArgumentException("Animation input " + fileName + " is missing animations");
        }
        if (normalized.endsWith(".geo.json") && !json.has("minecraft:geometry")) {
            throw new IllegalArgumentException("Geometry input " + fileName + " is missing minecraft:geometry");
        }
    }

    public File getInputFolder() {
        return inputFolder;
    }

    public Path getGeneratedPackZipPath() {
        return generatedPackZipPath;
    }

    public HashMap<String, Entity> getEntityCache() {
        return entityCache;
    }

    public HashMap<String, Animation> getAnimationCache() {
        return animationCache;
    }

    public HashMap<String, Geometry> getGeometryCache() {
        return geometryCache;
    }

    public HashMap<String, Map<String, TextureData>> getTextureCache() {
        return textureCache;
    }
}
