package re.imc.geysermodelengineextension.managers.resourcepack.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.data.BoneData;
import re.imc.geysermodelengineextension.managers.resourcepack.generator.data.TextureData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderControllerTest {

    @Test
    void uvBindingBonesReuseParentBitWhenParentSortsAfterUvPrefix() {
        Map<String, BoneData> bones = new HashMap<>();
        bone(bones, "base", null);
        bone(bones, "ring", null);
        bone(bones, "uv_ring0", "ring");
        bone(bones, "west_pier", null);
        bone(bones, "uv_west_pier0", "west_pier");

        Map<String, String> visibility = partVisibility(bones, Set.of("uv_ring0", "uv_west_pier0"));

        assertEquals(bit(1), visibility.get("ring"));
        assertEquals(bit(1), visibility.get("uv_ring0"));
        assertEquals(bit(2), visibility.get("west_pier"));
        assertEquals(bit(2), visibility.get("uv_west_pier0"));
    }

    @Test
    void uvBindingBonesStripSortPrefixesBeforeLookingUpParent() {
        Map<String, BoneData> bones = new HashMap<>();
        bone(bones, "arm", null);
        bone(bones, "p_zeta", null);
        bone(bones, "uv_p_zeta0", "p_zeta");

        Map<String, String> visibility = partVisibility(bones, Set.of("uv_p_zeta0"));

        assertEquals(bit(1), visibility.get("p_zeta"));
        assertEquals(bit(1), visibility.get("uv_p_zeta0"));
    }

    private static Map<String, String> partVisibility(Map<String, BoneData> bones, Set<String> uvBones) {
        ModelConfig config = new ModelConfig();
        config.setDisablePartVisibility(false);
        config.setPerTextureUvSize(Map.of("atlas", new Integer[]{256, 256}));
        config.setBingingBones(Map.of("atlas", uvBones));

        Entity entity = new Entity("test");
        entity.setModelConfig(config);
        entity.setTextureMap(Map.of("atlas", new TextureData("test", "", uvBones, new byte[0])));

        JsonObject root = JsonParser.parseString(new RenderController("test", bones, entity)
                .generate("modelengine", false)).getAsJsonObject();
        JsonArray items = root.getAsJsonObject("render_controllers")
                .getAsJsonObject("controller.render.meg_test_atlas")
                .getAsJsonArray("part_visibility");
        Map<String, String> visibility = new HashMap<>();
        for (JsonElement item : items) {
            item.getAsJsonObject().entrySet().forEach(entry -> visibility.put(entry.getKey(), entry.getValue().getAsString()));
        }
        return visibility;
    }

    private static void bone(Map<String, BoneData> bones, String name, String parent) {
        BoneData bone = new BoneData(name, parent, new HashSet<>(), new HashSet<>());
        bones.put(name, bone);
        if (parent != null) {
            bones.get(parent).getChildren().add(bone);
            bones.get(parent).getAllChildren().add(bone);
        }
    }

    private static String bit(int index) {
        return "math.mod(math.floor(query.property('modelengine:bone0') / " + (1 << index) + "), 2) == 1";
    }
}
