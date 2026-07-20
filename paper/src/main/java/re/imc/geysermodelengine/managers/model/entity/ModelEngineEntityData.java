package re.imc.geysermodelengine.managers.model.entity;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.google.common.collect.Sets;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import re.imc.geysermodelengine.GeyserModelEngine;
import re.imc.geysermodelengine.managers.model.taskshandler.ModelEngineTaskHandler;
import re.imc.geysermodelengine.packet.entity.PacketEntity;

import java.util.Set;

public class ModelEngineEntityData implements EntityData {

    private final GeyserModelEngine plugin;

    private final PacketEntity entity;
    private final Set<Player> viewers = Sets.newConcurrentHashSet();

    private ModeledEntity modeledEntity;
    private ActiveModel activeModel;

    private ModelEngineTaskHandler entityTask;

    public ModelEngineEntityData(GeyserModelEngine plugin, ModeledEntity modeledEntity, ActiveModel activeModel) {
        this.plugin = plugin;

        this.modeledEntity = modeledEntity;
        this.activeModel = activeModel;
        Location location = modeledEntity.getBase().getLocation();
        this.entity = new PacketEntity(EntityTypes.PIG, viewers, location);
        this.entity.syncModelEnginePose(
                location,
                modeledEntity.getYBodyRot(),
                activeModel.getXHeadRot(),
                activeModel.getYHeadRot()
        );

        runEntityTask();
    }

    @Override
    public void teleportToModel() {
        ModeledEntity modeledEntity = this.modeledEntity;
        ActiveModel activeModel = this.activeModel;
        Location location = modeledEntity.getBase().getLocation();

        if (activeModel == null) {
            entity.teleport(location);
            return;
        }

        entity.syncModelEnginePose(
                location,
                modeledEntity.getYBodyRot(),
                activeModel.getXHeadRot(),
                activeModel.getYHeadRot()
        );

        if (plugin.getConfigManager().getConfig().getBoolean("options.debug.location")) plugin.getLogger().info(activeModel.getBlueprint().getName() + " " + location);
    }

    public void runEntityTask() {
        entityTask = new ModelEngineTaskHandler(plugin, this);
        entityTask.start(); // 先赋值 entityTask 再启动，关闭构造期 getEntityTask()==null 竞态
    }

    /**
     * 同一 base 实体被重复挂载同名蓝图、但产生了新的 ModeledEntity/ActiveModel 实例时，
     * 把本数据刷新指向最新的活实例（复用同一 PacketEntity 与定时任务，避免新增/闪烁基岩实体）。
     */
    public void updateModel(ModeledEntity modeledEntity, ActiveModel activeModel) {
        this.modeledEntity = modeledEntity;
        this.activeModel = activeModel;
    }

    @Override
    public PacketEntity getEntity() {
        return entity;
    }

    @Override
    public Set<Player> getViewers() {
        return viewers;
    }

    @Override
    public Object getModelInstance() {
        return activeModel;
    }

    @Override
    public ModelEngineTaskHandler getEntityTask() {
        return entityTask;
    }

    public ModeledEntity getModeledEntity() {
        return modeledEntity;
    }

    public ActiveModel getActiveModel() {
        return activeModel;
    }

    @Override
    public int getBaseEntityId() {
        return modeledEntity.getBase().getEntityId();
    }

    @Override
    public String getBlueprintName() {
        return activeModel.getBlueprint().getName();
    }

    @Override
    public boolean isModelDead() {
        ActiveModel am = activeModel;
        return am == null || am.isDestroyed() || am.isRemoved();
    }
}
