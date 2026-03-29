package com.lavishmc.headHunter;

import com.lavishmc.headHunter.DropHeads.events.EntityBeheadEvent;
import io.papermc.paper.event.entity.EntityRemoveEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Handles the {@code drop_custom: BANK_NOTE} behaviour defined in the mobs config.
 *
 * <p>When a configured mob with {@code drop_custom: BANK_NOTE} is killed,
 * its head drop is suppressed and replaced with bank note items scaled by
 * the MobStackManager stack size.</p>
 */
public class BankNoteDropListener implements Listener {

    @SuppressWarnings("deprecation") // intentional fixed "headhunter" namespace
    private static final NamespacedKey STACK_KEY = new NamespacedKey("headhunter", "stack_size");

    private final JavaPlugin plugin;
    /** UUIDs recorded in EntityBeheadEvent; consumed by the MONITOR death handler. */
    private final Set<UUID> pendingBankNoteDrops = new HashSet<>();

    public BankNoteDropListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Cancel the head drop for BANK_NOTE mobs and record the entity for the death handler. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityBehead(EntityBeheadEvent event) {
        String typeName = event.getVictim().getType().name();
        if (!plugin.getConfig().isConfigurationSection("mobs." + typeName)) return;

        String dropCustom = plugin.getConfig().getString("mobs." + typeName + ".drop_custom");
        if (!"BANK_NOTE".equals(dropCustom)) return;

        event.setCancelled(true);
        pendingBankNoteDrops.add(event.getVictim().getUniqueId());
    }

    /** Clean up any pending entry when an entity is removed without dying (despawn, chunk unload, etc.). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        pendingBankNoteDrops.remove(event.getEntity().getUniqueId());
    }

    /** Add bank notes to the drop list, scaled by the mob's stack size. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!pendingBankNoteDrops.remove(dead.getUniqueId())) return;

        String typeName = dead.getType().name();
        int tier = plugin.getConfig().getInt("mobs." + typeName + ".tier", 1);
        int stackSize = dead.getPersistentDataContainer()
                .getOrDefault(STACK_KEY, PersistentDataType.INTEGER, 1);

        int remaining = stackSize;
        while (remaining > 0) {
            int amount = Math.min(remaining, 64);
            ItemStack note = BankNoteManager.createBankNote(plugin, tier);
            note.setAmount(amount);
            event.getDrops().add(note);
            remaining -= amount;
        }
    }
}
