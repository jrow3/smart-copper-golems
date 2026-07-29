package com.anantaya.smartcgolem.ai;

import com.anantaya.smartcgolem.chest.ChestLockRegistry;
import com.anantaya.smartcgolem.config.GolemConfig;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.core.particles.ParticleTypes;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class SmartTransportItemsBehavior extends Behavior<PathfinderMob> {

    private enum TaskState {
        IDLE,
        WALK_TO_SOURCE,
        WAIT_FOR_SOURCE,
        INTERACT_SOURCE,
        WALK_TO_DESTINATION,
        WAIT_FOR_DESTINATION,
        INTERACT_DESTINATION,
        RETURN_TO_SOURCE
    }

    private static final int MAGIC_DEPOSIT_STUCK_TICKS = 100;

    private boolean currentDestinationIsFramedMatch = false;
    private boolean currentDestinationHadReachablePath = true;
    private double closestDistanceToWalkTargetSqr = Double.MAX_VALUE;

    private long stuckStartedAt = -1L;

    private final Predicate<BlockState> sourceBlockType;
    private final Predicate<BlockState> destinationBlockType;
    private final Consumer<PathfinderMob> onTravelling;

    private TaskState taskState = TaskState.IDLE;

    private BlockPos currentTarget = null;
    private BlockPos currentWalkTarget = null;
    private BlockPos lastPickupChest = null;
    private BlockPos returnToSourceChest = null;
    private BlockPos lockedChest = null;
    // Anti-oscillation: the destination we just gave up on this haul. Prevents two
    // mutually-rejecting chests from ping-ponging the golem forever. Cleared on new pickup.
    private BlockPos lastAbandonedDestination = null;

    private Container openedContainer = null;
    private CopperGolem openedCopperGolem = null;

    private boolean actionDone = false;

    private int ticksAtTarget = 0;
    private long nextSearchTick = 0;

    /** Items this golem just failed to route, and the tick each becomes eligible for pickup again. */
    private final Map<Item, Long> unroutableUntilTick = new HashMap<>();

    private static final int ARRIVAL_DISTANCE_SQUARED = 4;
    private static final int SEARCH_COOLDOWN_TICKS = 20;

    public SmartTransportItemsBehavior(
            Predicate<BlockState> sourceBlockType,
            Predicate<BlockState> destinationBlockType,
            Consumer<PathfinderMob> onTravelling
    ) {
        super(ImmutableMap.of(), 200);

        this.sourceBlockType = sourceBlockType;
        this.destinationBlockType = destinationBlockType;
        this.onTravelling = onTravelling;
    }

    @Override
    protected void start(@NonNull ServerLevel level, PathfinderMob mob, long gameTime) {

        boolean carrying = !mob.getMainHandItem().isEmpty();

        currentTarget = null;
        currentWalkTarget = null;
        openedContainer = null;
        openedCopperGolem = null;
        actionDone = false;
        ticksAtTarget = 0;
        taskState = TaskState.IDLE;
        releaseChest(mob);

        if (!carrying) {
            lastPickupChest = null;
            returnToSourceChest = null;
            lastAbandonedDestination = null;
        }

        GolemConfig.debugLog("[SMART-GOLEM START] carrying=" + carrying
                + " returnToSourceChest=" + returnToSourceChest
                + " lastPickupChest=" + lastPickupChest);
    }

    @Override
    protected boolean canStillUse(@NonNull ServerLevel level, PathfinderMob mob, long gameTime) {
        return true;
    }

    private boolean needsChestLock(TaskState state) {
        return state == TaskState.INTERACT_SOURCE
                || state == TaskState.INTERACT_DESTINATION;
    }

    private boolean isChestAvailable(PathfinderMob mob, BlockPos pos, long gameTime) {
        return ChestLockRegistry.isAvailable(mob.level().dimension(), pos, gameTime);
    }

    /** Claims a chest for this golem, refreshing the claim if it already holds it. True on success. */
    private boolean claimChest(PathfinderMob mob, BlockPos pos, long gameTime) {

        if (pos != null && pos.equals(lockedChest)) {
            ChestLockRegistry.refresh(mob.level().dimension(), pos, gameTime);
            return true;
        }

        // Whatever we held is not the chest we are working now, so release it rather than keeping it
        // claimed while we wait on a different one.
        releaseChest(mob);

        if (pos == null) {
            return true;
        }

        if (!ChestLockRegistry.tryLock(mob.level().dimension(), pos, gameTime)) {
            return false;
        }

        lockedChest = pos.immutable();
        return true;
    }

    private void releaseChest(PathfinderMob mob) {
        if (lockedChest == null) {
            return;
        }

        ChestLockRegistry.unlock(mob.level().dimension(), lockedChest);
        lockedChest = null;
    }

    private void switchState(PathfinderMob mob, TaskState newState, BlockPos target, long gameTime) {

        if (needsChestLock(newState)) {
            if (!claimChest(mob, target, gameTime)) {
                GolemConfig.debugLog("[SMART-GOLEM CHEST-LOCK-FAILED] target busy=" + target);

                if (newState == TaskState.INTERACT_SOURCE) {
                    this.taskState = TaskState.WAIT_FOR_SOURCE;
                } else if (newState == TaskState.INTERACT_DESTINATION) {
                    this.taskState = TaskState.WAIT_FOR_DESTINATION;
                } else {
                    this.taskState = TaskState.IDLE;
                }

                this.currentTarget = target;
                this.currentWalkTarget = target == null ? null : findBestWalkTargetForChest(mob, target);

                this.ticksAtTarget = 0;
                this.actionDone = false;
                resetStuckTracking(mob, gameTime);

                if (newState != TaskState.WALK_TO_DESTINATION) {
                    markDestinationSelection(false, true);
                }
                return;
            }
        } else {
            releaseChest(mob);
        }

        this.taskState = newState;
        this.currentTarget = target;
        this.currentWalkTarget = target == null ? null : findBestWalkTargetForChest(mob, target);

        this.ticksAtTarget = 0;
        this.actionDone = false;
        resetStuckTracking(mob, gameTime);

        if (newState != TaskState.WALK_TO_DESTINATION) {
            markDestinationSelection(false, true);
        }

        GolemConfig.debugLog("[SMART-GOLEM STATE] -> " + newState
                + " chestTarget=" + target
                + " walkTarget=" + currentWalkTarget);
    }

    @Override
    protected void tick(@NonNull ServerLevel level, PathfinderMob mob, long gameTime) {

        switch (taskState) {

            case IDLE ->
                searchForTarget(level, mob, gameTime);

            case WALK_TO_SOURCE -> {
                walkToTarget(mob, gameTime);

                if (hasArrived(mob)) {
                    switchState(mob, TaskState.WAIT_FOR_SOURCE, currentTarget, gameTime);
                }
            }

            case WAIT_FOR_SOURCE -> {
                walkToTarget(mob, gameTime);

                if (currentTarget == null) {
                    switchState(mob, TaskState.IDLE, null, gameTime);
                    return;
                }

                BlockEntity blockEntity = level.getBlockEntity(currentTarget);

                if (!(blockEntity instanceof ChestBlockEntity chest)) {
                    switchState(mob, TaskState.IDLE, null, gameTime);
                    return;
                }

                if (isChestAvailable(mob, currentTarget, gameTime) && !isChestBusy(chest)) {
                    switchState(mob, TaskState.INTERACT_SOURCE, currentTarget, gameTime);
                }
            }

            case INTERACT_SOURCE -> interactWithSource(level, mob, gameTime);

            case WALK_TO_DESTINATION -> {
                walkToTarget(mob, gameTime);

                if (tryMagicDepositIfStuck(level, mob, gameTime)) {
                    return;
                }

                if (hasArrived(mob)) {
                    switchState(mob, TaskState.WAIT_FOR_DESTINATION, currentTarget, gameTime);
                }
            }

            case WAIT_FOR_DESTINATION -> {
                walkToTarget(mob, gameTime);

                if (currentTarget == null) {
                    switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
                    return;
                }

                BlockEntity blockEntity = level.getBlockEntity(currentTarget);

                if (!(blockEntity instanceof ChestBlockEntity chest)) {
                    switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
                    return;
                }

                if (isChestAvailable(mob, currentTarget, gameTime) && !isChestBusy(chest)) {
                    switchState(mob, TaskState.INTERACT_DESTINATION, currentTarget, gameTime);
                }
            }

            case INTERACT_DESTINATION -> interactWithDestination(level, mob, gameTime);

            case RETURN_TO_SOURCE -> {
                walkToTarget(mob, gameTime);

                if (hasArrived(mob)) {

                    if (mob instanceof CopperGolem copperGolem) {
                        copperGolem.clearOpenedChestPos();
                        copperGolem.setState(CopperGolemState.IDLE);
                    }

                    returnHeldItemToSource(level, mob, gameTime);

                    GolemConfig.debugLog("[SMART-GOLEM RETURNED-TO-SOURCE] source=" + currentTarget);

                    returnToSourceChest = null;
                    lastPickupChest = null;

                    taskState = TaskState.IDLE;
                    currentTarget = null;
                    currentWalkTarget = null;
                    ticksAtTarget = 0;
                    actionDone = false;
                }
            }
        }
    }

    private void walkToTarget(PathfinderMob mob, long gameTime) {

        if (currentWalkTarget == null) {
            switchState(mob, TaskState.IDLE, null, gameTime);
            return;
        }

        BehaviorUtils.setWalkAndLookTargetMemories(
                mob,
                currentWalkTarget,
                GolemConfig.get().haulSpeed,
                0
        );
    }

    private boolean hasArrived(PathfinderMob mob) {
        return currentWalkTarget != null
                && mob.blockPosition().distSqr(currentWalkTarget)
                <= ARRIVAL_DISTANCE_SQUARED;
    }

    // Thin binders over ChestCandidateSource: they supply the current returnToSourceChest, which is
    // mutable golem state and so must be read at call time rather than captured.
    private double getPathCost(PathfinderMob mob, BlockPos chestPos) {
        return ChestCandidateSource.getPathCost(mob, chestPos, returnToSourceChest);
    }

    private BlockPos findBestWalkTargetForChest(PathfinderMob mob, BlockPos chestPos) {
        return ChestCandidateSource.findBestWalkTargetForChest(mob, chestPos, returnToSourceChest);
    }

    private void interactWithSource(ServerLevel level, PathfinderMob mob, long gameTime) {

        if (currentTarget == null) {
            closeOpenedContainer();
            switchState(mob, TaskState.IDLE, null, gameTime);
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(currentTarget);

        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            GolemConfig.debugLog("[SMART-GOLEM ERROR] Source target is not ChestBlockEntity at " + currentTarget);
            closeOpenedContainer();
            switchState(mob, TaskState.IDLE, null, gameTime);
            return;
        }

        ticksAtTarget++;

        mob.getLookControl().setLookAt(
                currentTarget.getX() + 0.5,
                currentTarget.getY() + 0.5,
                currentTarget.getZ() + 0.5
        );

        if (ticksAtTarget == 1) {

            if (mob instanceof CopperGolem copperGolem) {

                chest.startOpen(copperGolem);
                copperGolem.setOpenedChestPos(currentTarget);

                if (copperGolem.getMainHandItem().isEmpty()) {
                    copperGolem.setState(CopperGolemState.GETTING_ITEM);
                }

                openedContainer = chest;
                openedCopperGolem = copperGolem;

                GolemConfig.debugLog("[SMART-GOLEM VANILLA-OPEN] source=" + currentTarget);
            }

            return;
        }

        if (ticksAtTarget < GolemConfig.get().interactionOpenTicks) {
            return;
        }

        if (!actionDone) {

            mob.swing(InteractionHand.MAIN_HAND);

            boolean movedItem = false;

            if (mob.getMainHandItem().isEmpty()) {
                movedItem = pickupFromChest(level, mob, chest);
            }

            if (mob instanceof CopperGolem copperGolem) {

                if (movedItem) {
                    copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_GET);
                    copperGolem.setState(CopperGolemState.GETTING_ITEM);
                } else {
                    copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_NO_GET);
                    copperGolem.setState(CopperGolemState.GETTING_NO_ITEM);
                }
            }

            actionDone = true;
        }

        if (ticksAtTarget >= GolemConfig.get().interactionCloseTicks) {

            closeOpenedContainer();

            if (!mob.getMainHandItem().isEmpty()) {

                releaseChest(mob);

                BlockPos bestDestination = findDestinationChest(level, mob);

                if (bestDestination != null) {
                    switchState(mob, TaskState.WALK_TO_DESTINATION, bestDestination, gameTime);
                } else {
                    GolemConfig.debugLog("[SMART-GOLEM NO-DESTINATION] No valid destination found after pickup.");
                    switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
                }

                return;
            }

            switchState(mob, TaskState.IDLE, null, gameTime);
        }
    }

    private void interactWithDestination(ServerLevel level, PathfinderMob mob, long gameTime) {

        if (currentTarget == null) {
            closeOpenedContainer();
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(currentTarget);

        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            GolemConfig.debugLog("[SMART-GOLEM ERROR] Destination target is not ChestBlockEntity at " + currentTarget);
            closeOpenedContainer();
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
            return;
        }

        ticksAtTarget++;

        mob.getLookControl().setLookAt(
                currentTarget.getX() + 0.5,
                currentTarget.getY() + 0.5,
                currentTarget.getZ() + 0.5
        );

        if (ticksAtTarget == 1) {

            if (mob instanceof CopperGolem copperGolem) {

                chest.startOpen(copperGolem);
                copperGolem.setOpenedChestPos(currentTarget);

                if (!copperGolem.getMainHandItem().isEmpty()) {
                    copperGolem.setState(CopperGolemState.DROPPING_ITEM);
                }

                openedContainer = chest;
                openedCopperGolem = copperGolem;

                GolemConfig.debugLog("[SMART-GOLEM VANILLA-OPEN] destination=" + currentTarget);
            }

            return;
        }

        if (ticksAtTarget < GolemConfig.get().interactionOpenTicks) {
            return;
        }

        if (!actionDone) {

            mob.swing(InteractionHand.MAIN_HAND);

            boolean movedItem = false;

            if (!mob.getMainHandItem().isEmpty()) {

                ItemStack heldBefore = mob.getMainHandItem().copy();

                GolemConfig.debugLog("[SMART-GOLEM DEPOSIT START] chest=" + currentTarget
                        + " held=" + heldBefore);

                Container targetContainer = getActualContainer(level, currentTarget, chest);

                ItemStack remaining =
                        insertIntoChest(level, currentTarget, targetContainer, heldBefore);

                mob.setItemInHand(InteractionHand.MAIN_HAND, remaining);

                chest.setChanged();
                level.blockEntityChanged(currentTarget);

                movedItem = remaining.getCount() < heldBefore.getCount();

                GolemConfig.debugLog("[SMART-GOLEM DEPOSIT END] chest=" + currentTarget
                        + " remainingHand=" + mob.getMainHandItem());
            }

            if (mob instanceof CopperGolem copperGolem) {

                if (movedItem) {
                    copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_DROP);
                    copperGolem.setState(CopperGolemState.DROPPING_ITEM);
                } else {
                    copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_NO_DROP);
                    copperGolem.setState(CopperGolemState.DROPPING_NO_ITEM);
                }
            }

            actionDone = true;

            closeOpenedContainer();
            releaseChest(mob);


            if (!movedItem && !mob.getMainHandItem().isEmpty()) {

                GolemConfig.debugLog("[SMART-GOLEM DEPOSIT-FAILED] chest=" + currentTarget
                        + " still holding=" + mob.getMainHandItem()
                        + " searching for another destination");

                BlockPos alternateDestination = findDestinationChest(level, mob);

                if (alternateDestination != null
                        && !alternateDestination.equals(currentTarget)
                        && !alternateDestination.equals(lastAbandonedDestination)) {
                    lastAbandonedDestination = currentTarget;
                    switchState(mob, TaskState.WALK_TO_DESTINATION, alternateDestination, gameTime);
                    return;
                }
            }

            BlockPos sourceToReturn =
                    returnToSourceChest != null
                            ? returnToSourceChest
                            : lastPickupChest;

            if (sourceToReturn != null) {

                returnToSourceChest = sourceToReturn;
                switchState(mob, TaskState.RETURN_TO_SOURCE, sourceToReturn, gameTime);

                GolemConfig.debugLog("[SMART-GOLEM RETURN-TO-SOURCE] returning to=" + sourceToReturn);

            } else {

                GolemConfig.debugLog("[SMART-GOLEM RETURN-FAILED] No saved source chest.");

                taskState = TaskState.IDLE;
                currentTarget = null;
            }

        }
    }

    private void searchForTarget(ServerLevel level, PathfinderMob mob, long gameTime) {

        if (gameTime < nextSearchTick) {
            return;
        }

        nextSearchTick = gameTime + SEARCH_COOLDOWN_TICKS;

        boolean carrying = !mob.getMainHandItem().isEmpty();

        BlockPos best = carrying
                ? findDestinationChest(level, mob)
                : findSourceChest(level, mob);

        if (best == null) {
            return;
        }

        openedContainer = null;
        openedCopperGolem = null;

        if (carrying) {
            GolemConfig.debugLog("[SMART-GOLEM MATCHED-DEPOSIT] Selected destination chest=" + best);
            switchState(mob, TaskState.WALK_TO_DESTINATION, best, gameTime);
        } else {
            GolemConfig.debugLog("[SMART-GOLEM SOURCE] Selected source chest=" + best);
            switchState(mob, TaskState.WALK_TO_SOURCE, best, gameTime);
        }
    }

    private BlockPos findSourceChest(ServerLevel level, PathfinderMob mob) {

        for (BlockPos pos : ChestCandidateSource.collectCandidates(level, mob, sourceBlockType)) {

            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (!(blockEntity instanceof ChestBlockEntity chest)) {
                continue;
            }

            if (!hasAnyItem(chest, level.getGameTime())) {
                continue;
            }

            double pathCost = getPathCost(mob, pos);

            if (pathCost == Double.MAX_VALUE) {
                continue;
            }

            return pos;
        }

        return null;
    }

    private BlockPos findDestinationChest(ServerLevel level, PathfinderMob mob) {

        ItemStack held = mob.getMainHandItem();

        if (held.isEmpty()) {
            markDestinationSelection(false, true);
            return null;

        }

        java.util.List<BlockPos> candidates = ChestCandidateSource.collectCandidates(level, mob, destinationBlockType);

        // Query every item frame in the search volume once and bucket by the block each is attached
        // to, rather than running getEntitiesOfClass per candidate across both passes below.
        Map<BlockPos, java.util.List<ItemFrame>> framesByAttachedPos = FrameMatcher.collectFramesByAttachedPos(level, mob);

        BlockPos unreachableMatch = null;

        for (BlockPos pos : candidates) {

            if (pos.equals(lastPickupChest)) {
                continue;
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (!(blockEntity instanceof ChestBlockEntity chest)) {
                continue;
            }

            Container targetContainer = getActualContainer(level, pos, chest);

            if (isFullFor(targetContainer, held)) {
                GolemConfig.debugLog("[SMART-GOLEM FULL-CHEST] Skipping matching chest because full: " + pos);
                continue;
            }

            FrameMatcher.FrameFilterResult frameFilter = FrameMatcher.getFrameFilterResult(level, pos, held, framesByAttachedPos);

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

            double pathCost = getPathCost(mob, pos);

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
            markDestinationSelection(true, true);
            return pos;
        }

        if (unreachableMatch != null) {
            // Nothing reachable matched, so fall back to the walled-off chest and let the stuck
            // timeout hand the item over.
            GolemConfig.debugLog("[SMART-GOLEM MATCHED-NO-PATH-ONLY] No reachable match. Will allow magic "
                    + "deposit after stuck timeout. chest=" + unreachableMatch);
            markDestinationSelection(true, false);
            return unreachableMatch;
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

            FrameMatcher.FrameFilterResult frameFilter = FrameMatcher.getFrameFilterResult(level, pos, held, framesByAttachedPos);

            if (!FrameMatcher.isFallbackEligible(frameFilter, GolemConfig.get().fallbackMode)) {
                GolemConfig.debugLog("[SMART-GOLEM FALLBACK-SKIP] Chest not eligible for fallback: " + pos);
                continue;
            }

            Container targetContainer = getActualContainer(level, pos, chest);

            if (isFullFor(targetContainer, held)) {
                GolemConfig.debugLog("[SMART-GOLEM FULL-CHEST] Skipping fallback chest because full: " + pos);
                continue;
            }

            double pathCost = getPathCost(mob, pos);

            if (pathCost == Double.MAX_VALUE) {
                continue;
            }

            GolemConfig.debugLog("[SMART-GOLEM FALLBACK-DEPOSIT] Selected unfiltered chest=" + pos);
            markDestinationSelection(false, true);
            return pos;
        }

        // A third pass used to live here, meant to deposit back into the source chest as a last
        // resort. It could never run: it required the candidate to equal lastPickupChest, but
        // candidates come from the destination predicate (vanilla chests) while lastPickupChest is
        // always a copper chest. Returning null now routes the golem through RETURN_TO_SOURCE,
        // which puts the item back for real.
        GolemConfig.debugLog("[SMART-GOLEM NO-TARGET] No matching chest and no unfiltered fallback chest found for " + held);

        return null;
    }

    private void closeOpenedContainer() {

        if (openedContainer != null && openedCopperGolem != null) {

            if (openedContainer.getEntitiesWithContainerOpen().contains(openedCopperGolem)) {
                openedContainer.stopOpen(openedCopperGolem);
            }

            openedCopperGolem.clearOpenedChestPos();
            openedCopperGolem.setState(CopperGolemState.IDLE);

            GolemConfig.debugLog("[SMART-GOLEM VANILLA-CLOSE]");
        }

        openedContainer = null;
        openedCopperGolem = null;
    }

    private boolean pickupFromChest(ServerLevel level, PathfinderMob mob, ChestBlockEntity chest) {

        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);

            if (!stack.isEmpty() && !isOnUnroutableCooldown(stack.getItem(), level.getGameTime())) {

                int takeAmount = Math.min(stack.getCount(), stack.getMaxStackSize());
                ItemStack taken = stack.copyWithCount(takeAmount);

                GolemConfig.debugLog("[SMART-GOLEM PICKUP BEFORE] tick=" + level.getGameTime()
                        + " chest=" + currentTarget
                        + " slot=" + i
                        + " stack=" + stack
                        + " mobHand=" + mob.getMainHandItem());

                stack.shrink(takeAmount);

                chest.setChanged();
                level.blockEntityChanged(currentTarget);

                mob.setItemInHand(InteractionHand.MAIN_HAND, taken);

                lastPickupChest = currentTarget;
                returnToSourceChest = currentTarget;

                GolemConfig.debugLog("[SMART-GOLEM RETURN-SOURCE-SAVED] source=" + returnToSourceChest);

                GolemConfig.debugLog("[SMART-GOLEM PICKUP AFTER] tick=" + level.getGameTime()
                        + " chest=" + currentTarget
                        + " slot=" + i
                        + " remainingStack=" + chest.getItem(i)
                        + " mobHand=" + mob.getMainHandItem());

                return true;
            }
        }

        return false;
    }

    /**
     * Puts an item the golem could not route back where it came from.
     *
     * <p>Previously the golem simply kept holding it: RETURN_TO_SOURCE cleared its state without
     * depositing, and the last-resort pass meant to cover this could never run, because its
     * candidates come from the destination predicate (vanilla chests) while the source chest is
     * always a copper chest. The item stayed in the golem's hand and it rescanned once a second
     * forever.
     */
    private void returnHeldItemToSource(ServerLevel level, PathfinderMob mob, long gameTime) {

        ItemStack held = mob.getMainHandItem();

        if (held.isEmpty() || currentTarget == null) {
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(currentTarget);

        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            return;
        }

        Item returned = held.getItem();
        Container container = getActualContainer(level, currentTarget, chest);
        ItemStack leftover = insertIntoChest(level, currentTarget, container, held);

        mob.setItemInHand(InteractionHand.MAIN_HAND, leftover);
        markUnroutable(returned, gameTime);

        GolemConfig.debugLog("[SMART-GOLEM RETURNED-ITEM] source=" + currentTarget
                + " item=" + returned
                + " leftover=" + leftover);
    }

    /** Stops the golem immediately re-grabbing a stack it just proved it cannot deliver. */
    private void markUnroutable(Item item, long gameTime) {
        int cooldown = GolemConfig.get().unroutableItemCooldownTicks;

        if (cooldown > 0) {
            unroutableUntilTick.put(item, gameTime + cooldown);
        }
    }

    private boolean isOnUnroutableCooldown(Item item, long gameTime) {
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

    private ItemStack insertIntoChest(
            ServerLevel level,
            BlockPos chestPos,
            Container chest,
            ItemStack held
    ) {

        ItemStack remaining = held.copy();

        for (int i = 0; i < chest.getContainerSize(); i++) {

            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }

            ItemStack slot = chest.getItem(i);

            if (slot.isEmpty()) {
                continue;
            }

            if (!ItemStack.isSameItemSameComponents(slot, remaining)) {
                continue;
            }

            if (slot.getCount() >= slot.getMaxStackSize()) {
                continue;
            }

            int moveAmount = Math.min(
                    remaining.getCount(),
                    slot.getMaxStackSize() - slot.getCount()
            );

            GolemConfig.debugLog("[SMART-GOLEM STACK-DEPOSIT BEFORE] chest=" + chestPos
                    + " slot=" + i
                    + " slotBefore=" + slot
                    + " remaining=" + remaining
                    + " moveAmount=" + moveAmount);

            slot.grow(moveAmount);
            remaining.shrink(moveAmount);

            chest.setChanged();
            level.blockEntityChanged(chestPos);

            GolemConfig.debugLog("[SMART-GOLEM STACK-DEPOSIT AFTER] chest=" + chestPos
                    + " slot=" + i
                    + " slotAfter=" + chest.getItem(i)
                    + " remaining=" + remaining);
        }

        for (int i = 0; i < chest.getContainerSize(); i++) {

            if (remaining.isEmpty()) {
                return ItemStack.EMPTY;
            }

            ItemStack slot = chest.getItem(i);

            if (!slot.isEmpty()) {
                continue;
            }

            GolemConfig.debugLog("[SMART-GOLEM EMPTY-DEPOSIT BEFORE] chest=" + chestPos
                    + " slot=" + i
                    + " remaining=" + remaining);

            chest.setItem(i, remaining.copy());

            chest.setChanged();
            level.blockEntityChanged(chestPos);

            GolemConfig.debugLog("[SMART-GOLEM EMPTY-DEPOSIT AFTER] chest=" + chestPos
                    + " slot=" + i
                    + " slotAfter=" + chest.getItem(i));

            return ItemStack.EMPTY;
        }

        return remaining;
    }

    private boolean isChestBusy(ChestBlockEntity chest) {
        return !chest.getEntitiesWithContainerOpen().isEmpty();
    }

    private Container getActualContainer(
            ServerLevel level,
            BlockPos pos,
            ChestBlockEntity chest
    ) {

        Container targetContainer = chest;

        if (level.getBlockState(pos).getBlock() instanceof ChestBlock chestBlock) {

            Container combined = ChestBlock.getContainer(
                    chestBlock,
                    level.getBlockState(pos),
                    level,
                    pos,
                    true
            );

            if (combined != null) {
                targetContainer = combined;
            }
        }

        return targetContainer;
    }

    /**
     * Whether the chest has nowhere to put the item: no empty slot, and no matching stack with room
     * left. Named for what it returns; it was previously called hasSpaceFor, which meant the exact
     * opposite of its result.
     */
    private boolean isFullFor(Container chest, ItemStack item) {

        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack slot = chest.getItem(i);

            if (slot.isEmpty()) {
                return false;
            }

            if (ItemStack.isSameItemSameComponents(slot, item)
                    && slot.getCount() < slot.getMaxStackSize()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Whether the chest holds anything this golem would actually pick up right now. Items on the
     * unroutable cooldown do not count, otherwise the golem keeps walking to a chest it is going to
     * refuse to take from.
     */
    private boolean hasAnyItem(ChestBlockEntity chest, long gameTime) {

        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);

            if (!stack.isEmpty() && !isOnUnroutableCooldown(stack.getItem(), gameTime)) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void stop(@NonNull ServerLevel level, PathfinderMob mob, long gameTime) {
        closeOpenedContainer();
        releaseChest(mob);

        ticksAtTarget = 0;
        actionDone = false;


        if (!mob.getMainHandItem().isEmpty()) {
            GolemConfig.debugLog("[SMART-GOLEM STOP-CARRYING] keeping source memory, holding="
                    + mob.getMainHandItem()
                    + " returnToSourceChest=" + returnToSourceChest);

            taskState = TaskState.IDLE;
            currentTarget = null;
            currentWalkTarget = null;

            onTravelling.accept(mob);
            return;
        }

        if (returnToSourceChest != null) {
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);

            GolemConfig.debugLog("[SMART-GOLEM STOP-KEEP-RETURN] returning to=" + returnToSourceChest);
            onTravelling.accept(mob);
            return;
        }

        taskState = TaskState.IDLE;
        currentTarget = null;
        currentWalkTarget = null;

        onTravelling.accept(mob);
    }

    private void markDestinationSelection(boolean framedMatch, boolean hadReachablePath) {
        this.currentDestinationIsFramedMatch = framedMatch;
        this.currentDestinationHadReachablePath = hadReachablePath;
    }

    private void resetStuckTracking(PathfinderMob mob, long gameTime) {
        this.stuckStartedAt = gameTime;

        if (currentWalkTarget == null) {
            this.closestDistanceToWalkTargetSqr = Double.MAX_VALUE;
        } else {
            this.closestDistanceToWalkTargetSqr = distanceToCurrentWalkTargetSqr(mob);
        }
    }

    private boolean tryMagicDepositIfStuck(ServerLevel level, PathfinderMob mob, long gameTime) {

        if (!currentDestinationIsFramedMatch) {
            return false;
        }

        if (currentTarget == null || currentWalkTarget == null) {
            return false;
        }

        if (mob.getMainHandItem().isEmpty()) {
            return false;
        }

        if (hasArrived(mob)) {
            resetStuckTracking(mob, gameTime);
            return false;
        }

        double currentDistance = distanceToCurrentWalkTargetSqr(mob);

        if (closestDistanceToWalkTargetSqr == Double.MAX_VALUE) {
            closestDistanceToWalkTargetSqr = currentDistance;
            stuckStartedAt = gameTime;
            return false;
        }


        if (currentDistance < closestDistanceToWalkTargetSqr - 0.25D) {
            closestDistanceToWalkTargetSqr = currentDistance;
            stuckStartedAt = gameTime;
            return false;
        }

        if (stuckStartedAt < 0L) {
            stuckStartedAt = gameTime;
            return false;
        }

        if (gameTime - stuckStartedAt < MAGIC_DEPOSIT_STUCK_TICKS) {
            return false;
        }

        GolemConfig.debugLog("[SMART-GOLEM MAGIC-DEPOSIT-TRY] target=" + currentTarget
                + " holding=" + mob.getMainHandItem()
                + " reachableAtSearch=" + currentDestinationHadReachablePath
                + " distanceSqr=" + currentDistance
                + " bestDistanceSqr=" + closestDistanceToWalkTargetSqr);

        return magicDepositIntoMatchedChest(level, mob, gameTime);
    }

    private boolean magicDepositIntoMatchedChest(ServerLevel level, PathfinderMob mob, long gameTime) {

        BlockPos depositTarget = currentTarget;

        BlockEntity blockEntity = level.getBlockEntity(depositTarget);

        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            GolemConfig.debugLog("[SMART-GOLEM MAGIC-DEPOSIT-CANCEL] Target is not chest: " + depositTarget);
            markDestinationSelection(false, true);
            switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
            return true;
        }

        if (isChestBusy(chest)) {
            GolemConfig.debugLog("[SMART-GOLEM MAGIC-DEPOSIT-WAIT] Chest busy: " + depositTarget);
            resetStuckTracking(mob, gameTime);
            return false;
        }

        ItemStack heldBefore = mob.getMainHandItem().copy();

        if (heldBefore.isEmpty()) {
            return false;
        }

        FrameMatcher.FrameFilterResult frameFilter = FrameMatcher.getFrameFilterResult(level, depositTarget, heldBefore);

        if (!frameFilter.hasFrame() || !frameFilter.matchesHeld()) {

            GolemConfig.debugLog("[SMART-GOLEM MAGIC-DEPOSIT-CANCEL] No matching frame anymore. chest="
                    + depositTarget
                    + " matchedFrameItem=" + frameFilter.matchedFrameItem()
                    + " held=" + heldBefore);

            markDestinationSelection(false, true);

            BlockPos alternateDestination = findDestinationChest(level, mob);

            if (alternateDestination != null
                    && !alternateDestination.equals(depositTarget)
                    && !alternateDestination.equals(lastAbandonedDestination)) {
                lastAbandonedDestination = depositTarget;
                switchState(mob, TaskState.WALK_TO_DESTINATION, alternateDestination, gameTime);
            } else {
                switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
            }

            return true;
        }

        Container targetContainer = getActualContainer(level, depositTarget, chest);

        if (isFullFor(targetContainer, heldBefore)) {
            GolemConfig.debugLog("[SMART-GOLEM MAGIC-DEPOSIT-CANCEL] Matched chest full: " + depositTarget);

            markDestinationSelection(false, true);

            BlockPos alternateDestination = findDestinationChest(level, mob);

            if (alternateDestination != null
                    && !alternateDestination.equals(depositTarget)
                    && !alternateDestination.equals(lastAbandonedDestination)) {
                lastAbandonedDestination = depositTarget;
                switchState(mob, TaskState.WALK_TO_DESTINATION, alternateDestination, gameTime);
            } else {
                switchState(mob, TaskState.RETURN_TO_SOURCE, returnToSourceChest, gameTime);
            }

            return true;
        }

        if (!claimChest(mob, depositTarget, gameTime)) {
            GolemConfig.debugLog("[SMART-GOLEM MAGIC-DEPOSIT-WAIT] Chest locked by another golem: " + depositTarget);
            resetStuckTracking(mob, gameTime);
            return false;
        }

        mob.getNavigation().stop();

        ItemStack remaining;

        try {
            remaining = insertIntoChest(level, depositTarget, targetContainer, heldBefore);
        } finally {
            releaseChest(mob);
        }

        mob.setItemInHand(InteractionHand.MAIN_HAND, remaining);

        chest.setChanged();
        level.blockEntityChanged(depositTarget);

        boolean movedItem = remaining.getCount() < heldBefore.getCount();

        if (movedItem) {
            spawnMagicDepositParticles(level, mob, depositTarget);

            if (mob instanceof CopperGolem copperGolem) {
                copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_DROP);
                copperGolem.setState(CopperGolemState.DROPPING_ITEM);
            }

            GolemConfig.debugLog("[SMART-GOLEM MAGIC-DEPOSIT-SUCCESS] chest=" + depositTarget
                    + " heldBefore=" + heldBefore
                    + " remaining=" + remaining);
        } else {
            if (mob instanceof CopperGolem copperGolem) {
                copperGolem.playSound(SoundEvents.COPPER_GOLEM_ITEM_NO_DROP);
                copperGolem.setState(CopperGolemState.DROPPING_NO_ITEM);
            }

            GolemConfig.debugLog("[SMART-GOLEM MAGIC-DEPOSIT-FAILED] Nothing moved. chest=" + depositTarget);
        }

        markDestinationSelection(false, true);
        resetStuckTracking(mob, gameTime);


        if (!mob.getMainHandItem().isEmpty()) {

            BlockPos alternateDestination = findDestinationChest(level, mob);

            if (alternateDestination != null
                    && !alternateDestination.equals(depositTarget)
                    && !alternateDestination.equals(lastAbandonedDestination)) {
                lastAbandonedDestination = depositTarget;
                switchState(mob, TaskState.WALK_TO_DESTINATION, alternateDestination, gameTime);
                return true;
            }
        }

        BlockPos sourceToReturn = returnToSourceChest != null
                ? returnToSourceChest
                : lastPickupChest;

        if (sourceToReturn != null) {
            returnToSourceChest = sourceToReturn;
            switchState(mob, TaskState.RETURN_TO_SOURCE, sourceToReturn, gameTime);

            GolemConfig.debugLog("[SMART-GOLEM MAGIC-RETURN-TO-SOURCE] returning to=" + sourceToReturn);
        } else {
            taskState = TaskState.IDLE;
            currentTarget = null;
            currentWalkTarget = null;

            GolemConfig.debugLog("[SMART-GOLEM MAGIC-RETURN-FAILED] No saved source chest.");
        }

        return true;
    }

    private double distanceToCurrentWalkTargetSqr(PathfinderMob mob) {
        if (currentWalkTarget == null) {
            return Double.MAX_VALUE;
        }

        double dx = mob.getX() - (currentWalkTarget.getX() + 0.5D);
        double dy = mob.getY() - currentWalkTarget.getY();
        double dz = mob.getZ() - (currentWalkTarget.getZ() + 0.5D);

        return dx * dx + dy * dy + dz * dz;
    }

    private void spawnMagicDepositParticles(ServerLevel level, PathfinderMob mob, BlockPos chestPos) {

        ColorParticleOption magicParticle =
                ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xB85CFF);

        level.sendParticles(
                magicParticle,
                mob.getX(),
                mob.getY() + 1.0D,
                mob.getZ(),
                24,
                0.35D,
                0.45D,
                0.35D,
                0.05D
        );

        level.sendParticles(
                magicParticle,
                chestPos.getX() + 0.5D,
                chestPos.getY() + 0.8D,
                chestPos.getZ() + 0.5D,
                24,
                0.45D,
                0.35D,
                0.45D,
                0.05D
        );
    }
}
