package com.lavishmc.headHunter;

import com.lavishmc.headHunter.DropHeads.DropHeads;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * HeadHunter — plugin entry point.
 *
 * Extends DropHeads (which extends EvPlugin → JavaPlugin) so that all existing
 * DropHeads lifecycle hooks, static accessors, and API references continue to
 * work without modification. Bukkit instantiates this class; the EvPlugin base
 * class calls onEvEnable() / onEvDisable() from its own onEnable() / onDisable(),
 * which are inherited from DropHeads.
 */
public final class HeadHunter extends DropHeads {

    private static PlayerDataManager playerDataManager;

    /** Returns the shared {@link PlayerDataManager} instance (available after onEnable). */
    public static PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    @Override
    public void onEvEnable() {
        // Initialise all base DropHeads functionality first.
        super.onEvEnable();
        getLogger().info("HeadHunter economy system loaded - HeadSellListener registered");

        // Resolve Vault economy (soft dependency — null if unavailable).
        Economy economy = null;
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp =
                    getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        if (economy == null) {
            getLogger().warning("Vault not found or no economy plugin loaded — head selling disabled.");
        }

        playerDataManager = new PlayerDataManager(this);

        SpawnRateConfig spawnRateConfig = new SpawnRateConfig(this);

        // Register HeadHunter-specific systems.
        getServer().getPluginManager().registerEvents(new MobStackManager(this), this);
        getServer().getPluginManager().registerEvents(new BankNoteDropListener(this), this);
        getServer().getPluginManager().registerEvents(new HeadLoreListener(this), this);
        getServer().getPluginManager().registerEvents(new HeadSellListener(this, economy, playerDataManager), this);
        getServer().getPluginManager().registerEvents(new SunlightProtectionListener(), this);
        getServer().getPluginManager().registerEvents(new SpawnerStackManager(this, spawnRateConfig), this);

        // Start the sidebar scoreboard (updates every second).
        new SidebarManager(this, playerDataManager, economy).start();

        // /hhdebug — prints item-in-hand diagnostics to help verify head detection.
        CommandExecutor hhDebug = (sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be run by a player.");
                return true;
            }

            ItemStack item = player.getInventory().getItemInMainHand();
            ItemMeta meta = item.getItemMeta();

            boolean hasDisplayName = meta != null && meta.hasDisplayName();
            String displayNamePlain = hasDisplayName
                    ? PlainTextComponentSerializer.plainText().serialize(
                            Objects.requireNonNull(meta.displayName()))
                    : "(none)";

            ConfigurationSection mobsSection = getConfig().getConfigurationSection("mobs");
            String mobKeys = mobsSection == null ? "(none)"
                    : mobsSection.getKeys(false).stream()
                            .collect(Collectors.joining(", "));

            player.sendMessage(ChatColor.GOLD + "--- HHDebug ---");
            player.sendMessage(ChatColor.YELLOW + "Material: " + ChatColor.WHITE + item.getType());
            player.sendMessage(ChatColor.YELLOW + "Has display name: " + ChatColor.WHITE + hasDisplayName);
            player.sendMessage(ChatColor.YELLOW + "Display name plain text: " + ChatColor.WHITE + displayNamePlain);
            player.sendMessage(ChatColor.YELLOW + "Mobs config keys: " + ChatColor.WHITE + mobKeys);
            return true;
        };
        Objects.requireNonNull(getCommand("hhdebug")).setExecutor(hhDebug);

        // /givespawner — give a configured spawner item to a player.
        GiveSpawnerCommand giveSpawner = new GiveSpawnerCommand(this);
        Objects.requireNonNull(getCommand("givespawner")).setExecutor(giveSpawner);
        Objects.requireNonNull(getCommand("givespawner")).setTabCompleter(giveSpawner);

        // /hh — admin command suite.
        HHAdminCommand hhAdmin = new HHAdminCommand(this, playerDataManager, economy, spawnRateConfig);
        Objects.requireNonNull(getCommand("hh")).setExecutor(hhAdmin);
        Objects.requireNonNull(getCommand("hh")).setTabCompleter(hhAdmin);

        // /rankup — spend money + XP gate to advance stored rank level.
        RankUpCommand rankUpCommand = new RankUpCommand(this, playerDataManager, economy);
        Objects.requireNonNull(getCommand("rankup")).setExecutor(rankUpCommand);

        // /testlevelup — preview rank-up effects without changing level (op-only).
        Objects.requireNonNull(getCommand("testlevelup")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be run by a player.");
                return true;
            }
            rankUpCommand.runTestEffects(player);
            return true;
        });

        // /hhtest — full item diagnostics: material, display name, meta class, all PDC keys.
        CommandExecutor hhTest = (sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command can only be run by a player.");
                return true;
            }

            ItemStack item = player.getInventory().getItemInMainHand();
            ItemMeta meta  = item.getItemMeta();

            String displayName = (meta != null && meta.hasDisplayName())
                    ? PlainTextComponentSerializer.plainText().serialize(
                            Objects.requireNonNull(meta.displayName()))
                    : "(none)";

            String metaClass = meta != null ? meta.getClass().getName() : "(no meta)";

            String pdcKeys = "(no meta)";
            if (meta != null) {
                pdcKeys = StreamSupport
                        .stream(meta.getPersistentDataContainer().getKeys().spliterator(), false)
                        .map(k -> k.namespace() + ":" + k.key())
                        .collect(Collectors.joining(", "));
                if (pdcKeys.isEmpty()) pdcKeys = "(none)";
            }

            player.sendMessage(ChatColor.GOLD + "--- HHTest ---");
            player.sendMessage(ChatColor.YELLOW + "Material:     " + ChatColor.WHITE + item.getType());
            player.sendMessage(ChatColor.YELLOW + "Display name: " + ChatColor.WHITE + displayName);
            player.sendMessage(ChatColor.YELLOW + "Meta class:   " + ChatColor.WHITE + metaClass);
            player.sendMessage(ChatColor.YELLOW + "PDC keys:     " + ChatColor.WHITE + pdcKeys);
            return true;
        };
        Objects.requireNonNull(getCommand("hhtest")).setExecutor(hhTest);
    }
}
