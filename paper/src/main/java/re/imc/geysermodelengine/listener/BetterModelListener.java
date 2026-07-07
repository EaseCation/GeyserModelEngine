package re.imc.geysermodelengine.listener;

import kr.toxicity.model.api.bukkit.BetterModelBukkit;
import kr.toxicity.model.api.event.CreateEntityTrackerEvent;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import re.imc.geysermodelengine.GeyserModelEngine;
import re.imc.geysermodelengine.managers.model.entity.BetterModelEntityData;
import re.imc.geysermodelengine.managers.model.entity.EntityData;

import java.util.concurrent.ConcurrentHashMap;

public class BetterModelListener implements Listener {

    private final GeyserModelEngine plugin;

    public BetterModelListener(GeyserModelEngine plugin) {
        this.plugin = plugin;

        onModelSpawn();
    }

    public void onModelSpawn() {
        BetterModelBukkit.platform().eventBus().subscribe(plugin, CreateEntityTrackerEvent.class, event -> {
            plugin.getModelManager().getModelHandler().createModel(event.tracker());
        });
    }

    @EventHandler
    public void onModelDamage(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();

        // H3：桶可能已被 teardown 摘除，get 返回 null，必须 null-guard；命中则标记该实体全部蓝图为 hurt。
        ConcurrentHashMap<String, EntityData> bucket = plugin.getModelManager().getEntities().get(entity.getEntityId());
        if (bucket == null) return;

        bucket.values().forEach(d -> ((BetterModelEntityData) d).setHurt(true));
    }
}
