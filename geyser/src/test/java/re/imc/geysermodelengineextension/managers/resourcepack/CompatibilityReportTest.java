package re.imc.geysermodelengineextension.managers.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.Animation;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.Entity;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.Geometry;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.ModelConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityReportTest {

    @Test
    void classifiesModelsWithoutTurningWarningsIntoBuildFailures() {
        Map<String, Geometry> geometries = new HashMap<>();
        geometries.put("formal", geometry("formal", """
                [{"name":"root"},{"name":"hi_head","parent":"root"},
                 {"name":"headc","parent":"root"},{"name":"mount","parent":"root"}]
                """));
        geometries.put("disabled", geometry("disabled", """
                [{"name":"root"},{"name":"h_head","parent":"root"}]
                """));
        geometries.put("plain", geometry("plain", """
                [{"name":"root"},{"name":"head","parent":"root"}]
                """));
        geometries.put("headless", geometry("headless", """
                [{"name":"root"},{"name":"body","parent":"root"}]
                """));

        Map<String, Entity> entities = new HashMap<>();
        entities.put("formal", entity("formal", true));
        entities.put("disabled", entity("disabled", false));
        entities.put("plain", entity("plain", true));
        entities.put("headless", entity("headless", true));

        Map<String, Animation> animations = new HashMap<>();
        animations.put("formal", animation("""
                {"animations":{
                  "animation.formal.idle":{"bones":{"hi_head":{"rotation":[1,2,3]}}},
                  "animation.formal.attack":{"bones":{"hi_head":{"rotation":[4,5,6]}}}
                }}
                """));
        animations.put("disabled", animation("""
                {"animations":{"animation.disabled.idle":{"bones":{"h_head":{"rotation":[1,0,0]}}}}}
                """));
        animations.put("plain", animation("""
                {"animations":{"animation.plain.idle":{"bones":{"head":{"rotation":[0,10,0]}}}}}
                """));

        CompatibilityReport report = CompatibilityReport.analyze(entities, geometries, animations);
        JsonObject json = report.json();
        JsonObject summary = json.getAsJsonObject("summary");

        assertEquals(4, summary.get("total_models").getAsInt());
        assertEquals(2, summary.get("modelengine_head_models").getAsInt());
        assertEquals(1, summary.get("plain_head_only_models").getAsInt());
        assertEquals(1, summary.get("headless_models").getAsInt());
        assertEquals(1, summary.get("look_enabled_models").getAsInt());
        assertEquals(1, summary.get("look_disabled_by_config_models").getAsInt());
        assertEquals(3, summary.get("source_anchor_rotation_channels_preserved").getAsInt());
        assertEquals(4, summary.get("source_legacy_flatten_rotation_channels_restored").getAsInt());
        assertFalse(json.get("semantic_review_blocks_pack_generation").getAsBoolean());

        Set<String> warningCodes = report.warnings().stream()
                .map(CompatibilityReport.Warning::code)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(warningCodes.contains("MIXED_FORMAL_AND_PLAIN_HEADS"));
        assertTrue(warningCodes.contains("MOUNT_BONE_PRESENT"));
        assertTrue(warningCodes.contains("HEAD_ROTATION_DISABLED_BY_MODEL_CONFIG"));
        assertTrue(warningCodes.contains("PLAIN_HEAD_WITHOUT_MODELENGINE_BEHAVIOR"));

        assertEquals("INJECTED", model(json, "formal").getAsJsonObject("head_rotation")
                .get("look_at_target").getAsString());
        assertEquals("DISABLED_BY_HEAD_ROTATION_CONFIG",
                model(json, "disabled").getAsJsonObject("head_rotation")
                        .get("look_at_target").getAsString());
        assertEquals("NOT_INJECTED_NO_MODELENGINE_HEAD",
                model(json, "plain").getAsJsonObject("head_rotation")
                        .get("look_at_target").getAsString());
    }

    @Test
    void reportsCaseVariantPrefixAndMissingModelConfigAsReviewOnly() {
        Geometry geometry = geometry("case_variant", """
                [{"name":"root"},{"name":"H_Head","parent":"root"}]
                """);

        CompatibilityReport report = CompatibilityReport.analyze(
                Map.of(), Map.of("case_variant", geometry), Map.of());

        Set<String> codes = report.warnings().stream()
                .map(CompatibilityReport.Warning::code)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.contains("CASE_VARIANT_HEAD_PREFIX"));
        assertTrue(codes.contains("MISSING_MODEL_CONFIG"));
        assertEquals("REVIEW_REQUIRED", model(report.json(), "case_variant")
                .get("review_status").getAsString());
    }

    private static JsonObject model(JsonObject report, String id) {
        JsonArray models = report.getAsJsonArray("models");
        for (var element : models) {
            JsonObject model = element.getAsJsonObject();
            if (id.equals(model.get("model_id").getAsString())) return model;
        }
        throw new AssertionError("Missing model " + id);
    }

    private static Entity entity(String id, boolean headRotation) {
        ModelConfig config = new ModelConfig();
        config.setEnableHeadRotation(headRotation);
        Entity entity = new Entity(id);
        entity.setPath("");
        entity.setModelConfig(config);
        return entity;
    }

    private static Animation animation(String json) {
        Animation animation = new Animation();
        animation.setJson(JsonParser.parseString(json).getAsJsonObject());
        return animation;
    }

    private static Geometry geometry(String id, String bones) {
        Geometry geometry = new Geometry();
        geometry.setModelId(id);
        geometry.load("""
                {"format_version":"1.21.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.%s"},
                  "bones":%s
                }]}
                """.formatted(id, bones));
        return geometry;
    }
}
