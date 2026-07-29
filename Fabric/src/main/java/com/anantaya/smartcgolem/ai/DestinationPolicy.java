package com.anantaya.smartcgolem.ai;

import com.anantaya.smartcgolem.config.GolemConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Chooses which chest the golem should walk to — the source to take from, and the destination to
 * deposit into.
 *
 * <p>Selection only, no inventory moves: the actual slot work is {@link ChestIo}, and gathering the
 * candidates is {@link ChestCandidateSource}. The mutable golem state these passes consult
 * ({@code lastPickupChest}, {@code returnToSourceChest}) is passed in explicitly, and the state they
 * would otherwise write is handed back in {@link Destination} for the behavior to apply.
 */
final class DestinationPolicy {

    private DestinationPolicy() {
    }

    /** What a completed destination search wants recorded about the chest it picked. */
    record Selection(boolean framedMatch, boolean hadReachablePath) {
    }

    /**
     * @param pos       the chosen chest, or {@code null} when nothing suitable was found.
     * @param selection the flags to record, or {@code null} to leave the previous ones alone.
     */
    record Destination(BlockPos pos, Selection selection) {
    }

    /**
     * Nearest reachable chest holding something the golem is currently willing to pick up.
     */
    static BlockPos findSource(
            ServerLevel level,
            PathfinderMob mob,
            Predicate<BlockState> sourceBlockType,
            BlockPos returnToSourceChest,
            UnroutableItemTracker unroutableItems
    ) {

        for (BlockPos pos : ChestCandidateSource.collectCandidates(level, mob, sourceBlockType)) {

            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (!(blockEntity instanceof ChestBlockEntity chest)) {
                continue;
            }

            if (!ChestIo.hasAnyItem(chest, level.getGameTime(), unroutableItems)) {
                continue;
            }

            double pathCost = ChestCandidateSource.getPathCost(mob, pos, returnToSourceChest);

            if (pathCost == Double.MAX_VALUE) {
                continue;
            }

            return pos;
        }

        return null;
    }

    /**
     * Picks where the held item should go, in three passes: a framed chest whose frame matches, then
     * the best unreachable framed match if that is all there was, then an eligible unframed fallback.
     */
    static Destination findDestination(
            ServerLevel level,
            PathfinderMob mob,
            Predicate<BlockState> destinationBlockType,
            BlockPos lastPickupChest,
            BlockPos returnToSourceChest
    ) {

        ItemStack held = mob.getMainHandItem();

        if (held.isEmpty()) {
            return new Destination(null, new Selection(false, true));
        }

        List<BlockPos> candidates = ChestCandidateSource.collectCandidates(level, mob, destinationBlockType);

        // Query every item frame in the search volume once and bucket by the block each is attached
        // to, rather than running getEntitiesOfClass per candidate across both passes below.
        Map<BlockPos, List<ItemFrame>> framesByAttachedPos = FrameMatcher.collectFramesByAttachedPos(level, mob);

        BlockPos unreachableMatch = null;

        for (BlockPos pos : candidates) {

            if (pos.equals(lastPickupChest)) {
                continue;
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (!(blockEntity instanceof ChestBlockEntity chest)) {
                continue;
            }

            Container targetContainer = ChestIo.getActualContainer(level, pos, chest);

            if (ChestIo.isFullFor(targetContainer, held)) {
                GolemConfig.debugLog("[SMART-GOLEM FULL-CHEST] Skipping matching chest because full: " + pos);
                continue;
            }

            FrameMatcher.FrameFilterResult frameFilter =
                    FrameMatcher.getFrameFilterResult(level, pos, held, framesByAttachedPos);

            if (!frameFilter.hasFrame()) {
                continue;
            }

            GolemConfig.debugLog("[SMART-GOLEM MATCH-CHECK] chest=" + pos
                    + " matchedFrameItem=" + frameFilter.matchedFrameItem()
                    + " holding=" + held
                    + " matches=" + frameFilter.matchesHeld());

            if (!frameFilter.matchesHeld()) {
                continue;
            }

            double pathCost = ChestCandidateSource.getPathCost(mob, pos, returnToSourceChest);

            if (pathCost == Double.MAX_VALUE) {
                // Remember it, but keep looking. Candidates are ordered by straight-line distance, so
                // returning here let a walled-off chest a few blocks away permanently shadow a
                // reachable one further out.
                if (unreachableMatch == null) {
                    unreachableMatch = pos;
                    GolemConfig.debugLog("[SMART-GOLEM MATCHED-NO-PATH] Matched framed chest is unreachable, "
                            + "continuing to look for a reachable one. chest=" + pos);
                }
                continue;
            }

            GolemConfig.debugLog("[SMART-GOLEM MATCHED-PATH] Matched framed chest is reachable. chest=" + pos);
            return new Destination(pos, new Selection(true, true));
        }

        if (unreachableMatch != null) {
            // Nothing reachable matched, so fall back to the walled-off chest and let the stuck
            // timeout hand the item over.
            GolemConfig.debugLog("[SMART-GOLEM MATCHED-NO-PATH-ONLY] No reachable match. Will allow magic "
                    + "deposit after stuck timeout. chest=" + unreachableMatch);
            return new Destination(unreachableMatch, new Selection(true, false));
        }

        GolemConfig.debugLog("[SMART-GOLEM FALLBACK-SEARCH] No matching framed chest for "
                + held + ". Searching unfiltered destination chest.");

        for (BlockPos pos : candidates) {

            if (pos.equals(lastPickupChest)) {
                continue;
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (!(blockEntity instanceof ChestBlockEntity chest)) {
                continue;
            }

            FrameMatcher.FrameFilterResult frameFilter =
                    FrameMatcher.getFrameFilterResult(level, pos, held, framesByAttachedPos);

            if (!FrameMatcher.isFallbackEligible(frameFilter, GolemConfig.get().fallbackMode)) {
                GolemConfig.debugLog("[SMART-GOLEM FALLBACK-SKIP] Chest not eligible for fallback: " + pos);
                continue;
            }

            Container targetContainer = ChestIo.getActualContainer(level, pos, chest);

            if (ChestIo.isFullFor(targetContainer, held)) {
                GolemConfig.debugLog("[SMART-GOLEM FULL-CHEST] Skipping fallback chest because full: " + pos);
                continue;
            }

            double pathCost = ChestCandidateSource.getPathCost(mob, pos, returnToSourceChest);

            if (pathCost == Double.MAX_VALUE) {
                continue;
            }

            GolemConfig.debugLog("[SMART-GOLEM FALLBACK-DEPOSIT] Selected unfiltered chest=" + pos);
            return new Destination(pos, new Selection(false, true));
        }

        // A third pass used to live here, meant to deposit back into the source chest as a last
        // resort. It could never run: it required the candidate to equal lastPickupChest, but
        // candidates come from the destination predicate (vanilla chests) while lastPickupChest is
        // always a copper chest. Returning null now routes the golem through RETURN_TO_SOURCE,
        // which puts the item back for real.
        GolemConfig.debugLog("[SMART-GOLEM NO-TARGET] No matching chest and no unfiltered fallback chest found for " + held);

        // Null selection, not Selection(false, true): unlike every other exit above, this path never
        // touched the selection flags, leaving whatever the previous search recorded. Preserved
        // verbatim rather than normalized — see the note on this asymmetry in the project plan.
        return new Destination(null, null);
    }
}
