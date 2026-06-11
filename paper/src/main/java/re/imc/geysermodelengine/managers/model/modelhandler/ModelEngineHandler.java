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
import re.imc.geysermodelengine.managers.model.model.Model;
import re.imc.geysermodelengine.managers.model.model.ModelEngineModel;
import re.imc.geysermodelengine.managers.model.propertyhandler.PropertyHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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

        Map<Model, EntityData> entityDataCache = plugin.getModelManager().getEntitiesCache().computeIfAbsent(entityID, k -> new HashMap<>());

        // 去重必须在构造 EntityData 之前。ModelEngineEntityData 的构造函数会立刻启动 ModelEngineTaskHandler
        // （20ms 定时任务 + 一个随机 ID 的 PacketEntity，开始向基岩端发生成包）。若先构造、再因重复而 return，
        // 这个被丢弃的 EntityData 不会进入 entitiesCache，但它的任务仍永久运行——向基岩端发出一个不在缓存里的
        // 「孤儿」发包实体；而动画属性只由 UpdateTaskRunnable 遍历 entitiesCache 下发，孤儿收不到 → 基岩端表现为
        // 第二个「贴了模型却不播放动画」的克隆体（Java 端不受影响，PacketEntity 仅发给基岩玩家）。
        // 同一实体被重复挂载同名蓝图时（如 Adyeshach refreshModelEngine + 逐玩家可见性 show 触发多次 AddModelEvent）
        // 即会命中此路径，故先判重、确认不是重复后再构造。
        for (Model existing : entityDataCache.keySet()) {
            if (existing.getName().equals(blueprintName)) {
                return;
            }
        }

        PropertyHandler propertyHandler = plugin.getEntityTaskManager().getPropertyHandler();
        EntityData entityData = new ModelEngineEntityData(plugin, megEntity, megActiveModel);
        Model model = new ModelEngineModel(megActiveModel, this, entityData, propertyHandler);

        plugin.getModelManager().getModelEntitiesCache().put(entityID, model);
        entityDataCache.put(model, entityData);

        if (plugin.getConfigManager().getConfig().getBoolean("options.debug.spawn")) plugin.getLogger().info("Creating model for " + model.getName());
    }

    @Override
    public void processEntities(Entity entity) {
        if (plugin.getModelManager().getEntitiesCache().containsKey(entity.getEntityId())) return;

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
