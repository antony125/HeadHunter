package com.lavishmc.headHunter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spawner stacking system.
 *
 * Spawner items are Material.SPAWNER with PDC keys for type and stack count.
 * HIDE_ADDITIONAL_TOOLTIP suppresses the vanilla tooltip warning on the item.
 */
public class SpawnerStackManager implements Listener {

    /**
     * PDC key for entity type name — stored on items (CHEST) and block tile entities (SPAWNER).
     * Exposed so GiveSpawnerCommand can create items that this manager recognises.
     */
    //noinspection deprecation
    public static final NamespacedKey SPAWNER_TYPE_KEY =
            new NamespacedKey("headhunter", "spawner_type");

    /** PDC key for stack count — stored on items and block tile entities. */
    //noinspection deprecation
    public static final NamespacedKey SPAWNER_STACK_KEY =
            new NamespacedKey("headhunter", "spawner_stack");

    private final JavaPlugin plugin;
    private final SpawnRateConfig spawnRateConfig;
    private final int maxStack;

    /** Location string → active BukkitTask for that spawner. */
    private final Map<String, BukkitTask> tasks = new HashMap<>();
    /** Location string → current stack count. */
    private final Map<String, Integer> stackCounts = new HashMap<>();
    /** Location string → UUID of the floating TextDisplay label. */
    private final Map<String, UUID> labels = new HashMap<>();

    public SpawnerStackManager(JavaPlugin plugin, SpawnRateConfig spawnRateConfig) {
        this.plugin = plugin;
        this.spawnRateConfig = spawnRateConfig;
        this.maxStack = plugin.getConfig().getInt("spawner-stack-max", 50);
    }

    // -------------------------------------------------------------------------
    // Event: right-click spawner with same-type spawner (chest) in hand → merge
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.SPAWNER) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.SPAWNER) return;

        EntityType heldType = getItemSpawnerType(held);
        if (heldType == null) return; // not our custom spawner item

        EntityType placedType = getBlockSpawnerType(clicked);
        if (placedType == null || placedType != heldType) return;

        // Same type — attempt merge.
        String locKey = locKey(clicked.getLocation());
        int current = stackCounts.getOrDefault(locKey, 1);

        if (current >= maxStack) {
            player.sendMessage(msg("&cThis spawner is already at the maximum stack size of &e" + maxStack + "&c."));
            event.setCancelled(true);
            return;
        }

        // Consume exactly 1 spawner from hand per merge click (skipped in creative mode).
        if (player.getGameMode() != GameMode.CREATIVE) {
            if (held.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                held.setAmount(held.getAmount() - 1);
            }
        }

        int newCount = current + 1;
        stackCounts.put(locKey, newCount);

        // Update block tile-entity PDC to reflect new count.
        CreatureSpawner cs = (CreatureSpawner) clicked.getState();
        cs.getPersistentDataContainer().set(SPAWNER_STACK_KEY, PersistentDataType.INTEGER, newCount);
        cs.update();

        updateLabel(clicked.getLocation(), placedType, newCount);
        restartTask(clicked.getLocation(), placedType, newCount);

        player.sendMessage(msg("&aMerged &e1 &aspawner. Stack is now &e" + newCount + "/" + maxStack + "&a."));
        event.setCancelled(true);
    }

    // -------------------------------------------------------------------------
    // Event: placing our spawner item (chest) — swap block type, configure spawner
    // -------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (placed.getType() != Material.SPAWNER) return;

        ItemStack item = event.getItemInHand();

        // Only intercept our custom spawner items (identified by PDC type key).
        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta == null) return;
        String typeStr = itemMeta.getPersistentDataContainer().get(SPAWNER_TYPE_KEY, PersistentDataType.STRING);
        if (typeStr == null) return; // regular chest — leave it alone

        EntityType type = null;
        try { type = EntityType.valueOf(typeStr); } catch (IllegalArgumentException ignored) {}
        if (type == null) return;

        int count = getItemStackCount(item);

        // Apply entity type and persist both keys on the tile entity.
        CreatureSpawner cs = (CreatureSpawner) placed.getState();
        cs.setSpawnedType(type);
        PersistentDataContainer blockPdc = cs.getPersistentDataContainer();
        blockPdc.set(SPAWNER_TYPE_KEY, PersistentDataType.STRING, type.name());
        if (count > 1) {
            blockPdc.set(SPAWNER_STACK_KEY, PersistentDataType.INTEGER, count);
        } else {
            blockPdc.remove(SPAWNER_STACK_KEY);
        }
        cs.update();

        if (count <= 1) return; // single spawner — no custom task needed

        String locKey = locKey(placed.getLocation());

        // Clear any stale entry for this location before registering fresh.
        BukkitTask stale = tasks.remove(locKey);
        if (stale != null) stale.cancel();
        stackCounts.remove(locKey);
        removeLabel(locKey);

        stackCounts.put(locKey, count);
        updateLabel(placed.getLocation(), type, count);
        restartTask(placed.getLocation(), type, count);
    }

    // -------------------------------------------------------------------------
    // Event: breaking a spawner — cancel task, always drop item manually
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) return;

        String locKey = locKey(block.getLocation());

        // Cancel any running task and remove the label.
        BukkitTask task = tasks.remove(locKey);
        if (task != null) task.cancel();
        removeLabel(locKey);
        stackCounts.remove(locKey);

        // Always suppress vanilla drops — we handle them ourselves (no silk touch required).
        event.setDropItems(false);

        // In creative mode, no drops.
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) return;

        // Resolve count and type from block tile-entity PDC.
        CreatureSpawner cs = (CreatureSpawner) block.getState();
        PersistentDataContainer blockPdc = cs.getPersistentDataContainer();
        int count = blockPdc.getOrDefault(SPAWNER_STACK_KEY, PersistentDataType.INTEGER, 1);

        EntityType type = null;
        String typeStr = blockPdc.get(SPAWNER_TYPE_KEY, PersistentDataType.STRING);
        if (typeStr != null) {
            try { type = EntityType.valueOf(typeStr); } catch (IllegalArgumentException ignored) {}
        }
        if (type == null) type = cs.getSpawnedType(); // fallback for vanilla spawners

        // Drop `count` individual single spawner items.
        ItemStack single = buildSpawnerItem(type, 1);
        Location dropLoc = block.getLocation().add(0.5, 0.5, 0.5);
        for (int i = 0; i < count; i++) {
            block.getWorld().dropItemNaturally(dropLoc, single.clone());
        }
    }

    // -------------------------------------------------------------------------
    // Task management
    // -------------------------------------------------------------------------

    private void restartTask(Location loc, EntityType type, int stackCount) {
        String locKey = locKey(loc);
        BukkitTask old = tasks.remove(locKey);
        if (old != null) old.cancel();

        double rateSeconds = spawnRateConfig.getRate(type.name());
        long periodTicks = Math.max(1L, Math.round(rateSeconds * 20.0));

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Block block = loc.getBlock();
            if (block.getType() != Material.SPAWNER) {
                BukkitTask self = tasks.remove(locKey);
                if (self != null) self.cancel();
                stackCounts.remove(locKey);
                removeLabel(locKey);
                return;
            }
            for (int i = 0; i < stackCount; i++) {
                loc.getWorld().spawnEntity(
                        jitterLocation(loc),
                        type,
                        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER
                );
            }
        }, periodTicks, periodTicks);

        tasks.put(locKey, task);
    }

    // -------------------------------------------------------------------------
    // Label (TextDisplay)
    // -------------------------------------------------------------------------

    private void updateLabel(Location loc, EntityType type, int count) {
        String locKey = locKey(loc);
        removeLabel(locKey);
        if (count <= 1) return;

        String text = "§e§l" + formatMobName(type) + " Spawner §6§lx" + count;
        Component component = LegacyComponentSerializer.legacySection().deserialize(text);

        Location labelLoc = loc.clone().add(0.5, 1.5, 0.5);
        TextDisplay display = (TextDisplay) loc.getWorld().spawnEntity(labelLoc, EntityType.TEXT_DISPLAY);
        display.text(component);
        display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        display.setPersistent(false);
        labels.put(locKey, display.getUniqueId());
    }

    private void removeLabel(String locKey) {
        UUID uid = labels.remove(locKey);
        if (uid == null) return;
        for (World world : plugin.getServer().getWorlds()) {
            org.bukkit.entity.Entity e = world.getEntity(uid);
            if (e != null) { e.remove(); return; }
        }
    }

    // -------------------------------------------------------------------------
    // PDC helpers for spawner items
    // -------------------------------------------------------------------------

    private int getItemStackCount(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 1;
        return meta.getPersistentDataContainer().getOrDefault(SPAWNER_STACK_KEY, PersistentDataType.INTEGER, 1);
    }

    private void setItemStackCount(ItemStack item, int count) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (count <= 1) pdc.remove(SPAWNER_STACK_KEY);
        else            pdc.set(SPAWNER_STACK_KEY, PersistentDataType.INTEGER, count);
        updateItemName(meta, getItemSpawnerType(item));
        item.setItemMeta(meta);
    }

    /**
     * Returns the EntityType for a spawner ItemStack (CHEST material).
     * Reads headhunter:spawner_type PDC. Falls back to BlockStateMeta for
     * vanilla SPAWNER items given by /givespawner before this system existed.
     */
    private static EntityType getItemSpawnerType(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String typeStr = meta.getPersistentDataContainer().get(SPAWNER_TYPE_KEY, PersistentDataType.STRING);
        if (typeStr != null) {
            try { return EntityType.valueOf(typeStr); } catch (IllegalArgumentException ignored) {}
        }
        // Fallback for legacy vanilla SPAWNER items.
        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof CreatureSpawner cs) {
            return cs.getSpawnedType();
        }
        return null;
    }

    private static EntityType getBlockSpawnerType(Block block) {
        if (!(block.getState() instanceof CreatureSpawner cs)) return null;
        String typeStr = cs.getPersistentDataContainer().get(SPAWNER_TYPE_KEY, PersistentDataType.STRING);
        if (typeStr != null) {
            try { return EntityType.valueOf(typeStr); } catch (IllegalArgumentException ignored) {}
        }
        return cs.getSpawnedType();
    }

    static ItemStack buildSpawnerItem(EntityType type, int count, JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.SPAWNER);
        ItemMeta meta = item.getItemMeta();

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (type != null) pdc.set(SPAWNER_TYPE_KEY, PersistentDataType.STRING, type.name());
        if (count > 1)    pdc.set(SPAWNER_STACK_KEY, PersistentDataType.INTEGER, count);

        updateItemName(meta, type);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);

        // Lore
        List<Component> lore = new ArrayList<>();
        ConfigurationSection section = type != null
                ? plugin.getConfig().getConfigurationSection("mobs." + type.name())
                : null;
        String levelStr = (section != null && section.contains("level"))
                ? String.valueOf(section.getInt("level", 0))
                : "N/A";
        lore.add(loreComponent("§fLevel: §e" + levelStr));
        if (section != null) {
            String customDrop = section.getString("custom_drop", "");
            if (customDrop != null && !customDrop.isBlank()) {
                lore.add(loreComponent("§fCustom Drop: §e" + customDrop));
            }
        }
        meta.lore(lore);

        item.setItemMeta(meta);
        item.setAmount(1);
        return item;
    }

    /** Convenience overload used internally. */
    private ItemStack buildSpawnerItem(EntityType type, int count) {
        return buildSpawnerItem(type, count, plugin);
    }

    private static void updateItemName(ItemMeta meta, EntityType type) {
        String mobName = type != null ? formatMobName(type) : "Mob";
        meta.displayName(Component.text(mobName + " Spawner")
                .color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Location jitterLocation(Location base) {
        double ox = (Math.random() - 0.5) * 3.0;
        double oz = (Math.random() - 0.5) * 3.0;
        return base.clone().add(0.5 + ox, 0.5, 0.5 + oz);
    }

    private static String locKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

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

    private static Component msg(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    /** Deserializes a legacy-section string and disables the default italic on lore lines. */
    private static Component loreComponent(String legacyText) {
        return LegacyComponentSerializer.legacySection().deserialize(legacyText)
                .decoration(TextDecoration.ITALIC, false);
    }
}
