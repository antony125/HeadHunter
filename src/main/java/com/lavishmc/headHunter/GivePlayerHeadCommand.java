package com.lavishmc.headHunter;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles /giveplayerhead <player> [amount].
 * Requires the {@code headhunter.admin} permission.
 * Gives the command sender a tagged PLAYER_HEAD for the specified player.
 */
public class GivePlayerHeadCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "headhunter.admin";

    @SuppressWarnings("deprecation") // intentional fixed namespace
    private static final NamespacedKey HEAD_OWNER_KEY =
            new NamespacedKey("headhunter", "player_head_owner");

    @SuppressWarnings("deprecation") // intentional fixed namespace
    private static final NamespacedKey HEAD_BALANCE_KEY =
            new NamespacedKey("headhunter", "player_head_balance");

    private final JavaPlugin plugin;

    public GivePlayerHeadCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Command ───────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(msg("&cYou don't have permission to use this command."));
            return true;
        }
        if (!(sender instanceof Player senderPlayer)) {
            sender.sendMessage("This command can only be run by a player.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(msg("&cUsage: /giveplayerhead <player> [amount]"));
            return true;
        }

        // ── Resolve target ────────────────────────────────────────────────────
        String targetName = args[0];
        OfflinePlayer target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            @SuppressWarnings("deprecation") // name lookup — no UUID available
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            if (!offline.hasPlayedBefore()) {
                sender.sendMessage(msg("&cPlayer not found: &e" + targetName));
                return true;
            }
            target = offline;
        }

        // ── Resolve amount ────────────────────────────────────────────────────
        int amount = 1;
        if (args.length >= 2) {
            try {
                amount = Integer.parseInt(args[1]);
                if (amount < 1 || amount > 64) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(msg("&cAmount must be a number between 1 and 64."));
                return true;
            }
        }

        // ── Build head item ───────────────────────────────────────────────────
        String victimName = target.getName() != null ? target.getName() : targetName;

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        PlayerProfile profile = (PlayerProfile) target.getPlayerProfile();
        meta.setPlayerProfile(profile);

        Component displayName = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(victimName + "'s Head")
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, true));

        Component lore = LegacyComponentSerializer.legacyAmpersand()
                .deserialize("&7Sell to claim bounty or &a$0 &7(25% of balance)")
                .decoration(TextDecoration.ITALIC, false);

        meta.displayName(displayName);
        meta.lore(List.of(lore));

        meta.getPersistentDataContainer()
                .set(HEAD_OWNER_KEY, PersistentDataType.STRING, target.getUniqueId().toString());
        meta.getPersistentDataContainer()
                .set(HEAD_BALANCE_KEY, PersistentDataType.LONG, 0L);

        item.setItemMeta(meta);
        item.setAmount(amount);

        // ── Give to sender — drop at feet if inventory is full ────────────────
        var leftover = senderPlayer.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            leftover.values().forEach(stack ->
                    senderPlayer.getWorld().dropItemNaturally(senderPlayer.getLocation(), stack));
        }

        sender.sendMessage(msg("&aGave &e" + amount + "x " + victimName + "'s Head &ato &e"
                + senderPlayer.getName() + "&a."));
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
            return List.of("1", "4", "16", "32", "64");
        }

        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Component msg(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }
}
