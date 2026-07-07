package re.imc.geysermodelengine.listener;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldInitEvent;
import re.imc.geysermodelengine.GeyserModelEngine;
import re.imc.geysermodelengine.events.GeyserModelEngineEntitySpawnEvent;
import re.imc.geysermodelengine.util.BedrockUtils;

public class ModelListener implements Listener {

    private final GeyserModelEngine plugin;

    public ModelListener(GeyserModelEngine plugin) {
        this.plugin = plugin;
    }

    /*
     / xSquishyLiam:
     / May change this into a better system?
    */
    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        World world = event.getWorld();
        world.getEntities().forEach(entity -> plugin.getModelManager().getModelHandler().processEntities(entity));
    }

    /*
     / xSquishyLiam:
     / A runDelay makes sure the client doesn't see pigs on login due to the client resyncing themselves back to normal
    */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!BedrockUtils.isBedrockPlayer(player)) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> plugin.getModelManager().getPlayerJoinedCache().add(player.getUniqueId()), 20);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!BedrockUtils.isBedrockPlayer(player)) return;
        plugin.getModelManager().getPlayerJoinedCache().remove(player.getUniqueId());
    }

    /*
     / respawn / 换维度 = 客户端「硬失效」：基岩端静默清空当前维度全部实体(含隐藏 PIG 载体)，
     / 但玩家没退出/没走远 → canSee 恒 true → checkViewers 永走「已在 viewers」维持分支 → 永不重发。
     / 这两个事件是 join 之外唯二的客户端实体重置点，onPlayerJoin 已处理 join，此处补上另两个。
    */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        resyncBedrockViewer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        resyncBedrockViewer(event.getPlayer());
    }

    /*
     / 硬失效后延迟 N tick(与 join 的 20t 同款，等客户端实体系统 resync 就绪)再把玩家逐出所有 viewers，
     / 由下一轮 checkViewers 经既有原子路径重建载体+重推 anim0 快照自愈。延迟而非立即：respawn 瞬间玩家
     / 仍在 playerJoinedCache、canSee 立即 true，若立即逐出会在 ~70ms 内向尚未 resync 的客户端重发而漏接，
     / 把空窗永久化(等价原 bug)。Math.max(1L,…) 防运维把 config 设成 0 令 runDelayed 抛异常。
    */
    private void resyncBedrockViewer(Player player) {
        if (!BedrockUtils.isBedrockPlayer(player)) return;
        long delayTicks = Math.max(1L,
                plugin.getConfigManager().getConfig().getInt("models.respawn-resend-delay", 20));
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin,
                t -> plugin.getModelManager().removeViewerFromAll(player), delayTicks);
    }
}
