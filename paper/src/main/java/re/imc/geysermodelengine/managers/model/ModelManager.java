package re.imc.geysermodelengine.managers.model;

import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.bone.type.Mount;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import re.imc.geysermodelengine.GeyserModelEngine;
import re.imc.geysermodelengine.managers.model.entity.EntityData;
import re.imc.geysermodelengine.managers.model.modelhandler.BetterModelHandler;
import re.imc.geysermodelengine.managers.model.modelhandler.ModelEngineHandler;
import re.imc.geysermodelengine.managers.model.modelhandler.ModelHandler;
import re.imc.geysermodelengine.managers.model.taskshandler.TaskHandler;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ModelManager {

    private final GeyserModelEngine plugin;

    private ModelHandler modelHandler;

    private final HashSet<UUID> playerJoinedCache = new HashSet<>();

    /**
     * 单一权威缓存：base 实体 id → (蓝图名 → EntityData)。
     * 内外层均为 ConcurrentHashMap：外层 compute(entityID,…) 的每键 bin 锁把同一实体的
     * create/refresh/teardown 全部串行化（原子幂等、原子拆除）；内层 CHM 消除 UpdateTaskRunnable
     * 遍历与 create put 之间的 ConcurrentModificationException。逐 (实体,蓝图) 至多一条载体。
     */
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<String, EntityData>> entities = new ConcurrentHashMap<>();

    // MEG ONLY
    private final ConcurrentHashMap<UUID, Pair<ActiveModel, Mount>> driversCache = new ConcurrentHashMap<>();

    public ModelManager(GeyserModelEngine plugin) {
        this.plugin = plugin;

        if (Bukkit.getPluginManager().getPlugin("ModelEngine") != null) {
            this.modelHandler = new ModelEngineHandler(plugin);
            plugin.getLogger().info("Using ModelEngine handler!");
        } else if (Bukkit.getPluginManager().getPlugin("BetterModel") != null) {
            this.modelHandler = new BetterModelHandler(plugin);
            plugin.getLogger().info("Using BetterModel handler!");
        } else {
            plugin.getLogger().severe("No supported model engine found!");
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return;
        }

        modelHandler.loadListeners();
    }

    /**
     * 插件停用/重载时清场。H7：既要销毁基岩载体、更要 cancel 每个 20ms 定时任务，
     * 否则 /reload 会泄漏 ScheduledFuture（配合 GeyserModelEngine.onDisable 关闭线程池）。
     */
    public void removeEntities() {
        for (ConcurrentHashMap<String, EntityData> bucket : entities.values()) {
            bucket.values().forEach(data -> {
                TaskHandler task = data.getEntityTask();
                if (task != null) task.cancel();
                data.getEntity().remove();
            });
        }
        entities.clear();
    }

    /**
     * 客户端硬失效(respawn/换维度)后，把该玩家从所有模型载体的 viewers 影子集合逐出。
     * 不发 destroy：触发事件已使基岩客户端自行销毁当前维度全部实体，对不存在的 entityId 再发 destroy 属多余。
     * 逐出后各模型 20ms checkViewers 经既有原子路径 sendSpawnPacket→sendEntityData(firstSend) 自愈。
     * 线程安全：entities 内外层均 CHM，values() 弱一致迭代器绝不抛 CME；viewers 为 ConcurrentHashSet，remove 原子。
     */
    public void removeViewerFromAll(Player player) {
        for (ConcurrentHashMap<String, EntityData> bucket : entities.values()) {
            for (EntityData data : bucket.values()) {
                data.getViewers().remove(player);
            }
        }
    }

    public ModelHandler getModelHandler() {
        return modelHandler;
    }

    public HashSet<UUID> getPlayerJoinedCache() {
        return playerJoinedCache;
    }

    public ConcurrentHashMap<Integer, ConcurrentHashMap<String, EntityData>> getEntities() {
        return entities;
    }

    public ConcurrentHashMap<UUID, Pair<ActiveModel, Mount>> getDriversCache() {
        return driversCache;
    }
}
