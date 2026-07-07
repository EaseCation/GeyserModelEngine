package re.imc.geysermodelengine.managers.model.entity;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.google.common.collect.Sets;
import com.ticxo.modelengine.api.utils.scheduling.BukkitPlatformTask;
import kr.toxicity.model.api.bukkit.BetterModelBukkit;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.bukkit.platform.BukkitLocation;
import kr.toxicity.model.api.entity.BaseEntity;
import kr.toxicity.model.api.platform.PlatformAdapter;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.Tracker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import re.imc.geysermodelengine.GeyserModelEngine;
import re.imc.geysermodelengine.managers.model.taskshandler.BetterModelTaskHandler;
import re.imc.geysermodelengine.packet.entity.PacketEntity;

import java.util.Set;

public class BetterModelEntityData implements EntityData {

    private final GeyserModelEngine plugin;

    private final PacketEntity entity;
    private final Set<Player> viewers = Sets.newConcurrentHashSet();

    // 非 final：同一 base 实体被重复挂载同名蓝图、产生新 tracker 实例时刷新复用同一载体（见 updateTracker）
    private BaseEntity entitySource;
    private EntityTracker entityTracker;
    // TODO DummyTracker support

    private BetterModelTaskHandler entityTask;

    private boolean hurt;

    public BetterModelEntityData(GeyserModelEngine plugin, BaseEntity entitySource, EntityTracker entityTracker) {
        this.plugin = plugin;

        this.entitySource = entitySource;
        this.entityTracker = entityTracker;

        Location location = ((BukkitLocation) entitySource.location()).source();
        this.entity = new PacketEntity(EntityTypes.PIG, viewers, location);

        runEntityTask();
    }

    @Override
    public void teleportToModel() {
        Location location = ((BukkitLocation) entitySource.location()).source();
        entity.teleport(location);

        if (plugin.getConfigManager().getConfig().getBoolean("options.debug.location")) plugin.getLogger().info(entityTracker.name() + " " + location);
    }

    public void runEntityTask() {
        entityTask = new BetterModelTaskHandler(plugin, this);
        entityTask.start(); // 先赋值 entityTask 再启动，关闭构造期 getEntityTask()==null 竞态
    }

    /**
     * 同一 base 实体被重复挂载同名蓝图、但产生了新的 BaseEntity/EntityTracker 实例时，
     * 把本数据刷新指向最新的活实例（复用同一 PacketEntity 与定时任务，避免新增/闪烁基岩实体）。
     */
    public void updateTracker(BaseEntity entitySource, EntityTracker entityTracker) {
        this.entitySource = entitySource;
        this.entityTracker = entityTracker;
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
    public BetterModelTaskHandler getEntityTask() {
        return entityTask;
    }

    @Override
    public Object getModelInstance() {
        return entityTracker;
    }

    public void setHurt(boolean hurt) {
        this.hurt = hurt;
    }

    public BaseEntity getEntitySource() {
        return entitySource;
    }

    public EntityTracker getEntityTracker() {
        return entityTracker;
    }

    public boolean isHurt() {
        return hurt;
    }

    @Override
    public int getBaseEntityId() {
        return entitySource.id();
    }

    @Override
    public String getBlueprintName() {
        return entityTracker.name();
    }

    @Override
    public boolean isModelDead() {
        return entitySource.dead() || entityTracker.forRemoval();
    }
}
