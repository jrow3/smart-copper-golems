package com.anantaya.smartcgolem.ai;

import com.anantaya.smartcgolem.config.GolemConfig;

import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-golem cooldown on items that could not be delivered anywhere.
 *
 * <p>Without this the golem picks a stack up, fails to route it, puts it back, and immediately picks
 * the same stack up again — a tight loop that never makes progress. Marking the item keeps the golem
 * off it long enough to do something useful.
 *
 * <p>Expiry is lazy: an entry is only dropped when it is next looked at, so there is no sweep and no
 * timer. The map holds at most one entry per distinct item the golem has failed on.
 */
final class UnroutableItemTracker {

    private final Map<Item, Long> unroutableUntilTick = new HashMap<>();

    /** Stops the golem immediately re-grabbing a stack it just proved it cannot deliver. */
    void mark(Item item, long gameTime) {
        int cooldown = GolemConfig.get().unroutableItemCooldownTicks;

        if (cooldown > 0) {
            unroutableUntilTick.put(item, gameTime + cooldown);
        }
    }

    boolean isOnCooldown(Item item, long gameTime) {
        Long until = unroutableUntilTick.get(item);

        if (until == null) {
            return false;
        }

        if (gameTime >= until) {
            unroutableUntilTick.remove(item);
            return false;
        }

        return true;
    }
}
