package re.imc.geysermodelengine.managers.model.modelhandler;

import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.entity.BaseEntity;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.Tracker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import re.imc.geysermodelengine.GeyserModelEngine;
import re.imc.geysermodelengine.listener.BetterModelListener;
import re.imc.geysermodelengine.managers.model.entity.BetterModelEntityData;
import re.imc.geysermodelengine.managers.model.entity.EntityData;

import java.util.concurrent.ConcurrentHashMap;

public class BetterModelHandler implements ModelHandler {

    private final GeyserModelEngine plugin;

    public BetterModelHandler(GeyserModelEngine plugin) {
        this.plugin = plugin;
    }

    @Override
    public void createModel(Object... objects) {
        EntityTracker entityTracker = (EntityTracker) objects[0];
        BaseEntity entitySource = entityTracker.sourceEntity();

        int entityID = entitySource.id();
        String blueprintName = entityTracker.name();

        // 核心①（对称 MEG 侧）：判重 → 刷新/构造 全程在 entities.compute(entityID,…) 内，按 base 实体串行。
        // H2：原实现「先 new BetterModelEntityData(启动定时任务) 再判重、重复时裸 return」= 泄漏孤儿发包任务；
        // 此处把判重前移进 compute、构造之前，命中同名不同 tracker 时做等价 refresh（复用同一载体），绝不裸 return。
        plugin.getModelManager().getEntities().compute(entityID, (id, bucket) -> {
            if (bucket == null) bucket = new ConcurrentHashMap<>();
            EntityData existing = bucket.get(blueprintName);
            if (existing != null) {
                BetterModelEntityData d = (BetterModelEntityData) existing;
                if (d.getEntityTracker() == entityTracker) return bucket; // 同一 tracker 的完全重复事件，忽略
                // 不同实例：同一 base 实体重复挂载同名蓝图（BetterModel 建了新 tracker，旧的随后 forRemoval）。
                // 刷新指向最新活实例，复用同一 PacketEntity 与定时任务，避免一直跟踪已废弃 tracker 导致基岩端动画不同步。
                d.updateTracker(entitySource, entityTracker);
                if (plugin.getConfigManager().getConfig().getBoolean("options.debug.spawn")) plugin.getLogger().info("Refreshed " + blueprintName + " to latest tracker instance");
                return bucket;
            }
            bucket.put(blueprintName, new BetterModelEntityData(plugin, entitySource, entityTracker));
            if (plugin.getConfigManager().getConfig().getBoolean("options.debug.spawn")) plugin.getLogger().info("Creating model for " + blueprintName);
            return bucket;
        });
    }

    @Override
    public void processEntities(Entity entity) {
//        if (plugin.getModelManager().getEntities().containsKey(entity.getEntityId())) return;
//
//        @NotNull Optional<EntityTrackerRegistry> modeledEntity = BetterModel.registry(entity);
//
//        modeledEntity.ifPresent(m -> createModel(modeledEntity.get().entity(), m.));
    }

    @Override
    public void loadListeners() {
        Bukkit.getPluginManager().registerEvents(new BetterModelListener(plugin), plugin);
    }

    @Override
    public boolean canSee(Player player, Object model) {
        Tracker tracker = (Tracker) model;
        return !tracker.getPipeline().isHide(BukkitAdapter.adapt(player));
    }
}
