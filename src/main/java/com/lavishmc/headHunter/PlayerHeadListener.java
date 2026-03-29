package com.lavishmc.headHunter;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerHeadListener implements Listener {

    /** PDC key storing the dead player's UUID (STRING) on a dropped player head. */
    @SuppressWarnings("deprecation") // intentional fixed namespace
    public static final NamespacedKey HEAD_OWNER_KEY =
            new NamespacedKey("headhunter", "player_head_owner");

    /** PDC key storing the 25%-of-balance payout amount (LONG) on a dropped player head. */
    @SuppressWarnings("deprecation") // intentional fixed namespace
    public static final NamespacedKey HEAD_BALANCE_KEY =
            new NamespacedKey("headhunter", "player_head_balance");

    /** Blocked commands (lowercased prefix) during post-death restriction window. */
    private static final List<String> BLOCKED_COMMANDS =
            List.of("/pay", "/sell", "/trade", "/ah", "/auction");

    private final JavaPlugin plugin;
    private final Economy economy;

    /** Maps player UUID → death timestamp (ms). Cleared when restriction expires. */
    private final HashMap<UUID, Long> deathRestrictions = new HashMap<>();

    /** Maps locKey → owner UUID for placed trophy heads. */
    private final Map<String, UUID> placedHeads = new HashMap<>();
    /** Maps locKey → balance snapshot for placed trophy heads. */
    private final Map<String, Long> placedHeadBalances = new HashMap<>();
    /** Maps locKey → TextDisplay UUID for placed trophy head labels. */
    private final Map<String, UUID> placedHeadLabels = new HashMap<>();

    public PlayerHeadListener(JavaPlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;

        // Every 5 seconds: refresh trophy head labels with live bounty data and
        // clean up any entries whose block was removed without a break event.
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (String locKey : new ArrayList<>(placedHeads.keySet())) {
                Location loc = locFromKey(locKey);
                if (loc == null || !isPlayerHead(loc.getBlock().getType())) {
                    removePlacedHeadLabel(locKey);
                    placedHeads.remove(locKey);
                    placedHeadBalances.remove(locKey);
                    continue;
                }
                updatePlacedHeadLabel(locKey, placedHeads.get(locKey),
                        placedHeadBalances.getOrDefault(locKey, 0L));
            }
        }, 100L, 100L);
    }

    // -------------------------------------------------------------------------
    // Head drop on death
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("player-heads.enabled", true)) return;

        Player deadPlayer = event.getEntity();
        boolean pvpOnly = plugin.getConfig().getBoolean("player-heads.drop-on-pvp-only", false);
        if (pvpOnly && deadPlayer.getKiller() == null) return;

        // Record death time for economy command restriction.
        deathRestrictions.put(deadPlayer.getUniqueId(), System.currentTimeMillis());

        if (economy == null) return;

        // Calculate payout: configurable % of balance, minimum 0.
        int percent = plugin.getConfig().getInt("player-heads.balance-percent", 25);
        double balance = economy.getBalance(deadPlayer);
        long payout = (long) Math.max(0, Math.floor(balance * percent / 100.0));

        // Build the head item.
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        PlayerProfile profile = (PlayerProfile) deadPlayer.getPlayerProfile();
        meta.setPlayerProfile(profile);

        Component displayName = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(deadPlayer.getName() + "'s Head")
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, true));

        Component lore = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("Sell to claim bounty or ").color(NamedTextColor.GRAY))
                .append(Component.text("$" + payout).color(NamedTextColor.GREEN))
                .append(Component.text(" (" + percent + "% of balance)").color(NamedTextColor.GRAY));

        meta.displayName(displayName);
        meta.lore(List.of(lore));

        meta.getPersistentDataContainer()
                .set(HEAD_OWNER_KEY, PersistentDataType.STRING, deadPlayer.getUniqueId().toString());
        meta.getPersistentDataContainer()
                .set(HEAD_BALANCE_KEY, PersistentDataType.LONG, payout);

        item.setItemMeta(meta);

        deadPlayer.getWorld().dropItemNaturally(deadPlayer.getLocation(), item);

        // Remove any untagged PLAYER_HEAD drops added by DropHeads or vanilla
        // so only our PDC-tagged head remains in the world.
        event.getDrops().removeIf(drop ->
                drop.getType() == Material.PLAYER_HEAD
                && (drop.getItemMeta() == null
                    || !drop.getItemMeta().getPersistentDataContainer()
                            .has(HEAD_OWNER_KEY, PersistentDataType.STRING)));
    }

    // -------------------------------------------------------------------------
    // Trophy head — place
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() != Material.PLAYER_HEAD) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        String ownerStr = meta.getPersistentDataContainer()
                .get(HEAD_OWNER_KEY, PersistentDataType.STRING);
        if (ownerStr == null) return;
        // Head was placed as a trophy — spawn the TextDisplay label above it.
        UUID ownerUUID;
        try { ownerUUID = UUID.fromString(ownerStr); }
        catch (IllegalArgumentException e) { return; }
        long balance = meta.getPersistentDataContainer()
                .getOrDefault(HEAD_BALANCE_KEY, PersistentDataType.LONG, 0L);
        Location loc = event.getBlockPlaced().getLocation();
        String locKey = locKey(loc);
        placedHeads.put(locKey, ownerUUID);
        placedHeadBalances.put(locKey, balance);
        updatePlacedHeadLabel(locKey, ownerUUID, balance);
    }

    // -------------------------------------------------------------------------
    // Trophy head — break
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isPlayerHead(event.getBlock().getType())) return;

        String locKey = locKey(event.getBlock().getLocation());
        if (!placedHeads.containsKey(locKey)) return;

        event.setDropItems(false);

        UUID ownerUUID = placedHeads.remove(locKey);
        long balanceSnapshot = placedHeadBalances.getOrDefault(locKey, 0L);
        placedHeadBalances.remove(locKey);
        removePlacedHeadLabel(locKey);

        // Rebuild the original tagged head item exactly as in onPlayerDeath.
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        OfflinePlayer victim = Bukkit.getOfflinePlayer(ownerUUID);
        PlayerProfile profile = (PlayerProfile) victim.getPlayerProfile();
        meta.setPlayerProfile(profile);

        String victimName = victim.getName() != null ? victim.getName() : ownerUUID.toString();
        int percent = plugin.getConfig().getInt("player-heads.balance-percent", 25);

        Component displayName = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(victimName + "'s Head")
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, true));

        Component lore = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("Sell to claim bounty or ").color(NamedTextColor.GRAY))
                .append(Component.text("$" + balanceSnapshot).color(NamedTextColor.GREEN))
                .append(Component.text(" (" + percent + "% of balance)").color(NamedTextColor.GRAY));

        meta.displayName(displayName);
        meta.lore(List.of(lore));

        meta.getPersistentDataContainer()
                .set(HEAD_OWNER_KEY, PersistentDataType.STRING, ownerUUID.toString());
        meta.getPersistentDataContainer()
                .set(HEAD_BALANCE_KEY, PersistentDataType.LONG, balanceSnapshot);

        item.setItemMeta(meta);

        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), item);
    }

    // -------------------------------------------------------------------------
    // Player head sell
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onPlayerHeadRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() != Material.PLAYER_HEAD) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        String ownerStr = meta.getPersistentDataContainer()
                .get(HEAD_OWNER_KEY, PersistentDataType.STRING);
        if (ownerStr == null) return;

        // Use getTargetBlock to authoritatively determine if player is
        // looking at a solid block. If yes, let vanilla place the head.
        // If no solid block in range, sell the head.
        Block target = player.getTargetBlock(null, 5);
        if (target != null && target.getType() != Material.AIR) return;

        event.setCancelled(true);
        handlePlayerHeadSell(player, item, ownerStr);
    }

    private void handlePlayerHeadSell(Player seller, ItemStack item, String ownerStr) {
        UUID ownerUUID;
        try {
            ownerUUID = UUID.fromString(ownerStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        if (ownerUUID.equals(seller.getUniqueId())) {
            seller.sendMessage(msg("&cYou cannot sell your own head!"));
            return;
        }

        if (economy == null) {
            seller.sendMessage(msg("&cEconomy is unavailable."));
            return;
        }

        ItemMeta meta = item.getItemMeta();
        long payout = 0;
        if (meta != null) {
            Long stored = meta.getPersistentDataContainer()
                    .get(HEAD_BALANCE_KEY, PersistentDataType.LONG);
            if (stored != null) payout = stored;
        }

        OfflinePlayer victim = Bukkit.getOfflinePlayer(ownerUUID);
        String victimName = victim.getName() != null ? victim.getName() : ownerUUID.toString();

        double victimBalance = economy.getBalance(victim);
        long actualWithdraw = (long) Math.min(payout, Math.floor(victimBalance));
        if (actualWithdraw > 0) economy.withdrawPlayer(victim, (double) actualWithdraw);
        if (actualWithdraw > 0) economy.depositPlayer(seller, (double) actualWithdraw);

        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            seller.getInventory().setItemInMainHand(null);
        }

        seller.sendMessage(msg("&aYou sold &e" + victimName + "'s &ahead for &e$" + actualWithdraw + "&a!"));

        Player onlineVictim = Bukkit.getPlayer(ownerUUID);
        if (onlineVictim != null) {
            onlineVictim.sendMessage(msg("&cYour head was sold! You lost &e$" + actualWithdraw + "&c!"));
        }
    }

    // -------------------------------------------------------------------------
    // Economy command restriction after death
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Long deathTime = deathRestrictions.get(uuid);
        if (deathTime == null) return;

        int restrictionSeconds = plugin.getConfig()
                .getInt("player-heads.death-restriction-seconds", 60);
        long elapsed = System.currentTimeMillis() - deathTime;
        long restrictionMs = restrictionSeconds * 1000L;

        if (elapsed >= restrictionMs) {
            deathRestrictions.remove(uuid);
            return;
        }

        String commandLower = event.getMessage().toLowerCase();
        boolean blocked = BLOCKED_COMMANDS.stream().anyMatch(commandLower::startsWith);
        if (!blocked) return;

        event.setCancelled(true);

        long remainingMs = restrictionMs - elapsed;
        long remainingSeconds = (remainingMs + 999) / 1000; // round up

        player.sendMessage(msg("&cYou cannot use economy commands for &e"
                + remainingSeconds + " seconds &cafter death!"));
    }

    // -------------------------------------------------------------------------
    // Trophy label management
    // -------------------------------------------------------------------------

    private void updatePlacedHeadLabel(String locKey, UUID ownerUUID, long balanceSnapshot) {
        String worldName = locKey.split(",", 2)[0];
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) return;

        // Resolve existing label entity, or spawn a fresh one if absent/gone.
        TextDisplay display = null;
        UUID labelUid = placedHeadLabels.get(locKey);
        if (labelUid != null) {
            org.bukkit.entity.Entity entity = world.getEntity(labelUid);
            if (entity instanceof TextDisplay td) display = td;
        }
        if (display == null) {
            Location loc = locFromKey(locKey);
            if (loc == null) return;
            Location labelLoc = loc.clone().add(0.5, 1.4, 0.5);
            display = (TextDisplay) world.spawnEntity(labelLoc, EntityType.TEXT_DISPLAY);
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(false);
            placedHeadLabels.put(locKey, display.getUniqueId());
        }

        OfflinePlayer victim = Bukkit.getOfflinePlayer(ownerUUID);
        String name = victim.getName() != null ? victim.getName() : ownerUUID.toString();

        long bounty = 0;
        Plugin bountyPlugin = Bukkit.getPluginManager().getPlugin("BountySystem");
        if (bountyPlugin != null && bountyPlugin.isEnabled()) {
            try {
                Object manager = bountyPlugin.getClass()
                        .getMethod("getBountyManager").invoke(bountyPlugin);
                bounty = (long) manager.getClass()
                        .getMethod("getBounty", UUID.class).invoke(manager, ownerUUID);
            } catch (Exception ignored) {}
        }

        String text = bounty > 0
                ? "§e§l" + name + "'s Head §r§7| §a§l$" + bounty + " Bounty"
                : "§e§l" + name + "'s Head §r§7| §f§l$" + balanceSnapshot + " (25%)";

        display.text(LegacyComponentSerializer.legacySection().deserialize(text));
    }

    private void removePlacedHeadLabel(String locKey) {
        UUID uid = placedHeadLabels.remove(locKey);
        if (uid == null) return;
        String worldName = locKey.split(",", 2)[0];
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) return;
        org.bukkit.entity.Entity e = world.getEntity(uid);
        if (e != null) e.remove();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns true for both PLAYER_HEAD (floor) and PLAYER_WALL_HEAD (wall-mounted). */
    private static boolean isPlayerHead(Material m) {
        return m == Material.PLAYER_HEAD || m == Material.PLAYER_WALL_HEAD;
    }

    private static String locKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location locFromKey(String locKey) {
        String[] parts = locKey.split(",", 4);
        if (parts.length != 4) return null;
        World world = plugin.getServer().getWorld(parts[0]);
        if (world == null) return null;
        try {
            return new Location(world,
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Component msg(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }
}
