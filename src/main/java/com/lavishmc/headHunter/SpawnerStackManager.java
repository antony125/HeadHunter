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
import org.bukkit.entity.LivingEntity;
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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
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

    /** PDC key used by MobStackManager on living entities — fixed namespace. */
    //noinspection deprecation
    private static final NamespacedKey MOB_STACK_KEY =
            new NamespacedKey("headhunter", "stack_size");

    private final JavaPlugin plugin;
    private final SpawnRateConfig spawnRateConfig;
    private final int maxStack;

    /** Location string → active BukkitTask for that spawner. */
    private final Map<String, BukkitTask> tasks = new HashMap<>();
    /** Location string → current stack count. */
    private final Map<String, Integer> stackCounts = new HashMap<>();
    /** Location string → entity type (mirrors the block PDC; kept in sync for fast save). */
    private final Map<String, EntityType> stackTypes = new HashMap<>();
    /** Location string → UUID of the floating TextDisplay label. */
    private final Map<String, UUID> labels = new HashMap<>();

    public SpawnerStackManager(JavaPlugin plugin, SpawnRateConfig spawnRateConfig) {
        this.plugin = plugin;
        this.spawnRateConfig = spawnRateConfig;
        this.maxStack = plugin.getConfig().getInt("spawner-stack-max", 50);
        load();
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
        stackTypes.put(locKey, placedType);

        // Update block tile-entity PDC to reflect new count.
        CreatureSpawner cs = (CreatureSpawner) clicked.getState();
        cs.getPersistentDataContainer().set(SPAWNER_STACK_KEY, PersistentDataType.INTEGER, newCount);
        cs.update();

        updateLabel(clicked.getLocation(), placedType, newCount);
        restartTask(clicked.getLocation(), placedType, newCount);
        save();

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
        stackTypes.remove(locKey);
        removeLabel(locKey);

        stackCounts.put(locKey, count);
        stackTypes.put(locKey, type);
        updateLabel(placed.getLocation(), type, count);
        restartTask(placed.getLocation(), type, count);
        save();
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
        stackTypes.remove(locKey);
        save();

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
                stackTypes.remove(locKey);
                removeLabel(locKey);
                return;
            }
            // Find an existing stack leader of this type in the spawner's chunk.
            // This covers the post-restart case where stack entities survived but the
            // in-memory MobStackManager map was cleared.
            LivingEntity existingLeader = null;
            for (org.bukkit.entity.Entity e : loc.getChunk().getEntities()) {
                if (!(e instanceof LivingEntity le)) continue;
                if (e.getType() != type || !le.isValid() || le.isDead()) continue;
                if (!le.getPersistentDataContainer().has(MOB_STACK_KEY, PersistentDataType.INTEGER)) continue;
                existingLeader = le;
                break;
            }
            if (existingLeader != null) {
                // Stack exists — grow it by stackCount without spawning new entities.
                int current = existingLeader.getPersistentDataContainer()
                        .getOrDefault(MOB_STACK_KEY, PersistentDataType.INTEGER, 1);
                int next = Math.min(current + stackCount, 9999);
                existingLeader.getPersistentDataContainer()
                        .set(MOB_STACK_KEY, PersistentDataType.INTEGER, next);
                existingLeader.setCustomName("§e§l" + formatMobName(type) + " §f§lx§6§l" + next);
                existingLeader.setCustomNameVisible(true);
            } else {
                // No stack yet — defer to the next tick so MobStackManager's
                // CreatureSpawnEvent handler finishes registering the entity as a
                // leader (size 1) before we override the PDC with stackCount.
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    // Re-check the block is still a spawner before spawning.
                    if (loc.getBlock().getType() != Material.SPAWNER) return;
                    org.bukkit.entity.Entity spawned = loc.getWorld().spawnEntity(
                            jitterLocation(loc),
                            type,
                            org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER
                    );
                    // MobStackManager has now processed the spawn event and set
                    // PDC = 1.  Override with the real batch count.
                    if (spawned instanceof LivingEntity le && stackCount > 1) {
                        le.getPersistentDataContainer()
                                .set(MOB_STACK_KEY, PersistentDataType.INTEGER, stackCount);
                        le.setCustomName("§e§l" + formatMobName(type) + " §f§lx§6§l" + stackCount);
                        le.setCustomNameVisible(true);
                    }
                });
            }
        }, periodTicks, periodTicks);

        tasks.put(locKey, task);
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Cancels all spawner tasks, removes all floating labels, and despawns every
     * living entity in loaded chunks that carries {@code headhunter:stack_size}.
     * Called from {@link HeadHunter#onEvDisable()}.
     */
    public void shutdown() {
        // Persist current state before tearing down.
        save();

        // Cancel all running spawner tasks.
        for (BukkitTask t : tasks.values()) t.cancel();
        tasks.clear();
        stackCounts.clear();
        stackTypes.clear();

        // Remove all floating text labels.
        for (String locKey : new ArrayList<>(labels.keySet())) {
            removeLabel(locKey);
        }

        // Despawn every living entity in loaded chunks with headhunter:stack_size.
        for (World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
                    if (entity instanceof LivingEntity le
                            && le.getPersistentDataContainer()
                                  .has(MOB_STACK_KEY, PersistentDataType.INTEGER)) {
                        le.remove();
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    /** Writes all active stacked spawners to {@code spawners.yml} in the plugin data folder. */
    private void save() {
        File file = new File(plugin.getDataFolder(), "spawners.yml");
        YamlConfiguration config = new YamlConfiguration();

        int idx = 0;
        for (Map.Entry<String, Integer> entry : stackCounts.entrySet()) {
            String locKey = entry.getKey();
            int count = entry.getValue();
            EntityType type = stackTypes.get(locKey);
            if (type == null) continue;

            // locKey format: "world,x,y,z"
            String[] parts = locKey.split(",", 4);
            if (parts.length != 4) continue;

            String base = "spawners." + idx;
            config.set(base + ".world", parts[0]);
            config.set(base + ".x",     Integer.parseInt(parts[1]));
            config.set(base + ".y",     Integer.parseInt(parts[2]));
            config.set(base + ".z",     Integer.parseInt(parts[3]));
            config.set(base + ".type",  type.name());
            config.set(base + ".count", count);
            idx++;
        }

        try {
            plugin.getDataFolder().mkdirs();
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save spawners.yml: " + e.getMessage());
        }
    }

    /**
     * Reads {@code spawners.yml} and re-registers all saved spawners.
     * Called from the constructor; worlds are loaded before plugins enable on a
     * normal server start so block lookups are safe here.
     */
    private void load() {
        File file = new File(plugin.getDataFolder(), "spawners.yml");
        if (!file.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection spawners = config.getConfigurationSection("spawners");
        if (spawners == null) return;

        // Collect valid entries first — purely in-memory work that is safe to do
        // before the first server tick (no entity spawning here).
        record Entry(Location loc, EntityType type, int count) {}
        List<Entry> valid = new ArrayList<>();

        for (String key : spawners.getKeys(false)) {
            ConfigurationSection sec = spawners.getConfigurationSection(key);
            if (sec == null) continue;

            String worldName = sec.getString("world");
            String typeStr   = sec.getString("type");
            int x     = sec.getInt("x");
            int y     = sec.getInt("y");
            int z     = sec.getInt("z");
            int count = sec.getInt("count", 1);

            if (worldName == null || typeStr == null || count <= 1) continue;

            World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("spawners.yml: world '" + worldName + "' not loaded, skipping entry " + key);
                continue;
            }

            EntityType type;
            try { type = EntityType.valueOf(typeStr); }
            catch (IllegalArgumentException e) {
                plugin.getLogger().warning("spawners.yml: unknown entity type '" + typeStr + "', skipping entry " + key);
                continue;
            }

            Location loc = new Location(world, x, y, z);
            if (loc.getBlock().getType() != Material.SPAWNER) continue; // block was broken since last save

            // Register in maps immediately so the data is available to any code
            // that runs before the deferred tick fires.
            String locKey = locKey(loc);
            stackCounts.put(locKey, count);
            stackTypes.put(locKey, type);
            valid.add(new Entry(loc, type, count));
        }

        if (valid.isEmpty()) return;

        // Defer TextDisplay spawning and task registration by 1 tick so the world
        // is fully ticked and ready to accept new entities.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Entry e : valid) {
                updateLabel(e.loc(), e.type(), e.count());
                restartTask(e.loc(), e.type(), e.count());
            }
            plugin.getLogger().info("Restored " + valid.size() + " stacked spawner(s) from spawners.yml.");
        }, 1L);
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
