package com.anantaya.smartcgolem.ai;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Pair;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.ActivityData;
import net.minecraft.world.entity.ai.behavior.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class SmartCopperGolemAi {

    private static final Predicate<BlockState> TRANSPORT_ITEM_SOURCE_BLOCK =
            block -> block.is(BlockTags.COPPER_CHESTS);

    private static final Predicate<BlockState> TRANSPORT_ITEM_DESTINATION_BLOCK =
            block -> block.is(Blocks.CHEST)
                    || block.is(Blocks.TRAPPED_CHEST);

    public static List<ActivityData<CopperGolem>> getActivities() {

        return List.of(
                initCoreActivity(),
                initIdleActivity()
        );
    }

    public static void updateActivity(final CopperGolem body) {


    body.getBrain().setActiveActivityToFirstValid(
            ImmutableList.of(
                    Activity.CORE,
                    Activity.IDLE
            )
    );
}

    private static ActivityData<CopperGolem> initCoreActivity() {

        return ActivityData.create(
                Activity.CORE,
                0,
                ImmutableList.of(
                        new AnimalPanic<>(1.5F),
                        new LookAtTargetSink(45, 90),
                        new MoveToTargetSink(),
                        InteractWithDoor.create(),
                        new CountDownCooldownTicks(
                                MemoryModuleType.GAZE_COOLDOWN_TICKS
                        ),
                        new CountDownCooldownTicks(
                                MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS
                        )
                )
        );
    }

    private static ActivityData<CopperGolem> initIdleActivity() {

        return ActivityData.create(
                Activity.IDLE,
                ImmutableList.of(
                        Pair.of(
                                0,
                                new SmartTransportItemsBehavior(
                                        TRANSPORT_ITEM_SOURCE_BLOCK,
                                        TRANSPORT_ITEM_DESTINATION_BLOCK,
                                        onTravelling()
                                )
                        ),

                        Pair.of(
                                1,
                                SetEntityLookTarget.create(
                                        EntityTypes.PLAYER,
                                        6.0F
                                )
                        ),

                        Pair.of(
                                2,
                                new RunOne<>(
                                        ImmutableMap.of(
                                                MemoryModuleType.WALK_TARGET,
                                                MemoryStatus.VALUE_ABSENT,

                                                MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS,
                                                MemoryStatus.VALUE_PRESENT
                                        ),

                                        ImmutableList.of(
                                                Pair.of(
                                                        RandomStroll.stroll(
                                                                1.0F,
                                                                2,
                                                                2
                                                        ),
                                                        1
                                                ),

                                                Pair.of(
                                                        new DoNothing(30, 60),
                                                        1
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private static Consumer<PathfinderMob> onTravelling() {

        return body -> {

            if (body instanceof CopperGolem copperGolem) {

                copperGolem.clearOpenedChestPos();

                copperGolem.setState(
                        CopperGolemState.IDLE
                );
            }
        };
    }

}