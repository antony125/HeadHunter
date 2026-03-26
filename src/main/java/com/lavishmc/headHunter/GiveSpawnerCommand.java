package com.lavishmc.headHunter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles /givespawner <player> <mobtype> [amount].
 * Requires the {@code headhunter.admin} permission.
 */
public class GiveSpawnerCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "headhunter.admin";

    /** Sorted list of all spawnable EntityType names, built once at class load. */
    private static final List<String> ENTITY_NAMES = Arrays.stream(EntityType.values())
            .filter(e -> e.isSpawnable() && e.isAlive())
            .map(Enum::name)
            .sorted()
            .collect(Collectors.toUnmodifiableList());

    private final JavaPlugin plugin;

    public GiveSpawnerCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Command ───────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(msg("&cYou don't have permission to use this command."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(msg("&cUsage: /givespawner <player> <mobtype> [amount]"));
            return true;
        }

        // ── Resolve player ────────────────────────────────────────────────────
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(msg("&cPlayer not found: &e" + args[0]));
            return true;
        }

        // ── Resolve EntityType ────────────────────────────────────────────────
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(msg("&cUnknown entity type: &e" + args[1]
                    + "&c. Use tab completion to see valid types."));
            return true;
        }
        if (!entityType.isSpawnable() || !entityType.isAlive()) {
            sender.sendMessage(msg("&e" + entityType.name() + " &ccannot be used in a spawner."));
            return true;
        }

        // ── Resolve amount ────────────────────────────────────────────────────
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
                if (amount < 1 || amount > 64) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(msg("&cAmount must be a number between 1 and 64."));
                return true;
            }
        }

        // ── Build spawner item (CHEST material, PDC type, lore — no vanilla tooltip) ──
        ItemStack spawner = SpawnerStackManager.buildSpawnerItem(entityType, 1, plugin);
        spawner.setAmount(amount);

        // ── Give to player — drop at feet if inventory is full ────────────────
        var leftover = target.getInventory().addItem(spawner);
        if (!leftover.isEmpty()) {
            leftover.values().forEach(stack ->
                    target.getWorld().dropItemNaturally(target.getLocation(), stack));
        }

        target.sendMessage(msg("&aYou received &e" + amount + "x "
                + formatName(entityType) + " Spawner&a."));
        return true;
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        if (!sender.hasPermission(PERM)) return List.of();

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String partial = args[1].toUpperCase();
            return ENTITY_NAMES.stream()
                    .filter(n -> n.startsWith(partial))
                    .collect(Collectors.toList());
        }

        if (args.length == 3) {
            return List.of("1", "4", "16", "32", "64");
        }

        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Converts WITHER_SKELETON → "Wither Skeleton". */
    private static String formatName(EntityType type) {
        String[] words = type.name().toLowerCase().split("_");
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
}
