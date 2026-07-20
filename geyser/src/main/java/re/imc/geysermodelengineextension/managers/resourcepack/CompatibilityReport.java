package re.imc.geysermodelengineextension.managers.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.Animation;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.Entity;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.Geometry;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.HeadModelProfile;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.ModelConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Static compatibility audit emitted next to each generated resource pack. */
final class CompatibilityReport {

    private final Map<String, HeadModelProfile> profiles;
    private final List<Warning> warnings;
    private final JsonObject json;

    private CompatibilityReport(
            Map<String, HeadModelProfile> profiles,
            List<Warning> warnings,
            JsonObject json
    ) {
        this.profiles = Map.copyOf(profiles);
        this.warnings = List.copyOf(warnings);
        this.json = json;
    }

    static CompatibilityReport analyze(
            Map<String, Entity> entities,
            Map<String, Geometry> geometries,
            Map<String, Animation> animations
    ) {
        Map<String, HeadModelProfile> profiles = new LinkedHashMap<>();
        List<Warning> warnings = new ArrayList<>();
        JsonArray models = new JsonArray();

        int formalModels = 0;
        int plainOnlyModels = 0;
        int headlessModels = 0;
        int lookEnabledModels = 0;
        int lookDisabledByConfigModels = 0;
        int reviewModels = 0;
        int sourceAnchorRotationChannels = 0;

        for (String modelId : geometries.keySet().stream().sorted().toList()) {
            Geometry geometry = geometries.get(modelId);
            HeadModelProfile profile = HeadModelProfile.analyze(geometry);
            profiles.put(modelId, profile);

            Entity entity = entities.get(modelId);
            ModelConfig config = entity == null ? null : entity.getModelConfig();
            boolean headRotationEnabled = config == null || config.isEnableHeadRotation();
            boolean hasFormalHead = !profile.modelEngineHeadBones().isEmpty();
            boolean plainOnly = !hasFormalHead && !profile.plainHeadBones().isEmpty();
            boolean headless = !hasFormalHead
                    && profile.plainHeadBones().isEmpty()
                    && profile.caseVariantHeadBones().isEmpty();
            boolean injectLook = hasFormalHead && headRotationEnabled;

            if (hasFormalHead) formalModels++;
            if (plainOnly) plainOnlyModels++;
            if (headless) headlessModels++;
            if (injectLook) lookEnabledModels++;
            if (hasFormalHead && !headRotationEnabled) lookDisabledByConfigModels++;

            SourceRotationSummary rotationSummary = sourceRotationSummary(
                    animations.get(modelId), profile.headAnchors());
            sourceAnchorRotationChannels += rotationSummary.channels();

            boolean sourceAnimationPresent = animations.get(modelId) != null;
            List<Warning> modelWarnings = collectWarnings(
                    modelId, profile, headRotationEnabled, sourceAnimationPresent);
            warnings.addAll(modelWarnings);
            if (!modelWarnings.isEmpty()) reviewModels++;

            JsonObject model = new JsonObject();
            model.addProperty("model_id", modelId);
            model.addProperty("source_path", entity == null || entity.getPath() == null ? "" : entity.getPath());
            model.addProperty("classification", classification(profile));

            JsonObject headRotation = new JsonObject();
            headRotation.addProperty("effective", headRotationEnabled);
            headRotation.addProperty("look_at_target", injectLook
                    ? "INJECTED"
                    : hasFormalHead ? "DISABLED_BY_HEAD_ROTATION_CONFIG"
                    : "NOT_INJECTED_NO_MODELENGINE_HEAD");
            headRotation.addProperty("source_animation_present", sourceAnimationPresent);
            model.add("head_rotation", headRotation);

            model.add("head_anchors", strings(profile.headAnchors()));
            model.add("modelengine_head_bones", strings(profile.modelEngineHeadBones()));
            model.add("nested_head_bones", strings(profile.nestedHeadBones()));
            model.add("plain_head_bones", strings(profile.plainHeadBones()));
            model.add("case_variant_head_prefix_bones", strings(profile.caseVariantHeadBones()));
            model.add("non_head_named_anchors", strings(profile.nonHeadNamedAnchors()));
            model.add("source_anchor_rotation_bones", strings(rotationSummary.bones()));
            model.addProperty("source_anchor_rotation_channels", rotationSummary.channels());

            JsonObject runtimeLocks = new JsonObject();
            runtimeLocks.addProperty("lockyaw", "NOT_AVAILABLE_IN_EXPORTED_RESOURCE_JSON");
            runtimeLocks.addProperty("lockpitch", "NOT_AVAILABLE_IN_EXPORTED_RESOURCE_JSON");
            model.add("runtime_locks", runtimeLocks);
            model.addProperty("custom_behavior_parser", "NOT_STATICALLY_DETECTABLE");

            JsonObject relationshipRisk = new JsonObject();
            relationshipRisk.addProperty("mount_bone_present", !profile.mountBones().isEmpty());
            relationshipRisk.add("mount_bones", strings(profile.mountBones()));
            relationshipRisk.addProperty(
                    "passenger_or_multi_model_relationship",
                    "NOT_STATICALLY_DETECTABLE_FROM_VELOCITY_INPUT");
            model.add("passenger_multi_model", relationshipRisk);

            model.addProperty("review_status", modelWarnings.isEmpty() ? "AUTO_SUPPORTED" : "REVIEW_REQUIRED");
            JsonArray risks = new JsonArray();
            for (Warning warning : modelWarnings) {
                JsonObject risk = new JsonObject();
                risk.addProperty("code", warning.code());
                risk.addProperty("message", warning.message());
                risk.addProperty("requires_developer_bedrock_device_test", true);
                risks.add(risk);
            }
            model.add("risks", risks);
            models.add(model);
        }

        JsonObject summary = new JsonObject();
        summary.addProperty("total_models", geometries.size());
        summary.addProperty("modelengine_head_models", formalModels);
        summary.addProperty("plain_head_only_models", plainOnlyModels);
        summary.addProperty("headless_models", headlessModels);
        summary.addProperty("look_enabled_models", lookEnabledModels);
        summary.addProperty("look_disabled_by_config_models", lookDisabledByConfigModels);
        summary.addProperty("review_required_models", reviewModels);
        summary.addProperty("source_anchor_rotation_channels_preserved", sourceAnchorRotationChannels);

        JsonArray limitations = new JsonArray();
        limitations.add("Custom ModelEngine behavior parsers are not represented in exported resource JSON.");
        limitations.add("MythicMobs passenger relationships and multiple model attachments cannot be proven from Velocity input.");
        limitations.add("Runtime lockyaw and lockpitch values are supplied by Paper and cannot be audited here.");

        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.addProperty("head_semantics", "MODELENGINE_4_CASE_SENSITIVE_H_AND_HI_PREFIXES");
        root.addProperty("semantic_review_blocks_pack_generation", false);
        root.add("summary", summary);
        root.add("static_analysis_limitations", limitations);
        root.add("models", models);
        return new CompatibilityReport(profiles, warnings, root);
    }

    private static List<Warning> collectWarnings(
            String modelId,
            HeadModelProfile profile,
            boolean headRotationEnabled,
            boolean sourceAnimationPresent
    ) {
        List<Warning> result = new ArrayList<>();
        if (!profile.caseVariantHeadBones().isEmpty()) {
            result.add(new Warning(modelId, "CASE_VARIANT_HEAD_PREFIX",
                    "Case-variant H_/HI_ prefixes are not ModelEngine Head behaviors: "
                            + profile.caseVariantHeadBones()));
        }
        if (profile.modelEngineHeadBones().isEmpty() && !profile.plainHeadBones().isEmpty()) {
            result.add(new Warning(modelId, "PLAIN_HEAD_WITHOUT_MODELENGINE_BEHAVIOR",
                    "Head-like bones are preserved as authored and do not receive runtime look: "
                            + profile.plainHeadBones()));
        }
        if (!profile.modelEngineHeadBones().isEmpty() && !profile.plainHeadBones().isEmpty()) {
            result.add(new Warning(modelId, "MIXED_FORMAL_AND_PLAIN_HEADS",
                    "Formal ModelEngine Head bones coexist with plain head-like bones: "
                            + profile.plainHeadBones()));
        }
        if (!profile.modelEngineHeadBones().isEmpty() && !headRotationEnabled) {
            result.add(new Warning(modelId, "HEAD_ROTATION_DISABLED_BY_MODEL_CONFIG",
                    "head_rotation:false is respected; no look animation or head scale escape bake is generated"));
        }
        if (!profile.modelEngineHeadBones().isEmpty() && headRotationEnabled && !sourceAnimationPresent) {
            result.add(new Warning(modelId, "SYNTHESIZED_HEAD_ANIMATION_FILE",
                    "No source animation JSON was present; a look-only animation file is generated"));
        }
        if (!profile.nestedHeadBones().isEmpty()) {
            result.add(new Warning(modelId, "NESTED_HEAD_BEHAVIORS",
                    "Nested h_/hi_ bones inherit the top-level anchor instead of receiving duplicate look: "
                            + profile.nestedHeadBones()));
        }
        if (profile.headAnchors().size() > 1) {
            result.add(new Warning(modelId, "MULTIPLE_HEAD_ANCHORS",
                    "Multiple independent ModelEngine Head anchors require Bedrock review: "
                            + profile.headAnchors()));
        }
        if (!profile.nonHeadNamedAnchors().isEmpty()) {
            result.add(new Warning(modelId, "NON_HEAD_NAMED_MODELENGINE_ANCHOR",
                    "ModelEngine marks these non-head-named bones as Head behavior: "
                            + profile.nonHeadNamedAnchors()));
        }
        if (!profile.mountBones().isEmpty()) {
            result.add(new Warning(modelId, "MOUNT_BONE_PRESENT",
                    "mount_bone_present; passenger or multi-model behavior is not inferred and needs manual review"));
        }
        return result;
    }

    private static String classification(HeadModelProfile profile) {
        if (!profile.modelEngineHeadBones().isEmpty()) return "MODELENGINE_HEAD";
        if (!profile.caseVariantHeadBones().isEmpty()) return "CASE_VARIANT_HEAD_PREFIX";
        if (!profile.plainHeadBones().isEmpty()) return "PLAIN_HEAD_ONLY";
        return "HEADLESS";
    }

    private static SourceRotationSummary sourceRotationSummary(Animation animation, List<String> anchors) {
        if (animation == null || animation.getJson() == null || anchors.isEmpty()) {
            return new SourceRotationSummary(List.of(), 0);
        }
        JsonElement animationsElement = animation.getJson().get("animations");
        if (animationsElement == null || !animationsElement.isJsonObject()) {
            return new SourceRotationSummary(List.of(), 0);
        }

        Set<String> anchorSet = new HashSet<>(anchors);
        Set<String> rotationBones = new HashSet<>();
        int channels = 0;
        for (JsonElement animationElement : animationsElement.getAsJsonObject().asMap().values()) {
            if (!animationElement.isJsonObject()) continue;
            JsonElement bonesElement = animationElement.getAsJsonObject().get("bones");
            if (bonesElement == null || !bonesElement.isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> bone : bonesElement.getAsJsonObject().entrySet()) {
                String name = bone.getKey().trim().toLowerCase(Locale.ROOT);
                if (!anchorSet.contains(name) || !bone.getValue().isJsonObject()) continue;
                if (bone.getValue().getAsJsonObject().has("rotation")) {
                    channels++;
                    rotationBones.add(name);
                }
            }
        }
        return new SourceRotationSummary(rotationBones.stream().sorted().toList(), channels);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    HeadModelProfile profile(String modelId) {
        return profiles.get(modelId);
    }

    List<Warning> warnings() {
        return warnings;
    }

    JsonObject json() {
        return json.deepCopy();
    }

    record Warning(String modelId, String code, String message) {
    }

    private record SourceRotationSummary(List<String> bones, int channels) {
    }
}
