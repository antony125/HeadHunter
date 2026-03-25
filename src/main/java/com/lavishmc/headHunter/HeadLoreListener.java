package com.lavishmc.headHunter;

import com.lavishmc.headHunter.DropHeads.events.EntityBeheadEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Adds sell-price and required-level lore to every head drop that has a
 * matching entry in the HeadHunter {@code mobs} config section.
 *
 * <p>Runs at {@link EventPriority#HIGH} with {@code ignoreCancelled = true}
 * so it skips mobs whose head drop was already cancelled (e.g. BANK_NOTE
 * mobs handled by {@link BankNoteDropListener}).</p>
 */
public class HeadLoreListener implements Listener {

    private final JavaPlugin plugin;

    public HeadLoreListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityBehead(EntityBeheadEvent event) {
        String typeName = event.getVictim().getType().name();

        ConfigurationSection section =
                plugin.getConfig().getConfigurationSection("mobs." + typeName);
        if (section == null) return;

        long sellPrice = section.getLong("sell_price", 0);
        int  tier      = section.getInt("tier", 1);

        // Tier 1 → level 1, Tier 2 → level 6, Tier 3 → level 11, etc.
        int requiredLevel = (tier - 1) * 5 + 1;

        ItemStack head = event.getHeadItem();
        if (head == null) return;

        ItemMeta meta = head.getItemMeta();
        if (meta == null) return;

        List<Component> lore = List.of(
                component("§7Required Level: §e" + requiredLevel),
                component("§7Head Price: §a$" + sellPrice)
        );
        meta.lore(lore);
        head.setItemMeta(meta);
    }

    private static Component component(String legacySection) {
        return LegacyComponentSerializer.legacySection().deserialize(legacySection);
    }
}
