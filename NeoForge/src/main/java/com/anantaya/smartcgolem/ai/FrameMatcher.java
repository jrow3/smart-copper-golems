package com.anantaya.smartcgolem.ai;

import com.anantaya.smartcgolem.config.GolemConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Item-frame matching for destination chests: decides whether a chest's frame accepts the held item,
 * and whether an unmatched chest is an eligible fallback. Stateless — every method takes its inputs as
 * parameters and reads only the {@link GolemConfig} singleton, so it holds no golem state.
 */
final class FrameMatcher {

    private FrameMatcher() {
    }

    record FrameFilterResult(
            boolean hasFrame,
            boolean matchesHeld,
            boolean hasBlankFrame,
            ItemStack matchedFrameItem
    ) {
    }

    /**
     * Item frames in the search volume, bucketed by the block position each is attached to. Built
     * once per destination scan so the per-candidate matcher is a map lookup instead of a fresh
     * getEntitiesOfClass query.
     */
    static Map<BlockPos, List<ItemFrame>> collectFramesByAttachedPos(ServerLevel level, PathfinderMob mob) {

        BlockPos mobPos = mob.blockPosition();
        int h = GolemConfig.get().horizontalSearchDistance + 1;
        int v = GolemConfig.get().verticalSearchDistance + 1;

        // +1 margin so a frame on the outer face of a boundary chest is still captured.
        AABB box = new AABB(
                mobPos.getX() - h, mobPos.getY() - v, mobPos.getZ() - h,
                mobPos.getX() + h + 1, mobPos.getY() + v + 1, mobPos.getZ() + h + 1);

        Map<BlockPos, List<ItemFrame>> map = new HashMap<>();

        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box)) {
            BlockPos attachedPos = frame.blockPosition().relative(frame.getDirection().getOpposite());
            map.computeIfAbsent(attachedPos, k -> new ArrayList<>()).add(frame);
        }

        return map;
    }

    /**
     * Single-chest convenience for the one caller outside the destination scan: builds a one-off
     * frame lookup with the original per-chest inflate(1.0) query, then delegates to the shared matcher.
     */
    static FrameFilterResult getFrameFilterResult(ServerLevel level, BlockPos chestPos, ItemStack held) {

        Map<BlockPos, List<ItemFrame>> map = new HashMap<>();

        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, new AABB(chestPos).inflate(1.0D))) {
            BlockPos attachedPos = frame.blockPosition().relative(frame.getDirection().getOpposite());
            map.computeIfAbsent(attachedPos, k -> new ArrayList<>()).add(frame);
        }

        return getFrameFilterResult(level, chestPos, held, map);
    }

    static FrameFilterResult getFrameFilterResult(
            ServerLevel level,
            BlockPos chestPos,
            ItemStack held,
            Map<BlockPos, List<ItemFrame>> framesByAttachedPos
    ) {
        boolean hasFrame = false;
        boolean hasBlankFrame = false;

        // A frame counts if it is attached to this chest itself, or to its genuine double-chest
        // partner. Keying the pre-built map by attached position replaces the old any-same-type-
        // neighbor check that let two side-by-side single chests cross-claim frames (the misfiling bug).
        List<ItemFrame> frames = new ArrayList<>();

        List<ItemFrame> direct = framesByAttachedPos.get(chestPos);
        if (direct != null) {
            frames.addAll(direct);
        }

        BlockPos partner = doubleChestPartner(level, chestPos);
        if (partner != null) {
            List<ItemFrame> partnerFrames = framesByAttachedPos.get(partner);
            if (partnerFrames != null) {
                frames.addAll(partnerFrames);
            }
        }

        for (ItemFrame frame : frames) {

            ItemStack frameItem = frame.getItem();

            if (frameItem.isEmpty()) {
                hasBlankFrame = true;
                continue;
            }

            hasFrame = true;

            if (!held.isEmpty() && itemsMatch(frameItem, held)) {
                return new FrameFilterResult(true, true, hasBlankFrame, frameItem.copy());
            }
        }

        return new FrameFilterResult(hasFrame, false, hasBlankFrame, ItemStack.EMPTY);
    }

    /**
     * The real other half of a double chest whose primary block is {@code chestPos}, or {@code null}
     * when {@code chestPos} is not a paired chest. Uses the vanilla chest pairing (type + connected
     * direction) rather than "any same-type neighbor", which is what fixes the misfiling bug.
     */
    static BlockPos doubleChestPartner(ServerLevel level, BlockPos chestPos) {
        BlockState state = level.getBlockState(chestPos);

        if (!(state.getBlock() instanceof ChestBlock)) {
            return null;
        }

        net.minecraft.world.level.block.state.properties.ChestType chestType =
                state.getValue(ChestBlock.TYPE);

        if (chestType == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            return null;
        }

        return chestPos.relative(ChestBlock.getConnectedDirection(state));
    }

    /** Item-frame match test, honoring the configured match strictness. */
    static boolean itemsMatch(ItemStack frameItem, ItemStack held) {
        return switch (GolemConfig.get().matchStrictness) {
            case ITEM_ONLY -> ItemStack.isSameItem(frameItem, held);
            case EXACT -> ItemStack.isSameItemSameComponents(frameItem, held);
        };
    }

    /** Whether a non-matching chest is an eligible fallback target under the configured mode. */
    static boolean isFallbackEligible(FrameFilterResult frame, GolemConfig.FallbackMode mode) {
        return switch (mode) {
            case NONE -> false;
            case BLANK_FRAME_ONLY -> frame.hasBlankFrame() && !frame.hasFrame();
            case UNFRAMED_OR_BLANK -> !frame.hasFrame();
        };
    }
}
