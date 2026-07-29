package com.anantaya.smartcgolem.ai;

import com.anantaya.smartcgolem.config.GolemConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Finds candidate chests in the search volume and evaluates how expensive each is to walk to.
 * Stateless: the one piece of golem state the walk-target bias needs ({@code returnToSourceChest})
 * is passed in explicitly rather than read off the behavior.
 */
final class ChestCandidateSource {

    private ChestCandidateSource() {
    }

    /**
     * Cheap squared-distance estimate used to rank candidate chests before
     * we spend any time on real pathfinding. This avoids running
     * createPath() for every block in the search volume.
     */
    static double getRoughDistance(PathfinderMob mob, BlockPos pos) {
        return mob.blockPosition().distSqr(pos);
    }

    /**
     * Collects all candidate positions matching the predicate/filter within
     * the search box, sorted by cheap squared-distance (closest first).
     * Real pathfinding (getPathCost) is only ever evaluated for these
     * candidates in distance order, and we stop as soon as one is reachable
     * and accepted, avoiding O(volume * 27) pathfinding calls.
     */
    static List<BlockPos> collectCandidates(
            ServerLevel level,
            PathfinderMob mob,
            Predicate<BlockState> blockType
    ) {
        BlockPos mobPos = mob.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();

        int hDist = GolemConfig.get().horizontalSearchDistance;
        int vDist = GolemConfig.get().verticalSearchDistance;

        int minX = mobPos.getX() - hDist;
        int maxX = mobPos.getX() + hDist;
        int minY = mobPos.getY() - vDist;
        int maxY = mobPos.getY() + vDist;
        int minZ = mobPos.getZ() - hDist;
        int maxZ = mobPos.getZ() + hDist;

        // Chests are block entities, so rather than probing every block in the box
        // (which also force-loads and generates chunks up to hDist out, synchronously
        // on the server thread), iterate the block-entity map of each already-loaded
        // chunk the box touches. getChunkNow returns null for unloaded chunks instead
        // of loading them. This yields the same matching set as the old box walk at a
        // fraction of the cost, and no longer drags in distant terrain at range 64.
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {

                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);

                if (chunk == null) {
                    continue;
                }

                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {

                    BlockPos pos = entry.getKey();

                    if (pos.getX() < minX || pos.getX() > maxX
                            || pos.getY() < minY || pos.getY() > maxY
                            || pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }

                    if (!blockType.test(entry.getValue().getBlockState())) {
                        continue;
                    }

                    candidates.add(pos.immutable());
                }
            }
        }

        candidates.sort((a, b) -> Double.compare(getRoughDistance(mob, a), getRoughDistance(mob, b)));

        return candidates;
    }

    /**
     * Best reachable stand position adjacent to a chest, and its path cost
     * (Double.MAX_VALUE when nothing nearby can be reached). Bundles what the two
     * former copies — getPathCost and findBestWalkTargetForChest — each computed
     * separately from the same 18 createPath() calls over the 3x2x3 shell.
     */
    record WalkTarget(BlockPos pos, double cost) {}

    /**
     * @param returnToSourceChest the chest the golem intends to return to, or {@code null} when it
     *                            has none; only biases which adjacent stand block is chosen.
     */
    static WalkTarget findWalkTarget(PathfinderMob mob, BlockPos chestPos, BlockPos returnToSourceChest) {

        BlockPos best = chestPos;
        double bestCost = Double.MAX_VALUE;

        for (BlockPos nearby : BlockPos.betweenClosed(
                chestPos.offset(-1, 0, -1),
                chestPos.offset(1, 1, 1))) {

            Path path = mob.getNavigation().createPath(nearby, 1);

            if (path == null || !path.canReach()) {
                continue;
            }

            double cost = path.getNodeCount();

            // Bias the chosen stand position toward the return chest's height so the
            // golem doesn't commit to a target that forces an extra vertical detour.
            // The bias is finite, so it never turns a reachable chest unreachable or
            // vice versa — getPathCost callers only test cost == MAX_VALUE, which is
            // unaffected; it only influences which adjacent block gets picked.
            if (returnToSourceChest != null) {
                cost += Math.abs(nearby.getY() - returnToSourceChest.getY()) * 200;
            }

            if (cost < bestCost) {
                bestCost = cost;
                best = nearby.immutable();
            }
        }

        if (bestCost == Double.MAX_VALUE) {
            GolemConfig.debugLog("[SMART-GOLEM PATH-SKIP] Cannot path near " + chestPos);
        }

        return new WalkTarget(best, bestCost);
    }

    static double getPathCost(PathfinderMob mob, BlockPos chestPos, BlockPos returnToSourceChest) {
        return findWalkTarget(mob, chestPos, returnToSourceChest).cost();
    }

    static BlockPos findBestWalkTargetForChest(PathfinderMob mob, BlockPos chestPos, BlockPos returnToSourceChest) {
        return findWalkTarget(mob, chestPos, returnToSourceChest).pos();
    }
}
