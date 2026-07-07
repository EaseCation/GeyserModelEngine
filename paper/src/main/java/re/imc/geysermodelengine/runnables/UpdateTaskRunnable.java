package re.imc.geysermodelengine.runnables;

import re.imc.geysermodelengine.GeyserModelEngine;
import re.imc.geysermodelengine.managers.model.entity.EntityData;

import java.util.concurrent.ConcurrentHashMap;

public class UpdateTaskRunnable implements Runnable {

    private final GeyserModelEngine plugin;

    public UpdateTaskRunnable(GeyserModelEngine plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        ConcurrentHashMap<Integer, ConcurrentHashMap<String, EntityData>> entities = plugin.getModelManager().getEntities();
        if (entities.isEmpty()) return;

        try {
            // 内层 CHM：与 create 的 bucket.put / teardown 的 bucket.remove 并发遍历无 CME
            for (ConcurrentHashMap<String, EntityData> bucket : entities.values()) {
                bucket.values().forEach(entityData -> {
                    if (entityData.getEntity().isDead()) return;   // 已销毁载体短路，省无谓发包
                    if (entityData.getViewers().isEmpty()) return;
                    plugin.getEntityTaskManager().getPropertyHandler().updateEntityProperties(entityData, entityData.getViewers(), false);
                });
            }
        } catch (Throwable err) {
            err.printStackTrace();
            throw new RuntimeException(err);
        }
    }
}
