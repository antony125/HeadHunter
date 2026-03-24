package com.lavishmc.headHunter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
     *   <li>Display name: {@code &6Bank Note &e[Tier {tier}]}</li>
     *   <li>Lore: {@code &7Redeem for &e${value}}</li>
     *   <li>PDC: {@code headhunter:banknote_tier = tier}</li>
     * </ul>
     */
    public static ItemStack createBankNote(JavaPlugin plugin, int tier) {
        int value = plugin.getConfig().getInt("bank-note-values." + tier, 0);

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(legacy("&6Bank Note &e[Tier " + tier + "]"));
        meta.lore(List.of(legacy("&7Redeem for &e$" + value)));
        meta.getPersistentDataContainer().set(BANKNOTE_TIER_KEY, PersistentDataType.INTEGER, tier);

        item.setItemMeta(meta);
        return item;
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
