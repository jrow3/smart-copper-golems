package com.anantaya.smartcgolem.chest;

import com.anantaya.smartcgolem.config.GolemConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks which chests golems have claimed so two of them do not interact with the same chest at
 * once.
 *
 * <p>Locks are keyed by dimension as well as position. A bare {@link BlockPos} collided across
 * dimensions, so a golem working a Nether chest could block an unrelated Overworld chest sharing its
 * coordinates.
 */
public final class ChestLockRegistry {

    /** A lock older than this is assumed abandoned and may be reclaimed. */
    private static final long LOCK_STALE_TICKS = 600;

    private record ChestKey(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private static final Map<ChestKey, Long> LOCKED_AT = new HashMap<>();

    private ChestLockRegistry() {
    }

    /** Whether the chest is free to claim, treating an abandoned lock as free. */
    public static synchronized boolean isAvailable(ResourceKey<Level> dimension, BlockPos pos, long gameTime) {
        ChestKey key = new ChestKey(dimension, pos.immutable());
        Long lockedAt = LOCKED_AT.get(key);

        if (lockedAt == null) {
            return true;
        }

        if (isStale(lockedAt, gameTime)) {
            GolemConfig.debugLog("[SMART-GOLEM CHEST-LOCK-STALE] clearing stale lock=" + pos);
            LOCKED_AT.remove(key);
            return true;
        }

        return false;
    }

    /** Claims the chest, or refreshes the claim if this caller already holds it. True on success. */
    public static synchronized boolean tryLock(ResourceKey<Level> dimension, BlockPos pos, long gameTime) {
        ChestKey key = new ChestKey(dimension, pos.immutable());
        Long lockedAt = LOCKED_AT.get(key);

        if (lockedAt != null && !isStale(lockedAt, gameTime)) {
            return false;
        }

        LOCKED_AT.put(key, gameTime);
        GolemConfig.debugLog("[SMART-GOLEM CHEST-LOCK] locked=" + pos);
        return true;
    }

    /** Refreshes an existing claim so a long interaction does not go stale under its owner. */
    public static synchronized void refresh(ResourceKey<Level> dimension, BlockPos pos, long gameTime) {
        LOCKED_AT.put(new ChestKey(dimension, pos.immutable()), gameTime);
    }

    public static synchronized void unlock(ResourceKey<Level> dimension, BlockPos pos) {
        if (LOCKED_AT.remove(new ChestKey(dimension, pos.immutable())) != null) {
            GolemConfig.debugLog("[SMART-GOLEM CHEST-UNLOCK] unlocked=" + pos);
        }
    }

    /**
     * Drops every lock. Registered on server stop: these are statics, so on an integrated server they
     * would otherwise survive into the next world loaded, where a leftover timestamp from a world
     * with a higher game time made the staleness check never fire and bricked the chest.
     */
    public static synchronized void clear() {
        LOCKED_AT.clear();
    }

    /**
     * Uses the absolute difference so a timestamp from a different world, which can be far ahead of
     * the current game time, still counts as stale rather than as a lock that never expires.
     */
    private static boolean isStale(long lockedAt, long gameTime) {
        return Math.abs(gameTime - lockedAt) > LOCK_STALE_TICKS;
    }
}
