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

    @Override
    public void onEvEnable() {
        // Initialise all base DropHeads functionality first.
        super.onEvEnable();

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

        PlayerDataManager playerData = new PlayerDataManager();

        // Register HeadHunter-specific systems.
        getServer().getPluginManager().registerEvents(new MobStackManager(this), this);
        getServer().getPluginManager().registerEvents(new BankNoteDropListener(this), this);
        getServer().getPluginManager().registerEvents(new HeadSellListener(this, economy, playerData), this);

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
    }
}
