package com.lavishmc.headHunter;

import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stores per-player XP and rank level (1–25).
 * Data is persisted to plugins/HeadHunter/playerdata.json and loaded on startup.
 *
 * <p>XP is earned passively by selling heads and acts as the gate for /rankup.
 * The stored level only advances when a player explicitly uses /rankup and pays
 * the money cost — it is never automatically derived from XP.</p>
 *
 * <p>Level thresholds are read from the {@code level-thresholds} section of
 * config.yml so they can be tuned without recompiling.</p>
 */
public class PlayerDataManager {

    public static final int MAX_LEVEL = 25;
    private static final Gson GSON = new Gson();

    /** Gson-serialisable wrapper for the on-disk JSON format. */
    private static class DataStore {
        Map<String, Long>   xp    = new HashMap<>();
        // Declared as Object so Gson never silently coerces integers to Double
        // due to type erasure — values are read via Number.intValue() in load().
        Map<String, Object> level = new HashMap<>();
    }

    private final JavaPlugin plugin;
    private final File dataFile;
    private final HashMap<UUID, Long>    playerXP    = new HashMap<>();
    /** Stored rank level — only changes via {@link #setLevel}. */
    private final HashMap<UUID, Integer> playerLevel = new HashMap<>();

    public PlayerDataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "playerdata.json");
        load();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public long getXP(UUID uuid) {
        return playerXP.getOrDefault(uuid, 0L);
    }

    public void setXP(UUID uuid, long amount) {
        playerXP.put(uuid, Math.max(0, amount));
        save();
    }

    public void addXP(UUID uuid, long amount) {
        playerXP.merge(uuid, amount, Long::sum);
        // Cap XP at the threshold for the next level above the player's stored rank.
        int currentLevel = getLevel(uuid);
        if (currentLevel < MAX_LEVEL) {
            long cap = xpToReachLevel(currentLevel + 1);
            if (playerXP.get(uuid) > cap) {
                playerXP.put(uuid, cap);
            }
        }
        save();
    }

    /**
     * Returns the player's stored rank level (1–25).
     * This only changes when the player uses /rankup — it is never auto-derived.
     */
    public int getLevel(UUID uuid) {
        return playerLevel.getOrDefault(uuid, 1);
    }

    /**
     * Sets the player's stored rank level.  Called exclusively by the /rankup
     * command after all checks pass.
     */
    public void setLevel(UUID uuid, int level) {
        playerLevel.put(uuid, Math.max(1, Math.min(level, MAX_LEVEL)));
        save();
    }

    /**
     * Returns the player's current tier (1–5) based on their stored level.
     * Levels 1-5 = Tier 1, 6-10 = Tier 2, 11-15 = Tier 3, 16-20 = Tier 4, 21-25 = Tier 5.
     */
    public int getTier(UUID uuid) {
        return (getLevel(uuid) - 1) / 5 + 1;
    }

    // -------------------------------------------------------------------------
    // XP math helpers — read thresholds from config
    // -------------------------------------------------------------------------

    /**
     * Total XP needed to reach level {@code n}.
     * Reads {@code level-thresholds.<n>} from config; defaults to 0 if missing.
     */
    public long xpToReachLevel(int n) {
        return plugin.getConfig().getLong("level-thresholds." + n, 0L);
    }

    /**
     * Returns the {@code cost_to_rankup} for whichever mob entry has {@code level == level}.
     * Falls back to 500 if no matching entry is found.
     */
    public long getRankupCost(int level) {
        org.bukkit.configuration.ConfigurationSection mobs =
                plugin.getConfig().getConfigurationSection("mobs");
        if (mobs != null) {
            for (String key : mobs.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection mob =
                        mobs.getConfigurationSection(key);
                if (mob != null && mob.getInt("level", 0) == level) {
                    return mob.getLong("cost_to_rankup", 500L);
                }
            }
        }
        return 500L;
    }

    /**
     * XP span of level {@code n} — i.e. how much XP is needed to advance from
     * level {@code n} to level {@code n+1}.
     */
    public long xpForLevel(int n) {
        if (n >= MAX_LEVEL) return Long.MAX_VALUE;
        return xpToReachLevel(n + 1) - xpToReachLevel(n);
    }

    /**
     * Compute the XP-milestone level (1–{@value MAX_LEVEL}) for a raw XP total.
     * Used for the sell boss-bar progress display and rankup XP gate checks.
     */
    public int levelFromXP(long xp) {
        int level = 1;
        for (int n = 2; n <= MAX_LEVEL; n++) {
            if (xp >= xpToReachLevel(n)) {
                level = n;
            } else {
                break;
            }
        }
        return level;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void load() {
        plugin.getDataFolder().mkdirs();
        plugin.getLogger().info("[HH] Loading playerdata from: " + dataFile.getAbsolutePath());
        if (!dataFile.exists()) {
            plugin.getLogger().info("[HH] playerdata.json not found — starting fresh.");
            return;
        }
        try (Reader reader = new FileReader(dataFile)) {
            DataStore store = GSON.fromJson(reader, DataStore.class);
            if (store == null) {
                plugin.getLogger().warning("[HH] playerdata.json parsed as null — file may be empty or corrupt.");
                return;
            }

            // New format: {"xp":{...}, "level":{...}}
            if (store.xp != null) {
                for (Map.Entry<String, Long> e : store.xp.entrySet()) {
                    tryPutXP(e.getKey(), e.getValue());
                }
            }
            if (store.level != null) {
                // Iterate as raw Object values — Gson can deserialize JSON integers
                // as Double at runtime due to type erasure on Map<String, Integer>.
                // Casting through Number.intValue() handles both Integer and Double.
                for (Map.Entry<String, ?> e : store.level.entrySet()) {
                    if (e.getValue() instanceof Number n) {
                        tryPutLevel(e.getKey(), n.intValue());
                    }
                }
            }

            plugin.getLogger().info("[HH] Loaded playerdata.json — "
                    + playerXP.size() + " XP entries, "
                    + playerLevel.size() + " level entries.");
        } catch (Exception e) {
            plugin.getLogger().warning("[HH] Failed to load playerdata.json: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void save() {
        plugin.getDataFolder().mkdirs();
        DataStore store = new DataStore();
        for (Map.Entry<UUID, Long>    e : playerXP.entrySet())    store.xp.put(e.getKey().toString(), e.getValue());
        for (Map.Entry<UUID, Integer> e : playerLevel.entrySet()) store.level.put(e.getKey().toString(), e.getValue());
        try (Writer writer = new FileWriter(dataFile)) {
            GSON.toJson(store, writer);
            plugin.getLogger().info("[HH] Saved playerdata.json — "
                    + store.xp.size() + " XP entries, "
                    + store.level.size() + " level entries.");
        } catch (IOException e) {
            plugin.getLogger().warning("[HH] Failed to save playerdata.json: " + e.getMessage());
        }
    }

    private void tryPutXP(String key, Long value) {
        if (value == null) return;
        try { playerXP.put(UUID.fromString(key), value); }
        catch (IllegalArgumentException ignored) {}
    }

    private void tryPutLevel(String key, int value) {
        try { playerLevel.put(UUID.fromString(key), value); }
        catch (IllegalArgumentException ignored) {}
    }
}
