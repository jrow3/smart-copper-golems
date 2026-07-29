package com.anantaya.smartcgolem.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;

/**
 * Detects that the golem has stopped making progress toward its walk target.
 *
 * <p>Vanilla pathfinding will happily report a path and then fail to follow it — around a wall the
 * golem cannot climb, for instance. This watches the closest approach so far: any real improvement
 * restarts the clock, and once the golem spends long enough without getting closer it is declared
 * stuck, which is what lets the magic-deposit fallback fire.
 */
final class StuckTracker {

    /**
     * How much closer the golem must get for it to count as progress. Without a margin, ordinary
     * mob jitter would reset the clock forever and the golem would never be declared stuck.
     */
    private static final double PROGRESS_EPSILON_SQR = 0.25D;

    private double closestDistanceToWalkTargetSqr = Double.MAX_VALUE;
    private long startedAt = -1L;

    /** Squared distance from the mob to the centre of {@code walkTarget}, or MAX_VALUE if unset. */
    static double distanceToWalkTargetSqr(PathfinderMob mob, BlockPos walkTarget) {
        if (walkTarget == null) {
            return Double.MAX_VALUE;
        }

        double dx = mob.getX() - (walkTarget.getX() + 0.5D);
        double dy = mob.getY() - walkTarget.getY();
        double dz = mob.getZ() - (walkTarget.getZ() + 0.5D);

        return dx * dx + dy * dy + dz * dz;
    }

    /** Starts the clock over, treating wherever the golem is now as its best approach. */
    void reset(PathfinderMob mob, BlockPos walkTarget, long gameTime) {
        this.startedAt = gameTime;
        this.closestDistanceToWalkTargetSqr = distanceToWalkTargetSqr(mob, walkTarget);
    }

    /**
     * Records this tick's position and reports whether the golem has now gone {@code stuckTicks}
     * without getting meaningfully closer. Call once per tick — it advances the tracking.
     */
    boolean isStuckFor(PathfinderMob mob, BlockPos walkTarget, long gameTime, int stuckTicks) {

        double currentDistance = distanceToWalkTargetSqr(mob, walkTarget);

        if (closestDistanceToWalkTargetSqr == Double.MAX_VALUE) {
            closestDistanceToWalkTargetSqr = currentDistance;
            startedAt = gameTime;
            return false;
        }

        if (currentDistance < closestDistanceToWalkTargetSqr - PROGRESS_EPSILON_SQR) {
            closestDistanceToWalkTargetSqr = currentDistance;
            startedAt = gameTime;
            return false;
        }

        if (startedAt < 0L) {
            startedAt = gameTime;
            return false;
        }

        return gameTime - startedAt >= stuckTicks;
    }

    /** Closest approach recorded so far, for debug output. */
    double closestDistanceSqr() {
        return closestDistanceToWalkTargetSqr;
    }
}
