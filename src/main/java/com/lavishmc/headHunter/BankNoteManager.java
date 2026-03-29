package com.lavishmc.headHunter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** Factory for Bank Note items and the PDC key used to identify them. */
public class BankNoteManager {

    /** PDC key that stores the bank note tier on a PAPER item. */
    @SuppressWarnings("deprecation") // intentional fixed namespace
    public static final NamespacedKey BANKNOTE_TIER_KEY =
            new NamespacedKey("headhunter", "banknote_tier");

    /**
     * Creates a Bank Note item for the given tier.
     * <ul>
     *   <li>Display name: {@code §6§lBank Note §e§l[Tier {tier}]} — non-italic</li>
     *   <li>Lore: {@code §fRedeem for §a§l${value}} — non-italic, value bold</li>
     *   <li>PDC: {@code headhunter:banknote_tier = tier}</li>
     * </ul>
     */
    public static ItemStack createBankNote(JavaPlugin plugin, int tier) {
        int value = plugin.getConfig().getInt("bank-note-values." + tier, 0);

        // §6§lBank Note §e§l[Tier 5]  — all non-italic
        Component displayName = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("Bank Note ")
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true))
                .append(Component.text("[Tier " + tier + "]")
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, true));

        // §fRedeem for §a§l$3000  — non-italic, value bold green
        Component lore = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("Redeem for ")
                        .color(NamedTextColor.WHITE))
                .append(Component.text("$" + value)
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true));

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(displayName);
        meta.lore(List.of(lore));
        meta.getPersistentDataContainer().set(BANKNOTE_TIER_KEY, PersistentDataType.INTEGER, tier);

        item.setItemMeta(meta);
        return item;
    }
}
