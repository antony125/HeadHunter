package com.lavishmc.headHunter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

import java.util.Map;

/**
 * Handles right-click head selling and bank-note redemption.
 *
 * <p>Right-click with a configured mob head → sell that stack.<br>
 * Shift + right-click with any configured mob head → sell all matching heads in inventory.<br>
 * Right-click with a bank note → redeem one note for its tier value.</p>
 */
public class HeadSellListener implements Listener {

    /** Maps vanilla skull materials to their EntityType name in the mobs config. */
    private static final Map<Material, String> VANILLA_HEAD_TYPES = Map.of(
            Material.ZOMBIE_HEAD,            "ZOMBIE",
            Material.SKELETON_SKULL,         "SKELETON",
            Material.WITHER_SKELETON_SKULL,  "WITHER_SKELETON",
            Material.CREEPER_HEAD,           "CREEPER",
            Material.PIGLIN_HEAD,            "PIGLIN",
            Material.DRAGON_HEAD,            "ENDER_DRAGON"
    );

    private final JavaPlugin plugin;
    private final Economy economy;
    private final PlayerDataManager playerData;

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
        playerData.addXP(player.getUniqueId(), totalXP);

        player.getInventory().setItemInMainHand(null);

        String mobName = formatMobName(mobType);
        player.sendMessage(msg(
                "&aYou sold &e" + count + " " + mobName + " Head(s)"
                + " &afor &e$" + totalMoney
                + " &aand &e" + totalXP + " XP"
        ));
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
        playerData.addXP(player.getUniqueId(), totalXP);

        player.sendMessage(msg(
                "&aYou sold all heads for &e$" + totalMoney
                + " &aand &e" + totalXP + " XP"
        ));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the EntityType name (e.g. {@code "ZOMBIE"}) for a mob head item,
     * or {@code null} if the item is not a recognised mob head.
     */
    private String getMobType(ItemStack item) {
        if (item == null) return null;

        // Vanilla skull materials have a fixed entity type.
        String vanilla = VANILLA_HEAD_TYPES.get(item.getType());
        if (vanilla != null) return vanilla;

        // DropHeads custom heads are PLAYER_HEAD items tagged by MobDropListener.
        if (item.getType() != Material.PLAYER_HEAD) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        return meta.getPersistentDataContainer()
                .get(MobDropListener.MOB_TYPE_KEY, PersistentDataType.STRING);
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
