package com.lavishmc.headHunter;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Loads and caches SpawnRate.yml from the plugin data folder.
 * Call reload() to re-read the file without restarting.
 */
public class SpawnRateConfig {

    private static final String FILE_NAME = "SpawnRate.yml";
    private static final double DEFAULT_RATE = 5.0;
    private static final double MIN_RATE = 0.05;

    private final JavaPlugin plugin;
    private YamlConfiguration config;

    public SpawnRateConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        saveDefault();
        reload();
    }

    /**
     * Returns the spawn interval in seconds for the given mob type (e.g. "SKELETON").
     * Falls back to the 'default' key, then to {@value DEFAULT_RATE}s if not set.
     */
    public double getRate(String mobType) {
        double rate;
        if (config.contains(mobType)) {
            rate = config.getDouble(mobType, DEFAULT_RATE);
        } else {
            rate = config.getDouble("default", DEFAULT_RATE);
        }
        return Math.max(rate, MIN_RATE);
    }

    /** Re-reads SpawnRate.yml from disk. Safe to call at any time. */
    public void reload() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        config = YamlConfiguration.loadConfiguration(file);

        // Merge any missing defaults from the bundled resource.
        InputStream bundled = plugin.getResource(FILE_NAME);
        if (bundled != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(bundled, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }
    }

    /** Copies SpawnRate.yml from the jar to the data folder if it doesn't exist yet. */
    private void saveDefault() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
    }
}
