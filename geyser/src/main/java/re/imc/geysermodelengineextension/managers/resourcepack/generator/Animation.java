package re.imc.geysermodelengineextension.managers.resourcepack.generator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import re.imc.geysermodelengineextension.GeyserModelEngineExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class Animation {

    private String modelId;
    private JsonObject json;
    private Set<String> animationIds = new HashSet<>();

    private String path;

    public static final String HEAD_TEMPLATE = """
             {
               "relative_to" : {
                 "rotation" : "entity"
               },
               "rotation" : [ "query.target_x_rotation - this", "query.target_y_rotation - this", 0.0 ]
            }
            """;

    /** 隐身缩放下限：亚像素不可见但矩阵非奇异，防 Bedrock 冻结零尺寸实体导致隐身动画卡死、永不现身。 */
    private static final double INVIS_EPSILON = 0.01;

    public void load(String string) {
        this.json = JsonParser.parseString(string).getAsJsonObject();
        JsonObject newAnimations = new JsonObject();

        boolean bakeCatmullrom = GeyserModelEngineExtension.getExtension().getConfigManager()
                .getConfig().getBoolean("options.resource-pack.bake-catmullrom-to-linear", false);
        double sampleStep = GeyserModelEngineExtension.getExtension().getConfigManager()
                .getConfig().getDouble("options.resource-pack.catmullrom-sample-step");
        if (sampleStep <= 0) sampleStep = 0.05;

        for (Map.Entry<String, JsonElement> element : json.get("animations").getAsJsonObject().entrySet()) {
            animationIds.add(element.getKey());
            JsonObject animation = element.getValue().getAsJsonObject();

            if (animation.has("override_previous_animation")) {
                if (animation.get("override_previous_animation").getAsBoolean()) {
                    if (!animation.has("loop")) {
                        animation.addProperty("loop", "hold_on_last_frame");
                        // play once but override must use this to avoid strange anim
                    }
                }

                animation.remove("override_previous_animation");
            }

            if (animation.has("loop")) {
                if (animation.get("loop").getAsJsonPrimitive().isString()) {
                    if (animation.get("loop").getAsString().equals("hold_on_last_frame")) {
                        if (!animation.has("bones")) {
                            continue;
                        }
                        for (Map.Entry<String, JsonElement> bone : animation.get("bones").getAsJsonObject().entrySet()) {

                            for (Map.Entry<String, JsonElement> anim : bone.getValue().getAsJsonObject().entrySet()) {
                                float max = -1;
                                JsonObject end = null;
                                if (!anim.getValue().isJsonObject()) {
                                    continue;
                                }
                                try {
                                    for (Map.Entry<String, JsonElement> timeline : anim.getValue().getAsJsonObject().entrySet()) {
                                        float time = Float.parseFloat(timeline.getKey());
                                        if (time > max) {
                                            max = time;
                                            if (timeline.getValue().isJsonObject()) {
                                                end = timeline.getValue().getAsJsonObject();
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                if (end != null && end.has("lerp_mode") && end.get("lerp_mode").getAsString().equals("catmullrom")) {
                                    end.addProperty("lerp_mode", "linear");
                                }
                            }
                        }
                    }
                }
            }

            if (bakeCatmullrom) {
                bakeCatmullromToLinear(animation, sampleStep);
            }

            newAnimations.add("animation." + modelId + "." + element.getKey().replace(" ", "_"), element.getValue());
        }

        json.add("animations", newAnimations);
    }

    private void bakeCatmullromToLinear(JsonObject animation, double step) {
        if (!animation.has("bones")) return;

        boolean isLooping = false;
        if (animation.has("loop")) {
            JsonElement loopElem = animation.get("loop");
            if (loopElem.isJsonPrimitive() && loopElem.getAsJsonPrimitive().isBoolean()) {
                isLooping = loopElem.getAsBoolean();
            }
        }

        double maxTime = 0;
        if (animation.has("animation_length")) {
            maxTime = animation.get("animation_length").getAsDouble();
        }

        JsonObject bones = animation.get("bones").getAsJsonObject();
        for (Map.Entry<String, JsonElement> boneEntry : bones.entrySet()) {
            if (!boneEntry.getValue().isJsonObject()) continue;
            JsonObject bone = boneEntry.getValue().getAsJsonObject();

            for (String channelName : new String[]{"position", "rotation", "scale"}) {
                if (!bone.has(channelName)) continue;
                JsonElement channelElem = bone.get(channelName);
                if (!channelElem.isJsonObject()) continue;

                JsonObject channel = channelElem.getAsJsonObject();
                if (!hasCatmullromKeyframes(channel)) continue;

                List<double[]> keyframes = extractKeyframes(channel);
                if (keyframes == null) continue;

                if (keyframes.size() < 2) {
                    stripCatmullrom(channel);
                    continue;
                }

                double channelMaxTime = maxTime;
                if (channelMaxTime <= 0) {
                    channelMaxTime = keyframes.get(keyframes.size() - 1)[0];
                }
                if (channelMaxTime <= 0) continue;

                bone.add(channelName, bakeChannel(keyframes, channelMaxTime, isLooping, step));
            }
        }
    }

    private void stripCatmullrom(JsonObject channel) {
        for (Map.Entry<String, JsonElement> entry : channel.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                JsonObject kf = entry.getValue().getAsJsonObject();
                if (kf.has("lerp_mode") && "catmullrom".equals(kf.get("lerp_mode").getAsString())) {
                    double[] values = extractValues(kf);
                    if (values != null) {
                        JsonArray arr = new JsonArray();
                        arr.add(values[0]);
                        arr.add(values[1]);
                        arr.add(values[2]);
                        channel.add(entry.getKey(), arr);
                    } else {
                        kf.remove("lerp_mode");
                    }
                }
            }
        }
    }

    private boolean hasCatmullromKeyframes(JsonObject channel) {
        for (Map.Entry<String, JsonElement> entry : channel.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                JsonObject kf = entry.getValue().getAsJsonObject();
                if (kf.has("lerp_mode") && "catmullrom".equals(kf.get("lerp_mode").getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<double[]> extractKeyframes(JsonObject channel) {
        List<double[]> keyframes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : channel.entrySet()) {
            double time;
            try {
                time = Double.parseDouble(entry.getKey());
            } catch (NumberFormatException e) {
                return null;
            }

            double[] values = extractValues(entry.getValue());
            if (values == null) return null;

            keyframes.add(new double[]{time, values[0], values[1], values[2]});
        }
        keyframes.sort(Comparator.comparingDouble(a -> a[0]));
        return keyframes;
    }

    private double[] extractValues(JsonElement elem) {
        if (elem.isJsonArray()) {
            return jsonArrayToDoubles(elem.getAsJsonArray());
        }
        if (elem.isJsonObject()) {
            JsonObject obj = elem.getAsJsonObject();
            if (obj.has("post")) {
                return extractValues(obj.get("post"));
            }
            if (obj.has("vector")) {
                return extractValues(obj.get("vector"));
            }
        }
        if (elem.isJsonPrimitive()) {
            if (elem.getAsJsonPrimitive().isNumber()) {
                double v = elem.getAsDouble();
                return new double[]{v, v, v};
            }
            return null;
        }
        return null;
    }

    private double[] jsonArrayToDoubles(JsonArray arr) {
        if (arr.size() < 3) return null;
        double[] result = new double[3];
        for (int i = 0; i < 3; i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            result[i] = e.getAsDouble();
        }
        return result;
    }

    private JsonObject bakeChannel(List<double[]> keyframes, double maxTime, boolean isLooping, double step) {
        double firstTime = keyframes.get(0)[0];
        double lastTime = keyframes.get(keyframes.size() - 1)[0];

        boolean shouldWrap = isLooping && firstTime < 0.01 && Math.abs(lastTime - maxTime) < 0.01;

        List<double[]> samples = new ArrayList<>();
        for (double t = 0; t <= maxTime + 0.001; t += step) {
            double time = Math.min(t, maxTime);
            double[] interpolated;

            if (time <= firstTime) {
                interpolated = new double[]{keyframes.get(0)[1], keyframes.get(0)[2], keyframes.get(0)[3]};
            } else if (time >= lastTime) {
                interpolated = new double[]{keyframes.get(keyframes.size() - 1)[1], keyframes.get(keyframes.size() - 1)[2], keyframes.get(keyframes.size() - 1)[3]};
            } else {
                int p1Idx = 0;
                for (int i = 0; i < keyframes.size(); i++) {
                    if (keyframes.get(i)[0] <= time) {
                        p1Idx = i;
                    }
                }
                int p2Idx = p1Idx + 1;

                double segStart = keyframes.get(p1Idx)[0];
                double segEnd = keyframes.get(p2Idx)[0];
                double segLen = segEnd - segStart;
                double localT = segLen > 0 ? (time - segStart) / segLen : 0;

                double[] p0, p1, p2, p3;
                p1 = new double[]{keyframes.get(p1Idx)[1], keyframes.get(p1Idx)[2], keyframes.get(p1Idx)[3]};
                p2 = new double[]{keyframes.get(p2Idx)[1], keyframes.get(p2Idx)[2], keyframes.get(p2Idx)[3]};

                if (p1Idx > 0) {
                    p0 = new double[]{keyframes.get(p1Idx - 1)[1], keyframes.get(p1Idx - 1)[2], keyframes.get(p1Idx - 1)[3]};
                } else if (shouldWrap) {
                    int lastIdx = keyframes.size() - 1;
                    p0 = new double[]{keyframes.get(lastIdx)[1], keyframes.get(lastIdx)[2], keyframes.get(lastIdx)[3]};
                } else {
                    p0 = p1.clone();
                }

                if (p2Idx < keyframes.size() - 1) {
                    p3 = new double[]{keyframes.get(p2Idx + 1)[1], keyframes.get(p2Idx + 1)[2], keyframes.get(p2Idx + 1)[3]};
                } else if (shouldWrap) {
                    p3 = new double[]{keyframes.get(0)[1], keyframes.get(0)[2], keyframes.get(0)[3]};
                } else {
                    p3 = p2.clone();
                }

                interpolated = catmullromInterpolate(p0, p1, p2, p3, localT);
            }

            samples.add(new double[]{
                Math.round(time * 10000.0) / 10000.0,
                Math.round(interpolated[0] * 10000.0) / 10000.0,
                Math.round(interpolated[1] * 10000.0) / 10000.0,
                Math.round(interpolated[2] * 10000.0) / 10000.0
            });
        }

        double tolerance = 0.05;
        List<double[]> reduced = new ArrayList<>();
        reduced.add(samples.get(0));

        for (int i = 1; i < samples.size() - 1; i++) {
            double[] prev = reduced.get(reduced.size() - 1);
            double[] curr = samples.get(i);
            double[] next = samples.get(i + 1);

            double dt = next[0] - prev[0];
            if (dt <= 0) continue;
            double ratio = (curr[0] - prev[0]) / dt;

            boolean needed = false;
            for (int c = 1; c <= 3; c++) {
                double linear = prev[c] + (next[c] - prev[c]) * ratio;
                if (Math.abs(linear - curr[c]) > tolerance) {
                    needed = true;
                    break;
                }
            }
            if (needed) {
                reduced.add(curr);
            }
        }

        reduced.add(samples.get(samples.size() - 1));

        JsonObject result = new JsonObject();
        for (double[] s : reduced) {
            String timeKey = String.format("%.4f", s[0]).replaceAll("0+$", "").replaceAll("\\.$", "");
            JsonArray values = new JsonArray();
            values.add(s[1]);
            values.add(s[2]);
            values.add(s[3]);
            result.add(timeKey, values);
        }

        return result;
    }

    private double[] catmullromInterpolate(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double[] result = new double[3];
        for (int i = 0; i < 3; i++) {
            result[i] = 0.5 * (
                (2 * p1[i]) +
                (-p0[i] + p2[i]) * t +
                (2 * p0[i] - 5 * p1[i] + 4 * p2[i] - p3[i]) * t2 +
                (-p0[i] + 3 * p1[i] - 3 * p2[i] + p3[i]) * t3
            );
        }
        return result;
    }

    public void addHeadBind(Geometry geometry) {
        JsonObject object = new JsonObject();
        object.addProperty("loop", true);
        JsonObject bones = new JsonObject();
        JsonArray array = geometry.getInternal().get("bones").getAsJsonArray();

        int i = 0;

        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                String name = element.getAsJsonObject().get("name").getAsString();

                String parent = "";
                if (element.getAsJsonObject().has("parent")) parent = element.getAsJsonObject().get("parent").getAsString();
                if (parent.startsWith("h_") || parent.startsWith("hi_")) continue;

                if (name.startsWith("h_") || name.startsWith("hi_")) {
                    bones.add(name, JsonParser.parseString(HEAD_TEMPLATE));
                    i++;
                }
            }
        }

        if (i == 0) return;

        GeyserModelEngineExtension.getExtension().getResourcePackManager().getEntityCache().get(modelId).setHasHeadAnimation(true);

        object.add("bones", bones);
        json.get("animations").getAsJsonObject().add("animation." + modelId + ".look_at_target", object);
    }

    /**
     * 方案A 根治「留头」：给因 look_at_target 逐帧重锚旋转、从而脱离父级缩放继承的「头骨」，
     * 补写其祖先的 reaches-zero scale 通道，使身体缩 0 隐身时头骨（及其正常继承的子骨 eyes/nose）同步缩 0。
     *
     * 两道安全阀：
     *  (A) 只烤「逃逸骨」本身（内建模式 = 名为 "head" 的骨；自定义模式 = 拿到 look_at_target 模板的顶层 h_/hi_ 骨），
     *      不烤整棵几何子树 —— 逃逸骨不继承祖先缩放、给一次值即修好；其非逃逸后代正常继承一次。
     *      杜绝 naive「烤整子树」的 祖先^骨深 指数失真（magus 深度10 / creeper / hole_monster 回归）。
     *  (B) 只烤「会归零」的祖先通道（某关键帧三分量全为 0 = 真隐身）；跳过 molang 字符串、呼吸/充能等不触零缩放。
     *      自动避开 hole_monster 呼吸(~1.05) / creeper 充能(1→1.3) / magus 竖直挤压([0,1.45,0])。
     *
     * 时序约束：必须在 addHeadBind 之后、writeString 之前调用（ResourcePackManager:114）——此处唯一同时握有
     * 动画 scale 通道（this.json）与骨骼层级（入参 geometry）。此刻 geometry.modify()（RPM:136）尚未执行，
     * getBones() 为空、bones 未小写化，故须自建父子表并统一 toLowerCase(Locale.ROOT)。
     */
    public void bakeAncestorScaleToEscapeBones(Geometry geometry) {
        if (json == null || !json.has("animations")) return;
        JsonElement bonesElem = geometry.getInternal().get("bones");
        if (bonesElem == null || !bonesElem.isJsonArray()) return;
        JsonArray rawBones = bonesElem.getAsJsonArray();

        // (1) 自建父子表（小写）—— 供沿父链走「会归零的祖先」
        Map<String, String> parentOf = new HashMap<>();
        for (JsonElement e : rawBones) {
            if (!e.isJsonObject()) continue;
            JsonObject b = e.getAsJsonObject();
            if (!b.has("name")) continue;
            String name = b.get("name").getAsString().toLowerCase(Locale.ROOT);
            String parent = b.has("parent") ? b.get("parent").getAsString().toLowerCase(Locale.ROOT) : null;
            parentOf.put(name, parent);
        }

        // (2) 逃逸骨集 —— 与 addHeadBind / neutralizeHeadRotation 严格同判据
        Set<String> escapeBones = computeEscapeBones(rawBones);
        if (escapeBones.isEmpty()) return;

        // (3) 逐动画：给每个逃逸骨补其「会归零的祖先」scale
        JsonObject animations = json.get("animations").getAsJsonObject();
        for (Map.Entry<String, JsonElement> a : animations.entrySet()) {
            if (a.getKey().endsWith(".look_at_target")) continue;     // 跳过自定义 look_at_target（纯 rotation 通道）
            JsonElement av = a.getValue();
            if (!av.isJsonObject() || !av.getAsJsonObject().has("bones")) continue;
            JsonElement animBonesElem = av.getAsJsonObject().get("bones");
            if (!animBonesElem.isJsonObject()) continue;
            JsonObject animBones = animBonesElem.getAsJsonObject();

            // 动画骨键 -> 原键（大小写无关）
            Map<String, String> keyOf = new HashMap<>();
            for (String k : animBones.keySet()) keyOf.put(k.toLowerCase(Locale.ROOT), k);

            for (String bone : escapeBones) {
                // 沿父链收集「越过逃逸集、且会归零」的祖先 scale 通道（本模型恒 = {waist}）
                List<JsonElement> zeroAncestors = new ArrayList<>();
                for (String cur = parentOf.get(bone); cur != null; cur = parentOf.get(cur)) {
                    if (escapeBones.contains(cur)) continue;
                    String ak = keyOf.get(cur);
                    if (ak == null) continue;
                    JsonElement ab = animBones.get(ak);
                    if (ab == null || !ab.isJsonObject()) continue;
                    JsonObject abo = ab.getAsJsonObject();
                    if (abo.has("scale") && reachesZeroScale(abo.get("scale"))) {
                        zeroAncestors.add(abo.get("scale"));
                    }
                }
                if (zeroAncestors.isEmpty()) continue;                // 本动画无隐身缩放：不碰（walk/idle/攻击等）

                // 目标骨对象（本动画若缺该骨则新建）
                String bk = keyOf.get(bone);
                JsonObject boneObj;
                if (bk != null) {
                    JsonElement be = animBones.get(bk);
                    if (!be.isJsonObject()) continue;
                    boneObj = be.getAsJsonObject();
                } else {
                    boneObj = new JsonObject();
                    animBones.add(bone, boneObj);
                    keyOf.put(bone, bone);
                }
                // 恒等 own scale（[1,1,1] / 裸标量 1）视同无 → 走 deepcopy 逐字节保真；只有真正非平凡的自有 scale 才参与相乘
                JsonElement ownRaw = boneObj.has("scale") ? boneObj.get("scale") : null;
                JsonElement ownScale = (ownRaw != null && isNonTrivialScale(ownRaw)) ? ownRaw : null;

                if (zeroAncestors.size() == 1 && ownScale == null) {
                    // 快路径：原样深拷贝，pre/post/step/lerp_mode 逐字节保真（illusioner 唯一路径）
                    boneObj.add("scale", JsonParser.parseString(zeroAncestors.get(0).toString()));
                } else {
                    // 合并路径：多祖先或逃逸骨已有自有 scale —— 逐时间点相乘（本模型不触发）
                    List<JsonElement> all = new ArrayList<>(zeroAncestors);
                    if (ownScale != null) all.add(ownScale);
                    boneObj.add("scale", mergeScalesByMultiply(all));
                }
            }
        }
    }

    /**
     * 「逃逸骨」判据（单一真源，供 bakeAncestorScaleToEscapeBones / neutralizeHeadRotation 复用）：
     * ME 判定为 Head 型、在 Java 端由 runtime（HeadImpl + BodyRotationController）覆盖旋转的顶层头骨。
     *  - 存在 h_/hi_ 头骨时：取顶层 h_/hi_（父不以 h_/hi_ 开头）—— 与 addHeadBind 的绑定判据一致；
     *  - 否则若存在内建 "head"：取 "head" —— 内建 animation.common.look_at_target 硬编码驱动之。
     * 返回全小写骨名集。geometry.modify() 尚未执行（getBones() 为空、未小写化），故此处按原始 bones 数组自建。
     */
    private Set<String> computeEscapeBones(JsonArray rawBones) {
        Map<String, String> parentOf = new HashMap<>();
        boolean hasHiBones = false;
        for (JsonElement e : rawBones) {
            if (!e.isJsonObject()) continue;
            JsonObject b = e.getAsJsonObject();
            if (!b.has("name")) continue;
            String name = b.get("name").getAsString().toLowerCase(Locale.ROOT);
            String parent = b.has("parent") ? b.get("parent").getAsString().toLowerCase(Locale.ROOT) : null;
            parentOf.put(name, parent);
            if (name.startsWith("h_") || name.startsWith("hi_")) hasHiBones = true;
        }

        Set<String> escapeBones = new HashSet<>();
        if (hasHiBones) {
            for (Map.Entry<String, String> en : parentOf.entrySet()) {
                String n = en.getKey(), p = en.getValue();
                boolean isHead = n.startsWith("h_") || n.startsWith("hi_");
                boolean parentIsHead = p != null && (p.startsWith("h_") || p.startsWith("hi_"));
                if (isHead && !parentIsHead) escapeBones.add(n);      // 只有顶层头骨（子 h_ 骨正常继承）
            }
        } else if (parentOf.containsKey("head")) {
            escapeBones.add("head");                                   // 内建 look_at_target 硬编码驱动 "head"
        }
        return escapeBones;
    }

    /**
     * 通用锁头（复刻 Java {@code BodyRotation{maxhead=0}}）：把「逃逸头骨」在所有 state 动画里的
     * {@code rotation} 通道删除 —— head 骨遂无自有旋转 → 继承父骨（body）→ 与 body 对齐、随身面向玩家。
     *
     * 为何等价 Java：ME 的 Head 型骨在 Java 端**总是**由 runtime 头逻辑驱动、忽略/覆盖动画烘焙的 head 旋转，
     * 故烘焙的斜头 pose 在 Java 从不显示；基岩无此 runtime，烘焙 pose 会裸露成「头身固定夹角」。压平的骨集
     * 与 ME 判定的 Head 型骨集（{@link #computeEscapeBones}）一致 → 双端都「head 骨不由动画驱动」→ 精确对齐。
     *
     * 通道隔离：**只删 rotation**，保留 scale（留头 bake 与 ε-floor 的隐身缩放）与 position → 与前两阶段零冲突。
     * 跳过 {@code .look_at_target} 动画本体（其为纯头追踪 rotation；Entity 侧配套不再注入 → 无副作用）。
     * 无逃逸骨（纯 VFX 模型 kunai/fangs/vex 等）→ 集空 → no-op。
     * 时序：与 addHeadBind / bake / floor 同段调用（ResourcePackManager 生成循环），受全局 lock-head-to-body 门控。
     */
    public void neutralizeHeadRotation(Geometry geometry) {
        if (json == null || !json.has("animations")) return;
        JsonElement bonesElem = geometry.getInternal().get("bones");
        if (bonesElem == null || !bonesElem.isJsonArray()) return;
        Set<String> escapeBones = computeEscapeBones(bonesElem.getAsJsonArray());
        if (escapeBones.isEmpty()) return;

        JsonObject animations = json.get("animations").getAsJsonObject();
        for (Map.Entry<String, JsonElement> a : animations.entrySet()) {
            if (a.getKey().endsWith(".look_at_target")) continue;     // 头追踪动画本体，Entity 侧已不注入
            JsonElement av = a.getValue();
            if (!av.isJsonObject() || !av.getAsJsonObject().has("bones")) continue;
            JsonElement animBonesElem = av.getAsJsonObject().get("bones");
            if (!animBonesElem.isJsonObject()) continue;
            JsonObject animBones = animBonesElem.getAsJsonObject();

            for (String k : new ArrayList<>(animBones.keySet())) {
                if (!escapeBones.contains(k.toLowerCase(Locale.ROOT))) continue;
                JsonElement be = animBones.get(k);
                if (!be.isJsonObject()) continue;
                be.getAsJsonObject().remove("rotation");              // 只压平 rotation，scale/position 原样保留
            }
        }
    }

    /**
     * 把所有动画 scale 通道里的「全零三元组」抬到 ε（{@link #INVIS_EPSILON}），
     * 防 Bedrock 冻结零尺寸(奇异矩阵)实体导致隐身动画时间线与控制器求值全部卡死、本体永不现身。
     * 只动全零三元组；[0,1.45,0] 这类偏零（扁平骨/竖直挤压）不碰，零误伤。
     *
     * 时序：必须在 {@link #bakeAncestorScaleToEscapeBones} 之后调用 —— bake 先把逃逸头骨也塌成 [0,0,0]，
     * 本方法再把 waist 与 head 的 [0,0,0] 一起抬到 [ε,ε,ε]（仍逐字节相等），既保留「无留头」又恢复现身。
     */
    public void floorZeroScalesToEpsilon() {
        if (json == null || !json.has("animations")) return;
        JsonObject animations = json.get("animations").getAsJsonObject();
        for (Map.Entry<String, JsonElement> a : animations.entrySet()) {
            JsonElement av = a.getValue();
            if (!av.isJsonObject() || !av.getAsJsonObject().has("bones")) continue;
            JsonElement bonesElem = av.getAsJsonObject().get("bones");
            if (!bonesElem.isJsonObject()) continue;
            JsonObject bones = bonesElem.getAsJsonObject();
            for (Map.Entry<String, JsonElement> b : bones.entrySet()) {
                if (!b.getValue().isJsonObject()) continue;
                JsonObject bd = b.getValue().getAsJsonObject();
                if (bd.has("scale")) bd.add("scale", flooredScale(bd.get("scale")));
            }
        }
    }

    /** 递归把 scale 元素里的全零三元组抬到 ε：数组[0,0,0]→[ε,ε,ε]；{pre/post}逐支；时间键对象逐键；裸标量 0→ε。其它原样返回。 */
    private JsonElement flooredScale(JsonElement s) {
        if (s.isJsonPrimitive() && s.getAsJsonPrimitive().isNumber())
            return s.getAsDouble() == 0.0 ? new JsonPrimitive(INVIS_EPSILON) : s;
        if (s.isJsonArray()) {
            JsonArray arr = s.getAsJsonArray();
            boolean allZero = arr.size() > 0;
            for (JsonElement e : arr) if (!(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsDouble() == 0.0)) { allZero = false; break; }
            if (allZero) { JsonArray e = new JsonArray(); e.add(INVIS_EPSILON); e.add(INVIS_EPSILON); e.add(INVIS_EPSILON); return e; }
            return s;
        }
        if (s.isJsonObject()) {
            JsonObject o = s.getAsJsonObject();
            if (o.has("pre") || o.has("post")) {
                if (o.has("pre"))  o.add("pre",  flooredScale(o.get("pre")));
                if (o.has("post")) o.add("post", flooredScale(o.get("post")));
            } else {
                for (String k : new ArrayList<>(o.keySet())) o.add(k, flooredScale(o.get(k)));
            }
            return o;
        }
        return s;
    }

    /** scale 通道是否非平凡（存在 ≠[1,1,1] 的关键帧）。裸标量 1 / 恒等数组视为平凡；molang 字符串无法证平凡 → 非平凡。 */
    private boolean isNonTrivialScale(JsonElement scale) {
        if (scale == null) return false;
        if (scale.isJsonPrimitive()) {
            if (scale.getAsJsonPrimitive().isNumber()) return scale.getAsDouble() != 1.0;
            return true;                                              // molang 字符串
        }
        if (scale.isJsonArray()) {
            double[] v = jsonArrayToDoubles(scale.getAsJsonArray());
            return v == null || v[0] != 1.0 || v[1] != 1.0 || v[2] != 1.0;
        }
        if (scale.isJsonObject()) {
            for (Map.Entry<String, JsonElement> kf : scale.getAsJsonObject().entrySet()) {
                double[] v = extractValues(kf.getValue());
                if (v == null || v[0] != 1.0 || v[1] != 1.0 || v[2] != 1.0) return true;
            }
            return false;
        }
        return true;
    }

    /** scale 通道是否在某关键帧三分量全为 0（真隐身）。molang 字符串无法静态证零 → 视为非零（不烤）。 */
    private boolean reachesZeroScale(JsonElement scale) {
        if (scale == null) return false;
        if (scale.isJsonArray()) return isZeroTriplet(jsonArrayToDoubles(scale.getAsJsonArray()));
        if (scale.isJsonObject()) {
            for (Map.Entry<String, JsonElement> kf : scale.getAsJsonObject().entrySet()) {
                if (keyframeHasZero(kf.getValue())) return true;
            }
        }
        return false;
    }

    /** 单个关键帧值是否含全零三元组（数组 / 数字 / {pre,post,vector} 递归；molang 字符串 → false）。 */
    private boolean keyframeHasZero(JsonElement v) {
        if (v == null) return false;
        if (v.isJsonArray()) return isZeroTriplet(jsonArrayToDoubles(v.getAsJsonArray()));
        if (v.isJsonPrimitive()) {
            return v.getAsJsonPrimitive().isNumber() && v.getAsDouble() == 0.0;   // molang 字符串 → false
        }
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            return (o.has("pre") && keyframeHasZero(o.get("pre")))
                || (o.has("post") && keyframeHasZero(o.get("post")))
                || (o.has("vector") && keyframeHasZero(o.get("vector")));
        }
        return false;
    }

    private boolean isZeroTriplet(double[] v) {
        return v != null && v[0] == 0.0 && v[1] == 0.0 && v[2] == 0.0;
    }

    /** 多个 reaches-zero 祖先 / 逃逸骨自有 scale：逐时间点相乘，输出 linear 数组关键帧（罕见路径，本模型不触发）。 */
    private JsonObject mergeScalesByMultiply(List<JsonElement> channels) {
        TreeSet<Double> times = new TreeSet<>();
        for (JsonElement ch : channels) {
            if (ch.isJsonObject()) {
                for (String k : ch.getAsJsonObject().keySet()) {
                    try { times.add(Double.parseDouble(k)); } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (times.isEmpty()) times.add(0.0);

        JsonObject result = new JsonObject();
        for (double t : times) {
            double x = 1, y = 1, z = 1;
            for (JsonElement ch : channels) {
                double[] v = sampleScaleAt(ch, t);
                x *= v[0]; y *= v[1]; z *= v[2];
            }
            String timeKey = String.format(Locale.ROOT, "%.4f", t).replaceAll("0+$", "").replaceAll("\\.$", "");
            JsonArray arr = new JsonArray();
            arr.add(x); arr.add(y); arr.add(z);
            result.add(timeKey.isEmpty() ? "0" : timeKey, arr);
        }
        return result;
    }

    /** 采样 scale 通道在时间 t 的值（step 语义：取 ≤t 的最近关键帧；无则取首帧；对象取 post）。 */
    private double[] sampleScaleAt(JsonElement scale, double t) {
        if (scale.isJsonArray()) {
            double[] v = jsonArrayToDoubles(scale.getAsJsonArray());
            return v != null ? v : new double[]{1, 1, 1};
        }
        if (scale.isJsonObject()) {
            double bestT = Double.NEGATIVE_INFINITY; JsonElement bestV = null;
            double firstT = Double.POSITIVE_INFINITY; JsonElement firstV = null;
            for (Map.Entry<String, JsonElement> kf : scale.getAsJsonObject().entrySet()) {
                double kt;
                try { kt = Double.parseDouble(kf.getKey()); } catch (NumberFormatException e) { continue; }
                if (kt < firstT) { firstT = kt; firstV = kf.getValue(); }
                if (kt <= t && kt >= bestT) { bestT = kt; bestV = kf.getValue(); }
            }
            JsonElement chosen = bestV != null ? bestV : firstV;
            if (chosen == null) return new double[]{1, 1, 1};
            double[] v = extractValues(chosen);
            return v != null ? v : new double[]{1, 1, 1};
        }
        return new double[]{1, 1, 1};
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public void setJson(JsonObject json) {
        this.json = json;
    }

    public void setAnimationIds(Set<String> animationIds) {
        this.animationIds = animationIds;
    }

    public String getModelId() {
        return modelId;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public JsonObject getJson() {
        return json;
    }

    public Set<String> getAnimationIds() {
        return animationIds;
    }

    public String getPath() {
        return path;
    }
}
