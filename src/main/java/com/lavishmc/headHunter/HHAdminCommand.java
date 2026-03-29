package com.lavishmc.headHunter;

import com.lavishmc.headHunter.DropHeads.DropHeads;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles all /hh admin subcommands.
 * Requires the {@code headhunter.admin} permission for every subcommand.
 */
public class HHAdminCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "headhunter.admin";

    private static final List<String> SUBCOMMANDS = List.of(
            "give", "setlevel", "setxp", "rankup", "reset", "info", "reload"
    );

    /** Vanilla skull materials keyed by mob type string, matching HeadSellListener. */
    private static final java.util.Map<String, Material> VANILLA_SKULLS = java.util.Map.of(
            "ZOMBIE",           Material.ZOMBIE_HEAD,
            "SKELETON",         Material.SKELETON_SKULL,
            "WITHER_SKELETON",  Material.WITHER_SKELETON_SKULL,
            "CREEPER",          Material.CREEPER_HEAD,
            "PIGLIN",           Material.PIGLIN_HEAD,
            "ENDER_DRAGON",     Material.DRAGON_HEAD
    );

    private final JavaPlugin plugin;
    private final PlayerDataManager playerData;
    private final Economy economy;
    private final SpawnRateConfig spawnRateConfig;

    public HHAdminCommand(JavaPlugin plugin, PlayerDataManager playerData, Economy economy,
                          SpawnRateConfig spawnRateConfig) {
        this.plugin          = plugin;
        this.playerData      = playerData;
        this.economy         = economy;
        this.spawnRateConfig = spawnRateConfig;
    }

    // ── Command dispatch ──────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(msg("&cYou don't have permission to use this command."));
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give"     -> cmdGive(sender, args);
            case "setlevel" -> cmdSetLevel(sender, args);
            case "setxp"    -> cmdSetXp(sender, args);
            case "rankup"   -> cmdRankUp(sender, args);
            case "reset"    -> cmdReset(sender, args);
            case "info"     -> cmdInfo(sender, args);
            case "reload"   -> cmdReload(sender);
            default         -> sendUsage(sender);
        }
        return true;
    }

    // ── Subcommand handlers ───────────────────────────────────────────────────

    /** /hh give <player> <mob> [amount] */
    private void cmdGive(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(msg("&cUsage: /hh give <player> <mob> [amount]"));
            return;
        }
        Player target = resolvePlayer(sender, args[1]);
        if (target == null) return;

        String mobType = args[2].toUpperCase();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("mobs." + mobType);
        if (section == null) {
            sender.sendMessage(msg("&cUnknown mob type: &e" + mobType));
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
                if (amount < 1 || amount > 64) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(msg("&cAmount must be a number between 1 and 64."));
                return;
            }
        }

        ItemStack head = buildHead(mobType, amount);
        target.getInventory().addItem(head);
        sender.sendMessage(msg("&aGave &e" + amount + "x " + formatMobName(mobType)
                + " Head &ato &e" + target.getName() + "&a."));
    }

    /** /hh setlevel <player> <level> */
    private void cmdSetLevel(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(msg("&cUsage: /hh setlevel <player> <level>"));
            return;
        }
        Player target = resolvePlayer(sender, args[1]);
        if (target == null) return;

        int level;
        try {
            level = Integer.parseInt(args[2]);
            if (level < 1 || level > PlayerDataManager.MAX_LEVEL) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(msg("&cLevel must be between 1 and " + PlayerDataManager.MAX_LEVEL + "."));
            return;
        }

        playerData.setLevel(target.getUniqueId(), level);
        sender.sendMessage(msg("&aSet &e" + target.getName() + "&a's level to &e" + level + "&a."));
        target.sendMessage(msg("&aAn admin set your level to &e" + level + "&a."));
    }

    /** /hh setxp <player> <amount> */
    private void cmdSetXp(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(msg("&cUsage: /hh setxp <player> <amount>"));
            return;
        }
        Player target = resolvePlayer(sender, args[1]);
        if (target == null) return;

        long amount;
        try {
            amount = Long.parseLong(args[2]);
            if (amount < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(msg("&cXP amount must be a non-negative number."));
            return;
        }

        playerData.setXP(target.getUniqueId(), amount);
        sender.sendMessage(msg("&aSet &e" + target.getName() + "&a's XP to &e" + amount + "&a."));
        target.sendMessage(msg("&aAn admin set your XP to &e" + amount + "&a."));
    }

    /** /hh rankup <player> — force +1 level, respects MAX_LEVEL cap */
    private void cmdRankUp(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("&cUsage: /hh rankup <player>"));
            return;
        }
        Player target = resolvePlayer(sender, args[1]);
        if (target == null) return;

        UUID uuid = target.getUniqueId();
        int current = playerData.getLevel(uuid);
        if (current >= PlayerDataManager.MAX_LEVEL) {
            sender.sendMessage(msg("&e" + target.getName() + " &cis already at max level."));
            return;
        }

        int next = current + 1;
        playerData.setLevel(uuid, next);
        sender.sendMessage(msg("&aForced &e" + target.getName() + " &ato level &e" + next + "&a."));
        target.sendMessage(msg("&a&lRANK UP! &eAn admin promoted you to level " + next + "!"));
    }

    /** /hh reset <player> — level → 1, XP → 0 */
    private void cmdReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("&cUsage: /hh reset <player>"));
            return;
        }
        Player target = resolvePlayer(sender, args[1]);
        if (target == null) return;

        UUID uuid = target.getUniqueId();
        playerData.setLevel(uuid, 1);
        playerData.setXP(uuid, 0);
        sender.sendMessage(msg("&aReset &e" + target.getName() + "&a's level and XP to defaults."));
        target.sendMessage(msg("&cAn admin reset your level and XP."));
    }

    /** /hh info <player> */
    private void cmdInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("&cUsage: /hh info <player>"));
            return;
        }
        Player target = resolvePlayer(sender, args[1]);
        if (target == null) return;

        UUID uuid  = target.getUniqueId();
        int  level = playerData.getLevel(uuid);
        long xp    = playerData.getXP(uuid);

        String xpNeeded;
        if (level >= PlayerDataManager.MAX_LEVEL) {
            xpNeeded = "MAX LEVEL";
        } else {
            long threshold = playerData.xpToReachLevel(level + 1);
            xpNeeded = (threshold - xp) + " XP needed for level " + (level + 1);
        }

        String balance = economy != null
                ? "$" + String.format("%.2f", economy.getBalance(target))
                : "N/A (Vault unavailable)";

        String costStr;
        if (level >= PlayerDataManager.MAX_LEVEL) {
            costStr = "MAX LEVEL";
        } else {
            costStr = "$" + playerData.getRankupCost(level);
        }

        sender.sendMessage(msg("&e--- " + target.getName() + " ---"));
        sender.sendMessage(msg("&7Level: &f" + level));
        sender.sendMessage(msg("&7XP: &f" + xp));
        sender.sendMessage(msg("&7Progress: &f" + xpNeeded));
        sender.sendMessage(msg("&7Rankup Cost: &f" + costStr));
        sender.sendMessage(msg("&7Balance: &f" + balance));
    }

    /** /hh reload */
    private void cmdReload(CommandSender sender) {
        plugin.reloadConfig();
        if (spawnRateConfig != null) spawnRateConfig.reload();
        sender.sendMessage(msg("&aHeadHunter config reloaded."));
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        if (!sender.hasPermission(PERM)) return List.of();

        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2 && !sub.equals("reload")) {
            return filter(onlinePlayerNames(), args[1]);
        }

        if (args.length == 3) {
            return switch (sub) {
                case "give"     -> filter(mobKeys(), args[2]);
                case "setlevel" -> filter(levelSuggestions(), args[2]);
                case "setxp"    -> List.of("0", "100", "500", "1000");
                default         -> List.of();
            };
        }

        if (args.length == 4 && sub.equals("give")) {
            return filter(List.of("1", "16", "32", "64"), args[3]);
        }

        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a mob head ItemStack with correct texture via the DropHeads API. */
    private ItemStack buildHead(String mobType, int amount) {
        Material mat = VANILLA_SKULLS.getOrDefault(mobType, Material.PLAYER_HEAD);

        // For non-PLAYER_HEAD vanilla skulls, a plain ItemStack already has the right texture.
        if (mat != Material.PLAYER_HEAD) {
            return new ItemStack(mat, amount);
        }

        // Use the DropHeads API to get a correctly textured head.
        try {
            EntityType entityType = EntityType.valueOf(mobType);
            ItemStack item = DropHeads.getPlugin().getAPI().getHead(entityType, mobType);
            if (item != null) {
                item.setAmount(amount);
                return item;
            }
        } catch (Exception ignored) {}

        // Fallback: plain PLAYER_HEAD with display name for HeadSellListener matching.
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection()
                    .deserialize("§f" + formatMobName(mobType) + " Head"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private Player resolvePlayer(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(msg("&cPlayer not found: &e" + name));
        }
        return target;
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
    }

    private List<String> mobKeys() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("mobs");
        if (section == null) return List.of();
        return new ArrayList<>(section.getKeys(false));
    }

    private static List<String> levelSuggestions() {
        List<String> levels = new ArrayList<>();
        for (int i = 1; i <= PlayerDataManager.MAX_LEVEL; i++) levels.add(String.valueOf(i));
        return levels;
    }

    private static List<String> filter(List<String> options, String partial) {
        String lower = partial.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }

    private static String formatMobName(String typeName) {
        String[] words = typeName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1))
                  .append(' ');
            }
        }
        return sb.toString().trim();
    }

    private static Component msg(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage(msg("&e/hh give <player> <mob> [amount]"));
        sender.sendMessage(msg("&e/hh setlevel <player> <level>"));
        sender.sendMessage(msg("&e/hh setxp <player> <amount>"));
        sender.sendMessage(msg("&e/hh rankup <player>"));
        sender.sendMessage(msg("&e/hh reset <player>"));
        sender.sendMessage(msg("&e/hh info <player>"));
        sender.sendMessage(msg("&e/hh reload"));
    }
}
