package com.lavishmc.headHunter;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

import java.util.HashMap;
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

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only process main-hand right-clicks (Paper fires the event twice).
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        plugin.getLogger().info("[HH] interact: item=" + player.getInventory().getItemInMainHand().getType() + " isHead=" + HEAD_MATERIALS.contains(player.getInventory().getItemInMainHand().getType()));
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

        if (player.isSneaking()) {
            sellAllHeads(player);
        } else {
            ConfigurationSection section =
                    plugin.getConfig().getConfigurationSection("mobs." + mobType);
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
        int count = item.getAmount();
        long sellPrice = section.getLong("sell_price", 0);
        long xp = section.getLong("xp", 0);

        long totalMoney = sellPrice * count;
        long totalXP    = xp       * count;

        if (economy != null) economy.depositPlayer(player, (double) totalMoney);

        long xpBefore = playerData.getXP(player.getUniqueId());
        playerData.addXP(player.getUniqueId(), totalXP);

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
    // Sell-all (shift + right-click)
    // -------------------------------------------------------------------------

    private void sellAllHeads(Player player) {
        long totalMoney = 0;
        long totalXP    = 0;
        int  totalHeads = 0;

        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null) continue;

            String mobType = getMobType(stack);
            if (mobType == null) continue;

            ConfigurationSection section =
                    plugin.getConfig().getConfigurationSection("mobs." + mobType);
            if (section == null) continue;

            int count = stack.getAmount();
            totalMoney += section.getLong("sell_price", 0) * count;
            totalXP    += section.getLong("xp", 0)        * count;
            totalHeads += count;

            player.getInventory().setItem(i, null);
        }

        if (totalHeads == 0) return;

        if (economy != null) economy.depositPlayer(player, (double) totalMoney);

        long xpBefore = playerData.getXP(player.getUniqueId());
        playerData.addXP(player.getUniqueId(), totalXP);

        player.sendMessage(msg(
                "&aYou sold all heads for &e$" + totalMoney
                + " &aand &e" + totalXP + " XP"
        ));

        showXpBar(player, xpBefore);
    }

    // -------------------------------------------------------------------------
    // XP boss bar
    // -------------------------------------------------------------------------

    /**
     * Shows (or replaces) a boss bar displaying the player's current level and
     * XP progress.  The bar is hidden automatically after 3 seconds.
     *
     * @param xpBefore the player's XP total <em>before</em> the most recent gain,
     *                 used to detect a level-up
     */
    private void showXpBar(Player player, long xpBefore) {
        long totalXP   = playerData.getXP(player.getUniqueId());
        int levelBefore = (int)(xpBefore  / 100);
        int levelNow    = (int)(totalXP   / 100);
        int xpInLevel   = (int)(totalXP   % 100);

        if (levelNow > levelBefore) {
            player.sendMessage(msg("&aLevel up! You are now &elevel " + levelNow));
        }

        Component title = Component.text(
                "Level " + levelNow + " \u2014 " + xpInLevel + "/100 XP",
                NamedTextColor.YELLOW
        );
        BossBar bar = BossBar.bossBar(
                title,
                xpInLevel / 100.0f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS
        );

        // Replace any bar that is still visible for this player.
        BossBar existing = activeBossBars.remove(player.getUniqueId());
        if (existing != null) player.hideBossBar(existing);

        activeBossBars.put(player.getUniqueId(), bar);
        player.showBossBar(bar);

        // Hide the bar after 3 seconds (60 ticks).
        UUID uuid = player.getUniqueId();
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

        // PLAYER_HEAD — try display name match first
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String displayName = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            ConfigurationSection mobsSection = plugin.getConfig().getConfigurationSection("mobs");
            if (mobsSection != null) {
                for (String mobKey : mobsSection.getKeys(false)) {
                    if (displayName.contains(formatMobName(mobKey))) {
                        return mobKey;
                    }
                }
            }
        }

        // Fallback — treat any PLAYER_HEAD as ZOMBIE if ZOMBIE is in config
        if (plugin.getConfig().isConfigurationSection("mobs.ZOMBIE")) return "ZOMBIE";
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
