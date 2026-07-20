package re.imc.geysermodelengineextension.managers.resourcepack.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Static ModelEngine head semantics extracted from one Bedrock geometry.
 *
 * <p>Only ModelEngine's {@code h_}/{@code hi_} behavior prefixes create a
 * programmable head. Names which merely look like heads are reported for
 * review, but never gain behavior implicitly.</p>
 */
public final class HeadModelProfile {

    private final Map<String, String> parentByBone;
    private final List<String> modelEngineHeadBones;
    private final List<String> headAnchors;
    private final List<String> nestedHeadBones;
    private final List<String> plainHeadBones;
    private final List<String> caseVariantHeadBones;
    private final List<String> nonHeadNamedAnchors;
    private final List<String> mountBones;

    private HeadModelProfile(
            Map<String, String> parentByBone,
            List<String> modelEngineHeadBones,
            List<String> headAnchors,
            List<String> nestedHeadBones,
            List<String> plainHeadBones,
            List<String> caseVariantHeadBones,
            List<String> nonHeadNamedAnchors,
            List<String> mountBones
    ) {
        this.parentByBone = Map.copyOf(parentByBone);
        this.modelEngineHeadBones = List.copyOf(modelEngineHeadBones);
        this.headAnchors = List.copyOf(headAnchors);
        this.nestedHeadBones = List.copyOf(nestedHeadBones);
        this.plainHeadBones = List.copyOf(plainHeadBones);
        this.caseVariantHeadBones = List.copyOf(caseVariantHeadBones);
        this.nonHeadNamedAnchors = List.copyOf(nonHeadNamedAnchors);
        this.mountBones = List.copyOf(mountBones);
    }

    public static HeadModelProfile analyze(Geometry geometry) {
        JsonElement bonesElement = geometry.getInternal().get("bones");
        if (bonesElement == null || !bonesElement.isJsonArray()) {
            throw invalid(geometry, "geometry is missing a bones array");
        }

        JsonArray bones = bonesElement.getAsJsonArray();
        Map<String, String> parentByBone = new LinkedHashMap<>();
        Set<String> formalHeads = new TreeSet<>();
        Set<String> plainHeads = new TreeSet<>();
        Set<String> caseVariantHeads = new TreeSet<>();
        Set<String> mounts = new TreeSet<>();

        for (JsonElement element : bones) {
            if (!element.isJsonObject()) {
                throw invalid(geometry, "bones array contains a non-object entry");
            }
            JsonObject bone = element.getAsJsonObject();
            if (!bone.has("name") || !bone.get("name").isJsonPrimitive()) {
                throw invalid(geometry, "bone is missing a string name");
            }

            String rawSourceName = bone.get("name").getAsString();
            String name = normalize(rawSourceName);
            if (name.isEmpty()) {
                throw invalid(geometry, "bone name is empty");
            }
            if (parentByBone.containsKey(name)) {
                throw invalid(geometry, "duplicate bone name after case normalization: " + name);
            }

            String parent = null;
            if (bone.has("parent")) {
                if (!bone.get("parent").isJsonPrimitive()) {
                    throw invalid(geometry, "bone " + name + " has a non-string parent");
                }
                parent = normalize(bone.get("parent").getAsString());
                if (parent.isEmpty()) {
                    throw invalid(geometry, "bone " + name + " has an empty parent");
                }
            }
            parentByBone.put(name, parent);

            if (isModelEngineHead(rawSourceName)) {
                formalHeads.add(name);
            } else if (isModelEngineHead(name)) {
                caseVariantHeads.add(name);
            } else if (name.contains("head")) {
                plainHeads.add(name);
            }
            if (name.equals("mount")) {
                mounts.add(name);
            }
        }

        validateHierarchy(geometry, parentByBone);

        Set<String> anchors = new TreeSet<>();
        Set<String> nested = new TreeSet<>();
        for (String head : formalHeads) {
            if (hasFormalHeadAncestor(head, formalHeads, parentByBone)) {
                nested.add(head);
            } else {
                anchors.add(head);
            }
        }

        Set<String> nonHeadNamed = new TreeSet<>();
        for (String anchor : anchors) {
            String behaviorTarget = anchor.startsWith("hi_") ? anchor.substring(3) : anchor.substring(2);
            if (!behaviorTarget.contains("head")) {
                nonHeadNamed.add(anchor);
            }
        }

        return new HeadModelProfile(
                parentByBone,
                List.copyOf(formalHeads),
                List.copyOf(anchors),
                List.copyOf(nested),
                List.copyOf(plainHeads),
                List.copyOf(caseVariantHeads),
                List.copyOf(nonHeadNamed),
                List.copyOf(mounts));
    }

    private static void validateHierarchy(Geometry geometry, Map<String, String> parentByBone) {
        for (Map.Entry<String, String> bone : parentByBone.entrySet()) {
            if (bone.getValue() != null && !parentByBone.containsKey(bone.getValue())) {
                throw invalid(geometry,
                        "bone " + bone.getKey() + " references missing parent " + bone.getValue());
            }
        }

        for (String start : parentByBone.keySet()) {
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null) {
                if (!path.add(current)) {
                    throw invalid(geometry, "bone hierarchy contains a cycle involving " + current);
                }
                current = parentByBone.get(current);
            }
        }
    }

    private static boolean hasFormalHeadAncestor(
            String bone,
            Set<String> formalHeads,
            Map<String, String> parentByBone
    ) {
        for (String parent = parentByBone.get(bone); parent != null; parent = parentByBone.get(parent)) {
            if (formalHeads.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isModelEngineHead(String name) {
        return name.startsWith("h_") || name.startsWith("hi_");
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException invalid(Geometry geometry, String message) {
        String modelId = geometry.getModelId() == null ? "<unknown>" : geometry.getModelId();
        return new IllegalArgumentException("Invalid geometry hierarchy for model " + modelId + ": " + message);
    }

    public String parentOf(String bone) {
        return parentByBone.get(normalize(bone));
    }

    public List<String> modelEngineHeadBones() {
        return modelEngineHeadBones;
    }

    public List<String> headAnchors() {
        return headAnchors;
    }

    public List<String> nestedHeadBones() {
        return nestedHeadBones;
    }

    public List<String> plainHeadBones() {
        return plainHeadBones;
    }

    public List<String> caseVariantHeadBones() {
        return caseVariantHeadBones;
    }

    public List<String> nonHeadNamedAnchors() {
        return nonHeadNamedAnchors;
    }

    public List<String> mountBones() {
        return mountBones;
    }
}
