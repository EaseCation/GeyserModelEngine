package re.imc.geysermodelengine.managers.model.taskshandler;

import org.bukkit.entity.Player;
import re.imc.geysermodelengine.managers.model.entity.EntityData;

public interface TaskHandler {

    /**
     * 启动定时任务。必须在 EntityData 把 entityTask 字段赋值完成后再调用，
     * 避免首个 tick 观察到 null 的 getEntityTask()（构造期 this-escape 竞态）。
     */
    void start();

    /**
     * Runs the entity scheduler
     */
    void runAsync();

    /**
     * Spawns the entity to the player
     * @param entityData The data of the entity
     * @param player Sends the entity to the player
     * @param delay Delays sending the entity to the player
     */
    void sendEntityData(EntityData entityData, Player player, int delay);

    /**
     * Cancels the entity scheduler
     */
    void cancel();
}
