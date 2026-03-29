package com.lavishmc.headHunter;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
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
        long rankupCost = playerData.getRankupCost(currentLevel);
        if (economy != null && economy.getBalance(player) < rankupCost) {
            long difference = rankupCost - (long) economy.getBalance(player);
            player.sendMessage(msg("&c&l[!] &fYou need &e$" + difference + " &fmore to rankup to &eLevel " + nextLevel + "&f!"));
            return true;
        }

        // ── All checks passed — execute rankup ───────────────────────────────
        if (economy != null) {
            economy.withdrawPlayer(player, (double) rankupCost);
        }

        int oldTier = playerData.getTier(uuid);
        playerData.setLevel(uuid, nextLevel);
        playerData.setXP(uuid, 0);
        int newTier = playerData.getTier(uuid);

        player.sendMessage(msg("&a&lRANK UP! &eYou are now level " + nextLevel + "!"));
        if (newTier > oldTier) {
            player.sendMessage(msg("&b&lTier " + newTier + " unlocked!"));
        }

        showRankUpBar(player, nextLevel, newTier);
        playRankUpEffects(player, nextLevel, oldTier, newTier);
        return true;
    }

    // -------------------------------------------------------------------------
    // Rank-up effects
    // -------------------------------------------------------------------------

    private void playRankUpEffects(Player player, int newLevel, int oldTier, int newTier) {
        // Title: §6§lRANK UP! / §eYou are now Level X!
        Title title = Title.title(
                LegacyComponentSerializer.legacySection().deserialize("§6§lRANK UP!"),
                LegacyComponentSerializer.legacySection().deserialize("§eYou are now Level " + newLevel + "!"),
                Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofMillis(3000),
                        Duration.ofMillis(1000)
                )
        );
        player.showTitle(title);

        // Totem of undying particle burst.
        player.getWorld().spawnParticle(
                Particle.TOTEM_OF_UNDYING,
                player.getLocation().add(0, 1, 0),
                200, 0.5, 1.0, 0.5, 0.3
        );

        // END_ROD particle burst — sphere radius 2 around the player.
        player.getWorld().spawnParticle(
                Particle.END_ROD,
                player.getLocation().add(0, 1, 0),
                150, 1.5, 1.5, 1.5, 0.1
        );

        // Firework burst at the player's location.
        org.bukkit.Color[] brightColors = {
                org.bukkit.Color.AQUA, org.bukkit.Color.FUCHSIA, org.bukkit.Color.YELLOW,
                org.bukkit.Color.LIME, org.bukkit.Color.RED, org.bukkit.Color.ORANGE
        };
        org.bukkit.Color randomColor = brightColors[(int) (Math.random() * brightColors.length)];

        org.bukkit.entity.Firework firework = player.getWorld().spawn(
                player.getLocation(), org.bukkit.entity.Firework.class);
        org.bukkit.inventory.meta.FireworkMeta meta = firework.getFireworkMeta();
        meta.setPower(1);
        meta.addEffect(org.bukkit.FireworkEffect.builder()
                .withColor(randomColor)
                .with(org.bukkit.FireworkEffect.Type.BALL_LARGE)
                .flicker(true)
                .trail(true)
                .build());
        firework.setFireworkMeta(meta);

        // Server-wide broadcast on tier unlock.
        if (newTier > oldTier) {
            Component broadcast = LegacyComponentSerializer.legacySection().deserialize(
                    "§6§l[!] §e" + player.getName() + " §6has reached §b§lTier " + newTier + "§6! §7\uD83C\uDF89"
            );
            plugin.getServer().getOnlinePlayers().forEach(p -> p.sendMessage(broadcast));
        }
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

    /** Fires all rank-up visual effects on the player without changing their level. */
    public void runTestEffects(Player player) {
        int level = playerData.getLevel(player.getUniqueId());
        int tier  = playerData.getTier(player.getUniqueId());
        playRankUpEffects(player, level, tier - 1, tier);
    }

    private static Component msg(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }
}
