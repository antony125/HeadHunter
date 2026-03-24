package com.lavishmc.headHunter;

import com.lavishmc.headHunter.DropHeads.DropHeads;

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

        // Then register HeadHunter-specific systems.
        getServer().getPluginManager().registerEvents(new MobStackManager(this), this);
    }
}
