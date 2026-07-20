package re.imc.geysermodelengineextension.managers.resourcepack.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadTranslationTest {

    @Test
    void addsLookOnlyToFormalTopLevelAnchorAndPreservesAuthoredRotation() {
        Geometry geometry = geometry("""
                [
                  {"name":"root"},
                  {"name":"hi_head","parent":"root"},
                  {"name":"helper","parent":"hi_head"},
                  {"name":"h_eye","parent":"helper"},
                  {"name":"headc","parent":"root"}
                ]
                """);
        HeadModelProfile profile = HeadModelProfile.analyze(geometry);
        Animation animation = animation("""
                {"format_version":"1.8.0","animations":{"animation.test.attack":{"bones":{
                  "hi_head":{"rotation":[10,20,30]},
                  "headc":{"rotation":[0,45,0]}
                }}}}
                """);
        JsonObject authoredBefore = animation.getJson().getAsJsonObject("animations")
                .getAsJsonObject("animation.test.attack").deepCopy();

        assertTrue(animation.addHeadBind(profile));

        JsonObject animations = animation.getJson().getAsJsonObject("animations");
        assertEquals(authoredBefore, animations.getAsJsonObject("animation.test.attack"));
        JsonObject lookBones = animations.getAsJsonObject("animation.test.look_at_target")
                .getAsJsonObject("bones");
        assertTrue(lookBones.has("hi_head"));
        assertFalse(lookBones.has("h_eye"));
        assertFalse(lookBones.has("headc"));
    }

    @Test
    void plainHeadAndHeadlessModelsNeverGainLookFallback() {
        Entity plain = entity("plain", false, animation(emptyAnimations()));
        plain.modify("modelengine", false);
        Entity headless = entity("headless", false, null);
        headless.modify("modelengine", false);

        assertFalse(description(plain).getAsJsonObject("animations").has("look_at_target"));
        assertFalse(description(headless).getAsJsonObject("animations").has("look_at_target"));
    }

    @Test
    void modelSpecificLookRunsBeforeStateControllers() {
        Animation animation = animation(emptyAnimations());
        Entity entity = entity("formal", true, animation);
        entity.modify("modelengine", false);

        JsonObject description = description(entity);
        assertEquals("animation.formal.look_at_target",
                description.getAsJsonObject("animations").get("look_at_target").getAsString());
        JsonArray animate = description.getAsJsonObject("scripts").getAsJsonArray("animate");
        assertEquals("look_at_target", animate.get(0).getAsString());
    }

    private static String emptyAnimations() {
        return "{\"format_version\":\"1.8.0\",\"animations\":{}}";
    }

    private static Entity entity(String id, boolean hasHeadAnimation, Animation animation) {
        Entity entity = new Entity(id);
        entity.setPath("");
        entity.setModelConfig(new ModelConfig());
        entity.setTextureMap(java.util.Map.of());
        entity.setHasHeadAnimation(hasHeadAnimation);
        entity.setAnimation(animation);
        return entity;
    }

    private static JsonObject description(Entity entity) {
        return entity.getJson().getAsJsonObject("minecraft:client_entity")
                .getAsJsonObject("description");
    }

    private static Animation animation(String json) {
        Animation animation = new Animation();
        animation.setModelId("test");
        animation.setJson(JsonParser.parseString(json).getAsJsonObject());
        return animation;
    }

    private static Geometry geometry(String bones) {
        Geometry geometry = new Geometry();
        geometry.setModelId("test");
        geometry.load("""
                {"format_version":"1.21.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.test"},
                  "bones":%s
                }]}
                """.formatted(bones));
        return geometry;
    }
}
