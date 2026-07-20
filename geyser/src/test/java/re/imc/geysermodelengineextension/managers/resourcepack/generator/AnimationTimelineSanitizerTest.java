package re.imc.geysermodelengineextension.managers.resourcepack.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationTimelineSanitizerTest {

    @Test
    void removesStandaloneStringAndEmptyTimeline() {
        JsonObject root = parse("""
                {"animations":{"attack":{"timeline":{"0.2":"mm:damage;"}}}}
                """);

        assertEquals(1, AnimationTimelineSanitizer.sanitize(root));
        assertFalse(animation(root, "attack").has("timeline"));
    }

    @Test
    void filtersArraysAndPreservesNonMythicValuesInOrder() {
        JsonObject root = parse("""
                {"animations":{"attack":{"timeline":{
                  "0.2":["mm:first;", "@s test", "mm:second;", {"future":true}],
                  "0.4":["mm:only;"]
                }}}}
                """);

        assertEquals(3, AnimationTimelineSanitizer.sanitize(root));
        JsonObject timeline = animation(root, "attack").getAsJsonObject("timeline");
        assertFalse(timeline.has("0.4"));
        JsonArray remaining = timeline.getAsJsonArray("0.2");
        assertEquals(2, remaining.size());
        assertEquals("@s test", remaining.get(0).getAsString());
        assertTrue(remaining.get(1).isJsonObject());
    }

    @Test
    void onlyRemovesCompleteCaseSensitiveCommands() {
        JsonObject root = parse("""
                {"animations":{"attack":{"timeline":{
                  "0":"prefix mm:skill;",
                  "1":"mm:missing_semicolon",
                  "2":"MM:upper;",
                  "3":"mm:one; mm:two;",
                  "4":"  mm:valid_skill;  "
                }}}}
                """);

        assertEquals(1, AnimationTimelineSanitizer.sanitize(root));
        JsonObject timeline = animation(root, "attack").getAsJsonObject("timeline");
        assertEquals(4, timeline.size());
        assertFalse(timeline.has("4"));
    }

    @Test
    void recursivelyFindsTimelineObjectsWithoutTouchingUnrelatedStrings() {
        JsonObject root = parse("""
                {"metadata":"mm:keep;","nested":[{"timeline":{"1":"mm:remove;"}}]}
                """);

        assertEquals(1, AnimationTimelineSanitizer.sanitize(root));
        assertEquals("mm:keep;", root.get("metadata").getAsString());
        assertFalse(root.getAsJsonArray("nested").get(0).getAsJsonObject().has("timeline"));
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject animation(JsonObject root, String name) {
        return root.getAsJsonObject("animations").getAsJsonObject(name);
    }
}
