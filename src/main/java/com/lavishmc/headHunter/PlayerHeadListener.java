package com.lavishmc.headHunter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class PlayerHeadListener implements Listener {

    /** PDC key storing the dead player's UUID (STRING) on a dropped player head. */
    @SuppressWarnings("deprecation") // intentional fixed namespace
    public static final NamespacedKey HEAD_OWNER_KEY =
            new NamespacedKey("headhunter", "player_head_owner");

    /** PDC key storing the 25%-of-balance payout amount (LONG) on a dropped player head. */
    @SuppressWarnings("deprecation") // intentional fixed namespace
    public static final NamespacedKey HEAD_BALANCE_KEY =
            new NamespacedKey("headhunter", "player_head_balance");

    /** Blocked commands (lowercased prefix) during post-death restriction window. */
    private static final List<String> BLOCKED_COMMANDS =
            List.of("/pay", "/sell", "/trade", "/ah", "/auction");

    private final JavaPlugin plugin;
    private final Economy economy;

    /** Maps player UUID → death timestamp (ms). Cleared when restriction expires. */
    private final HashMap<UUID, Long> deathRestrictions = new HashMap<>();

    public PlayerHeadListener(JavaPlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    // -------------------------------------------------------------------------
    // Head drop on death
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("player-heads.enabled", true)) return;

        Player deadPlayer = event.getEntity();
        boolean pvpOnly = plugin.getConfig().getBoolean("player-heads.drop-on-pvp-only", false);
        if (pvpOnly && deadPlayer.getKiller() == null) return;

        // Record death time for economy command restriction.
        deathRestrictions.put(deadPlayer.getUniqueId(), System.currentTimeMillis());

        if (economy == null) return;

        // Calculate payout: configurable % of balance, minimum 0.
        int percent = plugin.getConfig().getInt("player-heads.balance-percent", 25);
        double balance = economy.getBalance(deadPlayer);
        long payout = (long) Math.max(0, Math.floor(balance * percent / 100.0));

        // Build the head item.
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        @SuppressWarnings("deprecation") // setOwningPlayer is still the simplest API for this
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        meta.setOwningPlayer(deadPlayer);

        Component displayName = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(deadPlayer.getName() + "'s Head")
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, true));

        Component lore = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("Sell to claim bounty or ").color(NamedTextColor.GRAY))
                .append(Component.text("$" + payout).color(NamedTextColor.GREEN))
                .append(Component.text(" (" + percent + "% of balance)").color(NamedTextColor.GRAY));

        meta.displayName(displayName);
        meta.lore(List.of(lore));

        meta.getPersistentDataContainer()
                .set(HEAD_OWNER_KEY, PersistentDataType.STRING, deadPlayer.getUniqueId().toString());
        meta.getPersistentDataContainer()
                .set(HEAD_BALANCE_KEY, PersistentDataType.LONG, payout);

        item.setItemMeta(meta);

        deadPlayer.getWorld().dropItemNaturally(deadPlayer.getLocation(), item);
    }

    // -------------------------------------------------------------------------
    // Economy command restriction after death
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Long deathTime = deathRestrictions.get(uuid);
        if (deathTime == null) return;

        int restrictionSeconds = plugin.getConfig()
                .getInt("player-heads.death-restriction-seconds", 60);
        long elapsed = System.currentTimeMillis() - deathTime;
        long restrictionMs = restrictionSeconds * 1000L;

        if (elapsed >= restrictionMs) {
            deathRestrictions.remove(uuid);
            return;
        }

        String commandLower = event.getMessage().toLowerCase();
        boolean blocked = BLOCKED_COMMANDS.stream().anyMatch(commandLower::startsWith);
        if (!blocked) return;

        event.setCancelled(true);

        long remainingMs = restrictionMs - elapsed;
        long remainingSeconds = (remainingMs + 999) / 1000; // round up

        player.sendMessage(msg("&cYou cannot use economy commands for &e"
                + remainingSeconds + " seconds &cafter death!"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Component msg(String legacy) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(legacy);
    }
}
