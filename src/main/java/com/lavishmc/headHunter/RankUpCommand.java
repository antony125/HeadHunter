package com.lavishmc.headHunter;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.UUID;

/**
 * Handles the /rankup (alias /levelup) command.
 *
 * <p>Requirements to rank up:
 * <ol>
 *   <li>Player's total XP must be ≥ the XP threshold for the next level.</li>
 *   <li>Player must have ≥ {@code rankup-cost} money (Vault).</li>
 * </ol>
 * On success, money is deducted and the stored level is incremented by 1.</p>
 */
public class RankUpCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final PlayerDataManager playerData;
    private final Economy economy;
    /** Active rankup boss bars keyed by player UUID. */
    private final HashMap<UUID, BossBar> activeBossBars = new HashMap<>();

    public RankUpCommand(JavaPlugin plugin, PlayerDataManager playerData, Economy economy) {
        this.plugin = plugin;
        this.playerData = playerData;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be run by a player.");
            return true;
        }

        UUID uuid        = player.getUniqueId();
        int  currentLevel = playerData.getLevel(uuid);

        // Already maxed out.
        if (currentLevel >= PlayerDataManager.MAX_LEVEL) {
            player.sendMessage(msg("&cYou are already at the maximum level!"));
            return true;
        }

        int nextLevel = currentLevel + 1;

        // ── XP gate ──────────────────────────────────────────────────────────
        long currentXP  = playerData.getXP(uuid);
        long xpRequired = playerData.xpToReachLevel(nextLevel);
        if (currentXP < xpRequired) {
            player.sendMessage(msg(
                    "&cYou don't have enough XP to rank up! &e"
                    + currentXP + "/" + xpRequired + " XP"));
            return true;
        }

        // ── Money gate ───────────────────────────────────────────────────────
        long rankupCost = plugin.getConfig().getLong("rankup-cost", 500L);
        if (economy != null && economy.getBalance(player) < rankupCost) {
            player.sendMessage(msg("&cYou need &e$" + rankupCost + " &cto rank up!"));
            return true;
        }

        // ── All checks passed — execute rankup ───────────────────────────────
        if (economy != null) {
            economy.withdrawPlayer(player, (double) rankupCost);
        }

        int oldTier = playerData.getTier(uuid);
        playerData.setLevel(uuid, nextLevel);
        int newTier = playerData.getTier(uuid);

        player.sendMessage(msg("&a&lRANK UP! &eYou are now level " + nextLevel + "!"));
        if (newTier > oldTier) {
            player.sendMessage(msg("&b&lTier " + newTier + " unlocked!"));
        }

        showRankUpBar(player, nextLevel, newTier);
        return true;
    }

    // -------------------------------------------------------------------------
    // Boss bar
    // -------------------------------------------------------------------------

    private void showRankUpBar(Player player, int newLevel, int tier) {
        UUID uuid = player.getUniqueId();

        // XP progress within the new stored level toward the next rankup.
        long totalXP          = playerData.getXP(uuid);
        long xpAtCurrentLevel = playerData.xpToReachLevel(newLevel);
        long xpInLevel        = Math.max(0, totalXP - xpAtCurrentLevel);
        long xpForLevel       = playerData.xpForLevel(newLevel);
        boolean maxed = newLevel >= PlayerDataManager.MAX_LEVEL;
        float fill    = maxed ? 1.0f : Math.min(1.0f, (float) xpInLevel / xpForLevel);

        BossBar.Color color = switch (tier) {
            case 1  -> BossBar.Color.GREEN;
            case 2  -> BossBar.Color.YELLOW;
            case 3  -> BossBar.Color.WHITE;   // Adventure API has no GOLD
            case 4  -> BossBar.Color.RED;
            case 5  -> BossBar.Color.PURPLE;
            default -> BossBar.Color.WHITE;
        };

        String titleStr = "§a§lRANK UP! §eLevel " + newLevel
                + (tier > ((newLevel - 2) / 5 + 1) ? " §8| §bTier " + tier + " unlocked!" : "");
        Component title = LegacyComponentSerializer.legacySection().deserialize(titleStr);
        BossBar bar = BossBar.bossBar(title, fill, color, BossBar.Overlay.PROGRESS);

        BossBar existing = activeBossBars.remove(uuid);
        if (existing != null) player.hideBossBar(existing);

        activeBossBars.put(uuid, bar);
        player.showBossBar(bar);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (activeBossBars.get(uuid) == bar) {
                player.hideBossBar(bar);
                activeBossBars.remove(uuid);
            }
        }, 60L);
    }

    private static Component msg(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }
}
