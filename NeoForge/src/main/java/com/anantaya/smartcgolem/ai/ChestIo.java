package com.anantaya.smartcgolem.ai;

import com.anantaya.smartcgolem.config.GolemConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Reading from and writing to chest inventories: what fits, what is already there, and the actual
 * slot moves.
 *
 * <p>Stateless with respect to the golem's task state. The one piece of golem state any of this
 * needs — the unroutable-item cooldown — is passed in as an {@link UnroutableItemTracker} rather
 * than read off the behavior.
 *
 * <p>Note what is deliberately absent: nothing here decides <em>which</em> chest to use. Choosing a
 * target is policy and lives with the behavior; this class only carries out the move once a chest
 * has been picked.
 */
final class ChestIo {

    private ChestIo() {
    }

    /** Whether a player (or another golem) currently has this chest open. */
    static boolean isChestBusy(ChestBlockEntity chest) {
        return !chest.getEntitiesWithContainerOpen().isEmpty();
    }

    /**
     * The container to actually operate on, which for a double chest is the combined view over both
     * halves rather than the single block entity.
     */
    static Container getActualContainer(
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
    static boolean isFullFor(Container chest, ItemStack item) {

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
    static boolean hasAnyItem(ChestBlockEntity chest, long gameTime, UnroutableItemTracker unroutableItems) {

        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);

            if (!stack.isEmpty() && !unroutableItems.isOnCooldown(stack.getItem(), gameTime)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Takes the first stack the golem is willing to carry out of {@code chest} into its main hand.
     *
     * @return true if something was picked up, in which case the caller owns recording {@code
     *         chestPos} as the pickup/return-to source.
     */
    static boolean pickupFromChest(
            ServerLevel level,
            PathfinderMob mob,
            ChestBlockEntity chest,
            BlockPos chestPos,
            UnroutableItemTracker unroutableItems
    ) {

        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);

            if (!stack.isEmpty() && !unroutableItems.isOnCooldown(stack.getItem(), level.getGameTime())) {

                int takeAmount = Math.min(stack.getCount(), stack.getMaxStackSize());
                ItemStack taken = stack.copyWithCount(takeAmount);

                GolemConfig.debugLog("[SMART-GOLEM PICKUP BEFORE] tick=" + level.getGameTime()
                        + " chest=" + chestPos
                        + " slot=" + i
                        + " stack=" + stack
                        + " mobHand=" + mob.getMainHandItem());

                stack.shrink(takeAmount);

                chest.setChanged();
                level.blockEntityChanged(chestPos);

                mob.setItemInHand(InteractionHand.MAIN_HAND, taken);

                GolemConfig.debugLog("[SMART-GOLEM RETURN-SOURCE-SAVED] source=" + chestPos);

                GolemConfig.debugLog("[SMART-GOLEM PICKUP AFTER] tick=" + level.getGameTime()
                        + " chest=" + chestPos
                        + " slot=" + i
                        + " remainingStack=" + chest.getItem(i)
                        + " mobHand=" + mob.getMainHandItem());

                return true;
            }
        }

        return false;
    }

    /**
     * Deposits {@code held} into {@code chest}, topping up matching stacks before filling empty
     * slots, and returns whatever would not fit.
     */
    static ItemStack insertIntoChest(
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
}
