package com.lavishmc.headHunter;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MobStackManager implements Listener {

    // -------------------------------------------------------------------------
    // ChunkKey — identity key for one chunk in one world
    // -------------------------------------------------------------------------

    public record ChunkKey(String world, int chunkX, int chunkZ) {}

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int MAX_STACK = 9999;

    /**
     * Spawn reasons that are always allowed regardless of the natural-spawning
     * config.  Everything else is treated as "natural" and suppressed when the
     * option is off.
     */
    private static final Set<CreatureSpawnEvent.SpawnReason> ALLOWED_CAUSES = Set.of(
            CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.COMMAND
    );

    /** All skull/head materials that DropHeads (and vanilla) can add to drops. */
    private static final Set<Material> SKULL_MATERIALS = Set.of(
            Material.PLAYER_HEAD,
            Material.ZOMBIE_HEAD,
            Material.SKELETON_SKULL,
            Material.WITHER_SKELETON_SKULL,
            Material.CREEPER_HEAD,
            Material.PIGLIN_HEAD,
            Material.DRAGON_HEAD
    );

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final NamespacedKey stackKey;
    private final NamespacedKey headCountKey;
    /** When false, only SPAWNER / SPAWNER_EGG / COMMAND spawns are permitted (unless whitelisted). */
    private final boolean naturalSpawning;
    /** Entity types exempt from the natural-spawning ban. Empty when naturalSpawning is true. */
    private final Set<EntityType> naturalSpawnWhitelist;
    /** When true, every spawned mob has its AI disabled so it stands still. */
    private final boolean freezeMobs;

    /**
     * One entry per chunk.  Inner map: EntityType → UUID of the living stack
     * leader for that type.  Entries are added on first spawn, removed on death.
     */
    private final HashMap<ChunkKey, Map<EntityType, UUID>> stacks = new HashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public MobStackManager(JavaPlugin plugin) {
        // Hardcode the "headhunter" namespace so the key is always
        // "headhunter:stack_size" regardless of the registered plugin name.
        //noinspection deprecation  — intentional fixed namespace
        this.stackKey    = new NamespacedKey("headhunter", "stack_size");
        //noinspection deprecation
        this.headCountKey = new NamespacedKey("headhunter", "head_count");
        this.naturalSpawning = plugin.getConfig().getBoolean("natural-spawning", false);
        if (naturalSpawning) {
            this.naturalSpawnWhitelist = Set.of();
        } else {
            Set<EntityType> whitelist = new java.util.HashSet<>();
            for (String name : plugin.getConfig().getStringList("natural-spawn-whitelist")) {
                try {
                    whitelist.add(EntityType.valueOf(name.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("natural-spawn-whitelist: unknown entity type '" + name + "'");
                }
            }
            this.naturalSpawnWhitelist = java.util.Collections.unmodifiableSet(whitelist);
        }
        this.freezeMobs = plugin.getConfig().getBoolean("freeze-mobs", true);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the stack size stored on {@code entity}, or 1 if none is set.
     */
    public int getStackSize(Entity entity) {
        return entity.getPersistentDataContainer()
                .getOrDefault(stackKey, PersistentDataType.INTEGER, 1);
    }

    // -------------------------------------------------------------------------
    // Spawn listener — merge or register
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        boolean fromSpawner = event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER;

        if (event.isCancelled()) {
            if (fromSpawner) {
                // Un-cancel spawner spawns so they always go through, then fall
                // through to the stack merge logic below.
                event.setCancelled(false);
            } else {
                // Some other plugin cancelled a non-spawner spawn — leave it alone.
                return;
            }
        }

        // If natural spawning is disabled, suppress any spawn that did not
        // originate from a spawner block, spawn egg, or command.
        if (!naturalSpawning
                && !ALLOWED_CAUSES.contains(event.getSpawnReason())
                && !naturalSpawnWhitelist.contains(event.getEntity().getType())) {
            event.setCancelled(true);
            return;
        }

        LivingEntity spawned = event.getEntity();
        EntityType type = spawned.getType();
        ChunkKey key = chunkKey(spawned);

        Map<EntityType, UUID> chunkMap = stacks.get(key);
        if (chunkMap != null) {
            UUID leaderUUID = chunkMap.get(type);
            if (leaderUUID != null) {
                Entity leader = spawned.getWorld().getEntity(leaderUUID);

                if (leader instanceof LivingEntity le && le.isValid() && !le.isDead()) {
                    int current = getStackSize(le);
                    if (current < MAX_STACK) {
                        // Cancel the new spawn and grow the existing stack.
                        event.setCancelled(true);
                        int next = current + 1;
                        setStackSize(le, next);
                        applyName(le, type, next);
                    }
                    // Whether we merged or the stack is full, we're done.
                    return;
                }

                // Stale entry — the leader is gone; clear it so we can register
                // the new spawn as the fresh leader below.
                chunkMap.remove(type);
                if (chunkMap.isEmpty()) stacks.remove(key);
            }
        }

        // Register this entity as the stack leader for its type in this chunk.
        stacks.computeIfAbsent(key, k -> new HashMap<>())
              .put(type, spawned.getUniqueId());
        setStackSize(spawned, 1);
        // No visible name at size 1 — keep it clean.

        // Apply a permanent max-strength Slowness effect so the mob cannot move
        // on its own while still receiving knockback normally.
        if (freezeMobs) spawned.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS,
                Integer.MAX_VALUE,
                /*amplifier=*/ 255,
                /*ambient=*/   false,
                /*particles=*/ false,
                /*icon=*/      false
        ));
    }

    // -------------------------------------------------------------------------
    // Death listener — multiply drops, clean up entry
    // -------------------------------------------------------------------------

    /**
     * Runs at MONITOR so DropHeads (which registers at LOW by default) has
     * already appended head items to the drop list before we multiply them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        int size = getStackSize(dead);

        // Always remove the stack entry on death so the next spawn starts fresh.
        ChunkKey key = chunkKey(dead);
        Map<EntityType, UUID> chunkMap = stacks.get(key);
        if (chunkMap != null) {
            UUID registered = chunkMap.get(dead.getType());
            if (dead.getUniqueId().equals(registered)) {
                chunkMap.remove(dead.getType());
                if (chunkMap.isEmpty()) stacks.remove(key);
            }
        }

        if (size <= 1) return;

        // For each head item DropHeads added, consolidate the full stack count into
        // a single item entity.  The visible amount is capped at 64 (the Minecraft
        // item-stack limit) for display, but the real total is stored in PDC under
        // headhunter:head_count so the pickup listener can distribute it correctly.
        for (ItemStack drop : event.getDrops()) {
            if (drop == null || !SKULL_MATERIALS.contains(drop.getType())) continue;
            int total = size; // one head per mob in the stack
            drop.setAmount(Math.min(total, 64));
            ItemMeta meta = drop.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer()
                    .set(headCountKey, PersistentDataType.INTEGER, total);
                drop.setItemMeta(meta);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pickup listener — distribute the real head count into the player's inventory
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack item = event.getItem().getItemStack();
        if (!SKULL_MATERIALS.contains(item.getType())) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        Integer total = meta.getPersistentDataContainer()
                .get(headCountKey, PersistentDataType.INTEGER);
        if (total == null) return; // not a stacked head item — let vanilla handle it

        // Cancel the default pickup and remove the item entity so nothing
        // else can pick up the same drop.
        event.setCancelled(true);
        event.getItem().remove();

        // Build a clean template (no head_count PDC) to hand to the player.
        ItemStack template = item.clone();
        ItemMeta cleanMeta = template.getItemMeta();
        cleanMeta.getPersistentDataContainer().remove(headCountKey);
        template.setItemMeta(cleanMeta);

        // Distribute into the player's inventory as stacks of 64; drop any
        // overflow at the player's feet if their inventory is full.
        int remaining = total;
        while (remaining > 0) {
            int give = Math.min(remaining, 64);
            ItemStack stack = template.clone();
            stack.setAmount(give);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            remaining -= give;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setStackSize(LivingEntity entity, int size) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(stackKey, PersistentDataType.INTEGER, size);
    }

    private void applyName(LivingEntity entity, EntityType type, int size) {
        if (size <= 1) {
            entity.setCustomName(null);
            entity.setCustomNameVisible(false);
        } else {
            entity.setCustomName("§e§l" + formatMobName(type) + " §f§lx§6§l" + size);
            entity.setCustomNameVisible(true);
        }
    }

    /** Converts e.g. WITHER_SKELETON → "Wither Skeleton". */
    private static String formatMobName(EntityType type) {
        String[] words = type.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1))
                  .append(' ');
            }
        }
        return sb.toString().trim();
    }

    private static ChunkKey chunkKey(Entity entity) {
        return new ChunkKey(
                entity.getWorld().getName(),
                entity.getLocation().getChunk().getX(),
                entity.getLocation().getChunk().getZ()
        );
    }
}
