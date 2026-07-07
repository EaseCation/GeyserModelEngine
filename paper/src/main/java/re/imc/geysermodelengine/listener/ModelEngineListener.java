package re.imc.geysermodelengine.listener;

import com.ticxo.modelengine.api.events.*;
import com.ticxo.modelengine.api.model.ActiveModel;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import re.imc.geysermodelengine.GeyserModelEngine;

public class ModelEngineListener implements Listener {

    private final GeyserModelEngine plugin;

    public ModelEngineListener(GeyserModelEngine plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAddModel(AddModelEvent event) {
        if (event.isCancelled()) return;
        plugin.getModelManager().getModelHandler().createModel(event.getTarget(), event.getModel());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onModelMount(ModelMountEvent event) {
        if (!event.isDriver()) return;

        ActiveModel activeModel = event.getVehicle();
        if (activeModel == null) return;

        int entityID = activeModel.getModeledEntity().getBase().getEntityId();

        // 单一权威缓存：仅当该 base 实体确有被跟踪的载体时，才把驾驶员登记入 driversCache
        if (!plugin.getModelManager().getEntities().containsKey(entityID)) return;

        if (event.getPassenger() instanceof Player player) {
            plugin.getModelManager().getDriversCache().put(player.getUniqueId(), Pair.of(event.getVehicle(), event.getSeat()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onModelDismount(ModelDismountEvent event) {
        if (event.getPassenger() instanceof Player player) {
            plugin.getModelManager().getDriversCache().remove(player.getUniqueId());
        }
    }
}
