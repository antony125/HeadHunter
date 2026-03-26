package com.lavishmc.headHunter;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Prevents mobs spawned by our stack system from burning in sunlight.
 * Detection: the entity carries the headhunter:stack_size PDC key,
 * which MobStackManager writes onto every stack-leader entity.
 */
public class SunlightProtectionListener implements Listener {

    //noinspection deprecation — intentional fixed namespace matching MobStackManager
    private static final NamespacedKey STACK_SIZE_KEY =
            new NamespacedKey("headhunter", "stack_size");

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityCombust(EntityCombustEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) return;
        if (entity.getPersistentDataContainer().has(STACK_SIZE_KEY, PersistentDataType.INTEGER)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityCombustByBlock(EntityCombustByBlockEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) return;
        if (entity.getPersistentDataContainer().has(STACK_SIZE_KEY, PersistentDataType.INTEGER)) {
            event.setCancelled(true);
        }
    }
}
