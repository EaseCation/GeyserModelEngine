package re.imc.geysermodelengine.managers.model.modelhandler;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import re.imc.geysermodelengine.GeyserModelEngine;
import re.imc.geysermodelengine.listener.ModelEngineListener;
import re.imc.geysermodelengine.managers.model.entity.EntityData;
import re.imc.geysermodelengine.managers.model.entity.ModelEngineEntityData;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ModelEngineHandler implements ModelHandler {

    //TODO move driver hashmap here

    private final GeyserModelEngine plugin;

    public ModelEngineHandler(GeyserModelEngine plugin) {
        this.plugin = plugin;
    }

    @Override
    public void createModel(Object... objects) {
        ModeledEntity megEntity = (ModeledEntity) objects[0];
        ActiveModel megActiveModel = (ActiveModel) objects[1];

        int entityID = megEntity.getBase().getEntityId();
        String blueprintName = megActiveModel.getBlueprint().getName();

        // 核心①：判重 → 刷新/构造 全程在 entities.compute(entityID,…) 内，按 base 实体串行（CHM 每键 bin 锁）。
        // 结构上幂等：同 (entityID, blueprintName) 至多一条 EntityData。~onSpawn/~onLoad 的并发双挂载
        // 第二次落入 refresh 分支复用同一载体，绝不再造第二只孤儿（傀儡+留头本因）。构造只发生在确定要插入的
        // else 分支内，避免「先构造启动任务、再因重复丢弃」造成的孤儿发包任务泄漏。
        plugin.getModelManager().getEntities().compute(entityID, (id, bucket) -> {
            if (bucket == null) bucket = new ConcurrentHashMap<>();
            EntityData existing = bucket.get(blueprintName);
            if (existing != null) {
                ModelEngineEntityData d = (ModelEngineEntityData) existing;
                if (d.getActiveModel() == megActiveModel) return bucket; // 同一 ActiveModel 的完全重复事件，忽略
                // 不同实例：同一 base 实体重复挂载（ModelEngine 为它建了新的 ModeledEntity/ActiveModel，旧的随后被销毁）。
                // 刷新指向最新活实例，复用同一 PacketEntity 与定时任务，避免一直跟踪已销毁/静止实例导致基岩端动画不同步。
                d.updateModel(megEntity, megActiveModel);
                if (plugin.getConfigManager().getConfig().getBoolean("options.debug.spawn")) plugin.getLogger().info("Refreshed " + blueprintName + " to latest ActiveModel instance");
                return bucket;
            }
            bucket.put(blueprintName, new ModelEngineEntityData(plugin, megEntity, megActiveModel));
            if (plugin.getConfigManager().getConfig().getBoolean("options.debug.spawn")) plugin.getLogger().info("Creating model for " + blueprintName);
            return bucket;
        });
    }

    @Override
    public void processEntities(Entity entity) {
        if (plugin.getModelManager().getEntities().containsKey(entity.getEntityId())) return;

        ModeledEntity modeledEntity = ModelEngineAPI.getModeledEntity(entity);
        if (modeledEntity == null) return;

        Optional<ActiveModel> model = modeledEntity.getModels().values().stream().findFirst();
        model.ifPresent(m -> createModel(modeledEntity, m));
    }

    // TODO ModelEngine canSee impl

    @Override
    public void loadListeners() {
        Bukkit.getPluginManager().registerEvents(new ModelEngineListener(plugin), plugin);
    }
}
