package com.lavishmc.headHunter;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages a per-player scoreboard sidebar that updates every second.
 *
 * Layout:
 *   §a§l[Server Name]
 *   §7Mar 25 2026 9:13 PM
 *   §r
 *   §e[Player IGN]
 *   §d* Balance: $12345
 *   §d* Level: 7
 *   §d* 64% to next level   (or §d* MAX LEVEL at level 25)
 */
public class SidebarManager {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("MMM dd yyyy h:mm a");

    private final JavaPlugin plugin;
    private final PlayerDataManager playerData;
    private final Economy economy;
    private final String serverName;

    /** One dedicated scoreboard per player so lines never bleed between players. */
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    /** Per-player cache of what text is currently registered at each score slot (score → text). */
    private final Map<UUID, Map<Integer, String>> prevLines = new HashMap<>();

    public SidebarManager(JavaPlugin plugin, PlayerDataManager playerData, Economy economy) {
        this.plugin      = plugin;
        this.playerData  = playerData;
        this.economy     = economy;
        this.serverName  = plugin.getConfig().getString("server-name", "HeadHunter");
    }

    /** Start the 1-second repeating update task. Call this from onEvEnable(). */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, 20L, 20L);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    private void updateAll() {
        String dateTime = DATE_FORMAT.format(new Date());

        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player, dateTime);
        }

        // Clean up boards for players who are no longer online.
        boards.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        prevLines.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private void updatePlayer(Player player, String dateTime) {
        UUID uuid = player.getUniqueId();

        ScoreboardManager manager = Bukkit.getScoreboardManager();
        Scoreboard board = boards.computeIfAbsent(uuid, k -> manager.getNewScoreboard());
        Map<Integer, String> lines = prevLines.computeIfAbsent(uuid, k -> new HashMap<>());

        // Create the objective once per player; retrieve it on subsequent ticks.
        Objective obj = board.getObjective("hh_sidebar");
        if (obj == null) {
            obj = board.registerNewObjective(
                    "hh_sidebar", "dummy",
                    "§a§l" + serverName,
                    RenderType.INTEGER
            );
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        // Build player-specific lines.
        int     level = playerData.getLevel(uuid);
        boolean maxed = level >= PlayerDataManager.MAX_LEVEL;
        String xpLine;
        if (maxed) {
            xpLine = "§d§l* §5§lMAX LEVEL";
        } else {
            long totalXP    = playerData.getXP(uuid);
            long xpAtLevel  = playerData.xpToReachLevel(level);
            long xpForLevel = playerData.xpForLevel(level);
            int  percent    = xpForLevel > 0
                    ? (int) Math.min(100, (totalXP - xpAtLevel) * 100 / xpForLevel)
                    : 100;
            xpLine = "§d§l* §5§l" + percent + "% to next level";
        }

        String balanceLine;
        if (economy != null) {
            long bal = (long) economy.getBalance(player);
            balanceLine = "§d§l* §5§lBalance: §f§l$" + fmt(bal);
        } else {
            balanceLine = "§d§l* §5§lBalance: §7§lN/A";
        }

        // Scoreboard lines are set via descending score values (higher = higher on board).
        // setLine only updates a slot when the text actually changes, removing the stale
        // entry first to avoid ghost lines accumulating in the objective.
        setLine(obj, board, lines, "§7" + dateTime,                   6);
        setLine(obj, board, lines, "§r ",                              5);
        setLine(obj, board, lines, "§e§l" + player.getName(),         4);
        setLine(obj, board, lines, balanceLine,                        3);
        setLine(obj, board, lines, "§d§l* §5§lLevel: §f§l" + level,  2);
        setLine(obj, board, lines, xpLine,                             1);

        player.setScoreboard(board);
    }

    private static void setLine(Objective obj, Scoreboard board,
                                Map<Integer, String> lines, String text, int score) {
        String prev = lines.get(score);
        if (text.equals(prev)) return;       // unchanged — nothing to do
        if (prev != null) board.resetScores(prev); // remove stale entry for this slot
        obj.getScore(text).setScore(score);
        lines.put(score, text);
    }

    private static String fmt(long n) {
        if (n >= 1_000_000_000L) return trim(n / 1_000_000_000.0) + "b";
        if (n >= 1_000_000L)     return trim(n / 1_000_000.0)     + "m";
        if (n >= 1_000L)         return trim(n / 1_000.0)         + "k";
        return Long.toString(n);
    }

    /** Returns the number with one decimal place only if the decimal is non-zero. */
    private static String trim(double value) {
        long whole    = (long) value;
        long decimal  = Math.round((value - whole) * 10);
        return decimal == 0 ? Long.toString(whole) : whole + "." + decimal;
    }
}
