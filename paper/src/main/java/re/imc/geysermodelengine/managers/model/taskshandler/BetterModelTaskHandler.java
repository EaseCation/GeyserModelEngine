package re.imc.geysermodelengine.managers.model.taskshandler;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import me.zimzaza4.geyserutils.spigot.api.EntityUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import re.imc.geysermodelengine.GeyserModelEngine;
import re.imc.geysermodelengine.events.GeyserModelEngineEntityDeathEvent;
import re.imc.geysermodelengine.managers.model.entity.BetterModelEntityData;
import re.imc.geysermodelengine.managers.model.entity.EntityData;
import re.imc.geysermodelengine.packet.entity.PacketEntity;

import java.awt.*;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class BetterModelTaskHandler implements TaskHandler {

    private final GeyserModelEngine plugin;

    private final BetterModelEntityData entityData;

    private int tick = 0;
    private int syncTick = 0;

    private float lastScale = -1.0f;
    private Color lastColor = null;

    // volatile：teardown 写在 20ms 轮询线程、延迟 spawn 任务读在另一池线程；
    // compute 外的读点(runAsync/checkViewers)靠 volatile 见新值，compute 内的读已由 bin 锁建立 happens-before。
    private volatile boolean removed = false;

    private final ConcurrentHashMap<String, Integer> lastIntSet = new ConcurrentHashMap<>();
    private final Cache<String, Boolean> lastPlayedAnim = CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.MILLISECONDS).build();

    // 非 final：构造与调度拆分（见 start()），关闭构造期 this-escape 竞态
    private ScheduledFuture scheduledFuture;

    public BetterModelTaskHandler(GeyserModelEngine plugin, BetterModelEntityData entityData) {
        this.plugin = plugin;
        this.entityData = entityData;
    }

    @Override
    public void start() {
        plugin.getEntityTaskManager().sendHitBoxToAll(entityData);
        scheduledFuture = plugin.getSchedulerPool().scheduleAtFixedRate(() -> {
            try {
                runAsync();
            } catch (Throwable err) {
                err.printStackTrace();
            }
        }, 0, 20, TimeUnit.MILLISECONDS);
    }

    @Override
    public void runAsync() {
        if (removed || entityData == null) return;

        PacketEntity entity = entityData.getEntity();
        if (entity == null || entity.isDead()) return;

        plugin.getEntityTaskManager().checkViewers(entityData, entityData.getViewers());

        entityData.teleportToModel();

        Set<Player> viewers = entityData.getViewers();

        // 快照优化：先做便宜的门控；权威判据在 entities.compute 锁内重读。
        if (entityData.isModelDead()) {
            teardown();
            return;
        }

        tick++;
        if (tick > 400) {
            tick = 0;
            plugin.getEntityTaskManager().sendHitBoxToAll(entityData);
        }

        if (viewers.isEmpty()) return;

        plugin.getEntityTaskManager().getPropertyHandler().sendScale(entityData, viewers, lastScale, false);
        plugin.getEntityTaskManager().getPropertyHandler().sendColor(entityData, viewers, lastColor, false);
    }

    /**
     * H1：teardown 决策+动作整体进 entities.compute，锁内重读复检，与 create 的 updateTracker 互斥。
     * 与 MEG 侧同构：避免 TOCTOU 误销毁一个刚被并发挂载刷新为 live 的载体。
     * remove/cancel/死亡事件等副作用在 compute 外经 tornDown 标志触发（锁卫生）。
     */
    private void teardown() {
        int entityID = entityData.getBaseEntityId();
        String blueprintName = entityData.getBlueprintName();
        final boolean[] tornDown = {false};

        plugin.getModelManager().getEntities().compute(entityID, (id, bucket) -> {
            if (bucket == null) return null;
            EntityData cur = bucket.get(blueprintName);
            if (cur != this.entityData) return bucket;              // 已被替换/移除，别动
            if (!cur.isModelDead()) return bucket;                  // 锁内重读：已刷新为 live → 中止，不 remove/不 cancel
            removed = true;
            entityData.getEntity().remove();                       // 只销毁本 PacketEntity
            cancel();
            bucket.remove(blueprintName);                          // 只删本蓝图这一条（不误伤兄弟蓝图）
            tornDown[0] = true;
            return bucket.isEmpty() ? null : bucket;               // 桶空才摘外层
        });

        if (tornDown[0]) {
            if (plugin.getConfigManager().getConfig().getBoolean("options.debug.death")) plugin.getLogger().info(blueprintName + " has died, removing runAsync!");
            Bukkit.getPluginManager().callEvent(new GeyserModelEngineEntityDeathEvent(entityData));
        }
    }

    @Override
    public void sendEntityData(EntityData entityData, Player player, int delay) {
        BetterModelEntityData betterModelEntityData = (BetterModelEntityData) entityData;
        long propertiesSendDelay = plugin.getConfigManager().getConfig().getInt("models.properties-send-delay", 1);

        EntityUtils.setCustomEntity(player, betterModelEntityData.getEntity().getEntityId(), plugin.getConfigManager().getConfig().getString("models.namespace") + ":" + betterModelEntityData.getEntityTracker().name().toLowerCase());
        if (plugin.getConfigManager().getConfig().getBoolean("options.debug.send-data")) plugin.getLogger().info("Setting custom entity data for " + betterModelEntityData.getEntityTracker().name());

        plugin.getSchedulerPool().schedule(() -> {
            // H8：延迟 spawn 的「判定+发包」整体进 entities.computeIfPresent，与 teardown 的 compute 同键串行。
            // teardown 先跑 → 桶已摘除本蓝图/本键 → 此处命中不到本 data → 跳过；spawn 先跑 → 客户端 spawn→destroy 干净。
            // 杜绝迟到 spawn 在 death-destroy 之后新建一个无人回收的客户端孤儿 actor（「留头」根因）。
            int entityID = this.entityData.getBaseEntityId();
            String blueprintName = this.entityData.getBlueprintName();
            final boolean[] spawned = {false};

            plugin.getModelManager().getEntities().computeIfPresent(entityID, (id, bucket) -> {
                if (bucket.get(blueprintName) == this.entityData
                        && !removed
                        && !this.entityData.getEntity().isDead()
                        && this.entityData.getViewers().contains(player)) {
                    this.entityData.getEntity().sendSpawnPacket(Collections.singletonList(player));
                    spawned[0] = true;
                }
                return bucket;                 // 不改桶结构，仅原子读判+发包
            });
            if (!spawned[0]) return;

            plugin.getSchedulerPool().schedule(() -> {
                // 属性/尺寸/颜色属非关键（Geyser 对不存在实体的更新 no-op），保留轻量守卫即可
                if (removed || entityData.getEntity().isDead() || !entityData.getViewers().contains(player)) return;
                plugin.getEntityTaskManager().getPropertyHandler().sendHitBox(entityData, player);

                plugin.getEntityTaskManager().getPropertyHandler().sendScale(entityData, Collections.singleton(player), lastScale, true);
                plugin.getEntityTaskManager().getPropertyHandler().sendColor(entityData, Collections.singleton(player), lastColor, true);

                plugin.getEntityTaskManager().getPropertyHandler().updateEntityProperties(entityData, Collections.singleton(player), true);
            }, propertiesSendDelay, TimeUnit.MILLISECONDS);
        }, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancel() {
        if (scheduledFuture != null) scheduledFuture.cancel(true);
    }

    public void setTick(int tick) {
        this.tick = tick;
    }

    public void setSyncTick(int syncTick) {
        this.syncTick = syncTick;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    public void setLastScale(float lastScale) {
        this.lastScale = lastScale;
    }

    public int getTick() {
        return tick;
    }

    public int getSyncTick() {
        return syncTick;
    }

    public void setLastColor(Color lastColor) {
        this.lastColor = lastColor;
    }

    public float getLastScale() {
        return lastScale;
    }

    public Color getLastColor() {
        return lastColor;
    }

    public boolean isRemoved() {
        return removed;
    }

    public ConcurrentHashMap<String, Integer> getLastIntSet() {
        return lastIntSet;
    }

    public Cache<String, Boolean> getLastPlayedAnim() {
        return lastPlayedAnim;
    }

    public ScheduledFuture getScheduledFuture() {
        return scheduledFuture;
    }
}
