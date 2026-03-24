package com.lavishmc.headHunter;

import java.util.HashMap;
import java.util.UUID;

/** Stores per-player XP totals earned from selling mob heads. */
public class PlayerDataManager {

    private final HashMap<UUID, Long> playerXP = new HashMap<>();

    public long getXP(UUID uuid) {
        return playerXP.getOrDefault(uuid, 0L);
    }

    public void addXP(UUID uuid, long amount) {
        playerXP.merge(uuid, amount, Long::sum);
    }
}
