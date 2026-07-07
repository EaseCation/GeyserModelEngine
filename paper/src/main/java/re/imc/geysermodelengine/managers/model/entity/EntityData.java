package re.imc.geysermodelengine.managers.model.entity;

import org.bukkit.entity.Player;
import re.imc.geysermodelengine.managers.model.taskshandler.TaskHandler;
import re.imc.geysermodelengine.packet.entity.PacketEntity;

import java.util.Set;

public interface EntityData {

    /**
     * Teleports the packet entity to the model
     */
    void teleportToModel();

    /**
     * Gets the packet Entity
     */
    PacketEntity getEntity();

    /**
     * Gets the entity view of players
     */
    Set<Player> getViewers();

    /**
     * Get the entity task handler
     */
    TaskHandler getEntityTask();

    /**
     * Gets the model of the entity
     */
    Object getModelInstance();

    /**
     * 该模型所依附的服务端 base 实体 id（单一权威缓存 entities 的外层键）
     */
    int getBaseEntityId();

    /**
     * 蓝图名（单一权威缓存 entities 的内层键；逐实体唯一）
     */
    String getBlueprintName();

    /**
     * 底层模型是否已死亡/待移除（teardown 唯一判据，须在 entities.compute 锁内重读）。
     * ModelEngine: activeModel==null || isDestroyed() || isRemoved()
     * BetterModel: entitySource.dead() || entityTracker.forRemoval()
     */
    boolean isModelDead();
}
