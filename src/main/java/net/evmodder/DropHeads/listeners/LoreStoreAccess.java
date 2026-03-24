package net.evmodder.DropHeads.listeners;

import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/** Public bridge to the package-private LoreStoreBlockBreakListener.getItemWithLore(). */
public class LoreStoreAccess {
    public static ItemStack getItemWithLore(Block block) {
        return LoreStoreBlockBreakListener.getItemWithLore(block);
    }
}
