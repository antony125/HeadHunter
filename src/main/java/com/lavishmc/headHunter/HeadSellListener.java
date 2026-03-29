package com.lavishmc.headHunter;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Handles right-click head selling and bank-note redemption.
 *
 * <p>Right-click with a configured mob head → sell that stack.<br>
 * Shift + right-click with any configured mob head → sell all matching heads in inventory.<br>
 * Right-click with a bank note → redeem one note for its tier value.</p>
 */
public class HeadSellListener implements Listener {

    /** Every skull/head material in the game. Used by {@link #isMobHead}. */
    private static final Set<Material> HEAD_MATERIALS = Set.of(
            Material.PLAYER_HEAD,
            Material.ZOMBIE_HEAD,
            Material.SKELETON_SKULL,
            Material.WITHER_SKELETON_SKULL,
            Material.CREEPER_HEAD,
            Material.PIGLIN_HEAD,
            Material.DRAGON_HEAD
    );

    private final JavaPlugin plugin;
    private final Economy economy;
    private final PlayerDataManager playerData;
    /** Active XP boss bars keyed by player UUID; replaced on each XP gain. */
    private final HashMap<UUID, BossBar> activeBossBars = new HashMap<>();

    /**
     * @param economy may be {@code null} if Vault is unavailable; money operations
     *                are skipped in that case but XP is still awarded.
     */
    public HeadSellListener(JavaPlugin plugin, Economy economy, PlayerDataManager playerData) {
        this.plugin = plugin;
        this.economy = economy;
        this.playerData = playerData;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only process main-hand right-clicks (Paper fires the event twice).
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) return;

        // ── Bank note redemption ─────────────────────────────────────────────
        if (item.getType() == Material.PAPER) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                Integer tier = meta.getPersistentDataContainer()
                        .get(BankNoteManager.BANKNOTE_TIER_KEY, PersistentDataType.INTEGER);
                if (tier != null) {
                    event.setCancelled(true);
                    redeemBankNote(player, item, tier);
                    return;
                }
            }
        }

        // ── Mob head selling ─────────────────────────────────────────────────
        if (!isMobHead(item)) return;

        String mobType = getMobType(item);
        if (mobType == null) return;
        if (!plugin.getConfig().isConfigurationSection("mobs." + mobType)) return;

        event.setCancelled(true);

        ConfigurationSection section =
                plugin.getConfig().getConfigurationSection("mobs." + mobType);
        if (player.isSneaking()) {
            sellAllHeads(player, item, mobType, section);
        } else {
            sellHeadStack(player, item, mobType, section);
        }
    }

    // -------------------------------------------------------------------------
    // Bank note
    // -------------------------------------------------------------------------

    private void redeemBankNote(Player player, ItemStack item, int tier) {
        int value = plugin.getConfig().getInt("bank-note-values." + tier, 0);
        if (value <= 0) return;

        if (economy != null) economy.depositPlayer(player, value);

        // Remove one note from the stack.
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        player.sendMessage(msg("&aYou redeemed a &6Bank Note &afor &e$" + value));
    }

    // -------------------------------------------------------------------------
    // Single-stack sell
    // -------------------------------------------------------------------------

    private void sellHeadStack(Player player, ItemStack item,
                               String mobType, ConfigurationSection section) {
        int mobLevel    = section.getInt("level", 0);
        int playerLevel = playerData.getLevel(player.getUniqueId());

        if (mobLevel == 0) {
            player.sendMessage(msg("&c&l[!] &e" + formatMobName(mobType) + " Head &fis not defined by level. Cannot sell!"));
            return;
        }

        // Mob is above the player's current level — block the sale entirely.
        if (mobLevel > playerLevel) {
            player.sendMessage(msg("&cYou must be level &e" + mobLevel + " &cto sell this head!"));
            return;
        }

        int count      = item.getAmount();
        long sellPrice = section.getLong("sell_price", 0);
        long xpPerHead = plugin.getConfig().getLong("xp-per-head", 1);
        long totalMoney = sellPrice * count;
        // XP only when mob level exactly matches the player's current level.
        long totalXP = (mobLevel == playerLevel) ? xpPerHead * count : 0;

        if (economy != null) economy.depositPlayer(player, (double) totalMoney);

        long xpBefore = playerData.getXP(player.getUniqueId());
        if (totalXP > 0) playerData.addXP(player.getUniqueId(), totalXP);

        player.getInventory().setItemInMainHand(null);

        String mobName = formatMobName(mobType);
        player.sendMessage(msg(
                "&aYou sold &e" + count + " " + mobName + " Head(s)"
                + " &afor &e$" + totalMoney
                + " &aand &e" + totalXP + " XP"
        ));

        showXpBar(player, xpBefore);
    }

    // -------------------------------------------------------------------------
    // Sell held stack (shift + right-click)
    // -------------------------------------------------------------------------

    private void sellAllHeads(Player player, ItemStack item,
                               String mobType, ConfigurationSection section) {
        int mobLevel    = section.getInt("level", 0);
        int playerLevel = playerData.getLevel(player.getUniqueId());

        if (mobLevel == 0) {
            player.sendMessage(msg("&c&l[!] &e" + formatMobName(mobType) + " Head &fis not defined by level. Cannot sell!"));
            return;
        }

        if (mobLevel > playerLevel) {
            player.sendMessage(msg("&cYou must be level &e" + mobLevel + " &cto sell this head!"));
            return;
        }

        long sellPrice = section.getLong("sell_price", 0);
        long xpPerHead = plugin.getConfig().getLong("xp-per-head", 1);
        boolean awardsXP = mobLevel > 0 && mobLevel == playerLevel;

        // Scan entire inventory for all stacks matching this mob type.
        int totalCount = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack slot = contents[i];
            if (slot == null) continue;
            if (!mobType.equals(getMobType(slot))) continue;
            totalCount += slot.getAmount();
            player.getInventory().setItem(i, null);
        }

        if (totalCount == 0) return;

        long totalMoney = sellPrice * totalCount;
        long totalXP    = awardsXP ? xpPerHead * totalCount : 0;

        if (economy != null) economy.depositPlayer(player, (double) totalMoney);

        long xpBefore = playerData.getXP(player.getUniqueId());
        if (totalXP > 0) playerData.addXP(player.getUniqueId(), totalXP);

        String mobName = formatMobName(mobType);
        player.sendMessage(msg(
                "&aYou sold &e" + totalCount + " " + mobName + " Head(s)"
                + " &afor &e$" + totalMoney
                + " &aand &e" + totalXP + " XP"
        ));

        showXpBar(player, xpBefore);
    }

    // -------------------------------------------------------------------------
    // XP boss bar
    // -------------------------------------------------------------------------

    /**
     * Shows (or replaces) a boss bar displaying XP progress toward the next
     * rankup threshold.  Format: {@code §e§lLevel 5 §8| §b67% §8| §a+50 XP}.
     * The bar is hidden automatically after 3 seconds.
     *
     * @param xpBefore the player's XP total <em>before</em> the most recent gain,
     *                 used to compute how much XP was just awarded
     */
    private void showXpBar(Player player, long xpBefore) {
        UUID uuid = player.getUniqueId();
        long totalXP  = playerData.getXP(uuid);
        long xpGained = totalXP - xpBefore;

        // Use the stored rank level (gated by /rankup), not raw XP calculation.
        int levelNow = playerData.getLevel(uuid);
        int tierNow  = (levelNow - 1) / 5 + 1;

        // XP progress within the current XP milestone level.
        long xpAtCurrentLevel = playerData.xpToReachLevel(levelNow);
        long xpInLevel        = totalXP - xpAtCurrentLevel;
        long xpForLevel       = playerData.xpForLevel(levelNow);
        boolean maxed = levelNow >= PlayerDataManager.MAX_LEVEL;
        int   percent = maxed ? 100 : (int) Math.min(100, (xpInLevel * 100 / xpForLevel));
        float fill    = maxed ? 1.0f : Math.min(1.0f, (float) xpInLevel / xpForLevel);

        // Bar colour by tier: GREEN / YELLOW / WHITE (no GOLD in API) / RED / PURPLE.
        BossBar.Color color = switch (tierNow) {
            case 1  -> BossBar.Color.GREEN;
            case 2  -> BossBar.Color.YELLOW;
            case 3  -> BossBar.Color.WHITE;   // Adventure API has no GOLD
            case 4  -> BossBar.Color.RED;
            case 5  -> BossBar.Color.PURPLE;
            default -> BossBar.Color.WHITE;
        };

        String titleStr = "§e§lLevel " + levelNow + " §8| §b" + percent + "% §8| §a+" + xpGained + " XP";
        Component title = LegacyComponentSerializer.legacySection().deserialize(titleStr);
        BossBar bar = BossBar.bossBar(title, fill, color, BossBar.Overlay.PROGRESS);

        // Replace any bar still visible for this player.
        BossBar existing = activeBossBars.remove(uuid);
        if (existing != null) player.hideBossBar(existing);

        activeBossBars.put(uuid, bar);
        player.showBossBar(bar);

        // Hide the bar after 3 seconds (60 ticks).
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (activeBossBars.get(uuid) == bar) {
                player.hideBossBar(bar);
                activeBossBars.remove(uuid);
            }
        }, 60L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns {@code true} if {@code item} is any head or skull material. */
    private static boolean isMobHead(ItemStack item) {
        return item != null && HEAD_MATERIALS.contains(item.getType());
    }

    private String getMobType(ItemStack item) {
        if (!isMobHead(item)) return null;

        // For non-PLAYER_HEAD vanilla skulls, match by material directly
        if (item.getType() == Material.ZOMBIE_HEAD)           return "ZOMBIE";
        if (item.getType() == Material.SKELETON_SKULL)        return "SKELETON";
        if (item.getType() == Material.WITHER_SKELETON_SKULL) return "WITHER_SKELETON";
        if (item.getType() == Material.CREEPER_HEAD)          return "CREEPER";
        if (item.getType() == Material.PIGLIN_HEAD)           return "PIGLIN";
        if (item.getType() == Material.DRAGON_HEAD)           return "ENDER_DRAGON";

        // PLAYER_HEAD — try display name match first.
        // Sort keys longest-name-first so "Zombie Villager" beats "Zombie", etc.
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String displayName = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            ConfigurationSection mobsSection = plugin.getConfig().getConfigurationSection("mobs");
            if (mobsSection != null) {
                List<String> sortedKeys = new ArrayList<>(mobsSection.getKeys(false));
                sortedKeys.sort((a, b) -> formatMobName(b).length() - formatMobName(a).length());
                for (String mobKey : sortedKeys) {
                    if (displayName.contains(formatMobName(mobKey))) {
                        return mobKey;
                    }
                }
            }
        }

        // No match — silently ignore unrecognized player heads.
        return null;
    }

    /** Converts {@code WITHER_SKELETON} → {@code "Wither Skeleton"}. */
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
}
