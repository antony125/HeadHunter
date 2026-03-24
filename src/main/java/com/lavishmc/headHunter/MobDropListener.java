package com.lavishmc.headHunter;

import com.lavishmc.headHunter.DropHeads.events.EntityBeheadEvent;
import com.lavishmc.headHunter.DropHeads.events.HeadRollEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Bridges the DropHeads event pipeline with the HeadHunter mobs config.
 *
 * <ul>
 *   <li>Overrides the head-drop success chance using {@code mobs.<TYPE>.drop_chance}.</li>
 *   <li>Tags dropped head items with {@code headhunter:mob_type} so the sell listener
 *       can identify them without relying on display-name parsing.</li>
 *   <li>For mobs with {@code drop_custom: BANK_NOTE}, cancels the head drop and instead
 *       adds bank note items to the entity's drops at MONITOR time (so the count is
 *       automatically scaled by the MobStackManager stack size).</li>
 * </ul>
 */
public class MobDropListener implements Listener {

    /** PDC key written onto every head item dropped for a mob in the mobs config. */
    @SuppressWarnings("deprecation") // intentional fixed "headhunter" namespace
    static final NamespacedKey MOB_TYPE_KEY = new NamespacedKey("headhunter", "mob_type");

    @SuppressWarnings("deprecation")
    private static final NamespacedKey STACK_KEY = new NamespacedKey("headhunter", "stack_size");

    private final JavaPlugin plugin;
    private final Logger log;
    /**
     * UUIDs of entities whose EntityBeheadEvent was cancelled because they should
     * drop a bank note.  Consumed by the MONITOR EntityDeathEvent handler.
     */
    private final Set<UUID> pendingBankNoteDrops = new HashSet<>();

    public MobDropListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Drop-chance override
    // -------------------------------------------------------------------------

    /**
     * Replace DropHeads' computed drop-success with the value from our mobs config.
     * Uses the same random roll so the result is still probabilistic.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onHeadRoll(HeadRollEvent event) {
        String typeName = event.getVictim().getType().name();
        if (!plugin.getConfig().isConfigurationSection("mobs." + typeName)) return;

        double dropChance = plugin.getConfig().getDouble("mobs." + typeName + ".drop_chance", 1.0);
        event.setDropSuccess(event.getDropRoll() < dropChance);
    }

    // -------------------------------------------------------------------------
    // Bank-note substitution (cancel head drop, record for MONITOR handler)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityBehead(EntityBeheadEvent event) {
        String typeName = event.getVictim().getType().name();
        if (!plugin.getConfig().isConfigurationSection("mobs." + typeName)) return;

        String dropCustom = plugin.getConfig().getString("mobs." + typeName + ".drop_custom");
        if ("BANK_NOTE".equals(dropCustom)) {
            // Suppress the head drop; MONITOR handler will add the bank note instead.
            event.setCancelled(true);
            pendingBankNoteDrops.add(event.getVictim().getUniqueId());
        }
        // Head tagging is handled in onEntityDeath at MONITOR priority,
        // after DropHeads has fully constructed and committed the head ItemStack.
    }

    // -------------------------------------------------------------------------
    // Head tagging + bank-note drops (MONITOR — drops list is finalised here)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        String typeName = dead.getType().name();

        // ── Bank note path ───────────────────────────────────────────────────
        if (pendingBankNoteDrops.remove(dead.getUniqueId())) {
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
            return;
        }

        // ── Head tagging path ────────────────────────────────────────────────
        // Only tag heads for mobs defined in the mobs config.
        if (!plugin.getConfig().isConfigurationSection("mobs." + typeName)) return;

        for (ItemStack drop : event.getDrops()) {
            if (drop == null || drop.getType() != Material.PLAYER_HEAD) continue;
            ItemMeta meta = drop.getItemMeta();
            if (meta == null) continue;
            meta.getPersistentDataContainer().set(MOB_TYPE_KEY, PersistentDataType.STRING, typeName);
            drop.setItemMeta(meta);
            log.info("[HeadHunter] Tagged " + typeName + " head in drops");
        }
    }
}
