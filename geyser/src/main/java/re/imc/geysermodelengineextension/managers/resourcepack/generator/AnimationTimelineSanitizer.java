package re.imc.geysermodelengineextension.managers.resourcepack.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes ModelEngine's server-side MythicMobs timeline commands from the
 * generated Bedrock animation tree. Bedrock cannot execute these commands;
 * the authoritative copy remains in ModelEngine's server-side blueprint.
 */
public final class AnimationTimelineSanitizer {

    private static final Pattern STANDALONE_MYTHIC_COMMAND =
            Pattern.compile("\\s*mm:[^;\\r\\n]+;\\s*");

    private AnimationTimelineSanitizer() {
    }

    /**
     * Recursively finds timeline objects and removes only standalone
     * {@code mm:<skill>;} values. String and array timestamp values are
     * supported. Everything else is preserved byte-for-byte at the JSON-tree
     * level.
     *
     * @return number of removed MythicMobs command strings
     */
    public static int sanitize(JsonObject root) {
        return sanitizeElement(root);
    }

    private static int sanitizeElement(JsonElement element) {
        int removed = 0;

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : new ArrayList<>(object.keySet())) {
                JsonElement child = object.get(key);
                if ("timeline".equals(key) && child != null && child.isJsonObject()) {
                    JsonObject timeline = child.getAsJsonObject();
                    removed += sanitizeTimeline(timeline);
                    if (timeline.isEmpty()) {
                        object.remove(key);
                    }
                } else if (child != null) {
                    removed += sanitizeElement(child);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                removed += sanitizeElement(child);
            }
        }

        return removed;
    }

    private static int sanitizeTimeline(JsonObject timeline) {
        int removed = 0;

        for (Map.Entry<String, JsonElement> entry : new ArrayList<>(timeline.entrySet())) {
            JsonElement value = entry.getValue();
            if (isStandaloneMythicCommand(value)) {
                timeline.remove(entry.getKey());
                removed++;
                continue;
            }

            if (!value.isJsonArray()) {
                continue;
            }

            JsonArray values = value.getAsJsonArray();
            for (int index = values.size() - 1; index >= 0; index--) {
                if (isStandaloneMythicCommand(values.get(index))) {
                    values.remove(index);
                    removed++;
                }
            }
            if (values.isEmpty()) {
                timeline.remove(entry.getKey());
            }
        }

        return removed;
    }

    private static boolean isStandaloneMythicCommand(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                && STANDALONE_MYTHIC_COMMAND.matcher(element.getAsString()).matches();
    }
}
