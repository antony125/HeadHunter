package com.lavishmc.headHunter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles /givebanknote <player> <tier> [amount].
 * Requires the {@code headhunter.admin} permission (default: op).
 */
public class GiveBankNoteCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "headhunter.admin";
    private static final int MIN_TIER = 1;
    private static final int MAX_TIER = 5;

    private static final List<String> TIER_COMPLETIONS =
            List.of("1", "2", "3", "4", "5");

    private final JavaPlugin plugin;

    public GiveBankNoteCommand(JavaPlugin plugin) {
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
            sender.sendMessage(msg("&cUsage: /givebanknote <player> <tier> [amount]"));
            return true;
        }

        // ── Resolve player ────────────────────────────────────────────────────
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(msg("&cPlayer not found: &e" + args[0]));
            return true;
        }

        // ── Resolve tier ──────────────────────────────────────────────────────
        int tier;
        try {
            tier = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(msg("&cTier must be a number between &e" + MIN_TIER + "&c and &e" + MAX_TIER + "&c."));
            return true;
        }
        if (tier < MIN_TIER || tier > MAX_TIER) {
            sender.sendMessage(msg("&cTier must be between &e" + MIN_TIER + "&c and &e" + MAX_TIER + "&c."));
            return true;
        }

        // ── Resolve amount (optional, default 1) ──────────────────────────────
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
                if (amount < 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(msg("&cAmount must be a positive number."));
                return true;
            }
        }

        // ── Build and give bank notes ─────────────────────────────────────────
        // createBankNote reads values fresh from config on each call, so /hh reload
        // takes effect immediately without a restart.
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, 64);
            ItemStack note = BankNoteManager.createBankNote(plugin, tier);
            note.setAmount(stackSize);
            var leftover = target.getInventory().addItem(note);
            leftover.values().forEach(stack ->
                    target.getWorld().dropItemNaturally(target.getLocation(), stack));
            remaining -= stackSize;
        }

        target.sendMessage(msg("&aYou received &e" + amount + "x Tier " + tier + " Bank Note"
                + (amount == 1 ? "" : "s") + "&a."));
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
            String partial = args[1];
            return TIER_COMPLETIONS.stream()
                    .filter(t -> t.startsWith(partial))
                    .collect(Collectors.toList());
        }

        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Component msg(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }
}
