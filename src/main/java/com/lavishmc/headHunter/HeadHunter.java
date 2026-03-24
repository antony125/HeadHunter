package com.lavishmc.headHunter;

import com.lavishmc.headHunter.DropHeads.DropHeads;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;

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
        getServer().getPluginManager().registerEvents(new MobDropListener(this), this);
        getServer().getPluginManager().registerEvents(new HeadSellListener(this, economy, playerData), this);
    }
}
