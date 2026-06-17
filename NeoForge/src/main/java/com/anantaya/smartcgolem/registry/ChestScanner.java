package com.anantaya.smartcgolem.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public class ChestScanner {

    public static void scanNearbyChests(
            ServerLevel level,
            BlockPos center,
            int radius
    ) {

        BlockPos.betweenClosedStream(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius)
        ).forEach(pos -> {

            BlockEntity blockEntity =
                    level.getBlockEntity(pos);

            if (blockEntity instanceof ChestBlockEntity) {

                ChestRegistry.registerChest(
                        level,
                        pos.immutable()
                );
            }
        });
    }
}