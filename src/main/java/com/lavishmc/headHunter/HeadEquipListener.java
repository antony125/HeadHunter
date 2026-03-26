package com.lavishmc.headHunter;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Prevents PLAYER_HEAD items from ever being equipped to the helmet slot.
 */
public class HeadEquipListener implements Listener {

    private final JavaPlugin plugin;

    public HeadEquipListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // ── Direct click on an armor slot ─────────────────────────────────────
        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            ItemStack cursor  = event.getCursor();
            ItemStack current = event.getCurrentItem();

            boolean cursorIsHead  = cursor  != null && cursor.getType()  == Material.PLAYER_HEAD;
            boolean currentIsHead = current != null && current.getType() == Material.PLAYER_HEAD;

            if (cursorIsHead || currentIsHead) {
                event.setCancelled(true);
                return;
            }
        }

        // ── Shift-click that would auto-equip to an empty helmet slot ─────────
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            ItemStack current = event.getCurrentItem();
            if (current != null && current.getType() == Material.PLAYER_HEAD) {
                ItemStack helmet = player.getInventory().getHelmet();
                if (helmet == null || helmet.getType() == Material.AIR) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.PLAYER_HEAD) return;

        // Block all three pathways the client uses to trigger the equip.
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        // Safety net: if the head still slips into the helmet slot server-side,
        // yank it out on the next tick and return it to inventory.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            ItemStack helmet = event.getPlayer().getInventory().getHelmet();
            if (helmet != null && helmet.getType() == Material.PLAYER_HEAD) {
                event.getPlayer().getInventory().setHelmet(null);
                event.getPlayer().getInventory().addItem(helmet);
            }
        }, 1L);
    }
}
