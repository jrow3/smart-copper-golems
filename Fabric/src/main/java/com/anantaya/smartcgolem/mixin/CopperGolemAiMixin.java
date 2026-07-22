package com.anantaya.smartcgolem.mixin;

import com.anantaya.smartcgolem.ai.SmartCopperGolemAi;
import net.minecraft.world.entity.ai.ActivityData;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.List;

@Mixin(CopperGolemAi.class)
public class CopperGolemAiMixin {

    @Overwrite
    public static List<ActivityData<CopperGolem>> getActivities() {

        com.anantaya.smartcgolem.config.GolemConfig.debugLog("SMART GOLEM MIXIN ACTIVE");

        return SmartCopperGolemAi.getActivities();
    }

    @Overwrite
    public static void updateActivity(CopperGolem body) {


        SmartCopperGolemAi.updateActivity(body);
    }
}