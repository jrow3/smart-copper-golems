package com.anantaya.smartcgolem;

import com.anantaya.smartcgolem.chest.ChestLockRegistry;
import com.anantaya.smartcgolem.command.CopperGolemCommand;
import com.anantaya.smartcgolem.config.GolemConfig;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.CopperGolem;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SmartGolem.MOD_ID)
public class SmartGolem {

    public static final String MOD_ID = "smartcgolem";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Namespaced with the NeoForge mod id, matching this build's identity. The Fabric build uses its
    // own id for the same modifiers; a world is only ever loaded by one of the two.
    private static final Identifier HEALTH_OVERRIDE_ID =
            Identifier.fromNamespaceAndPath(MOD_ID, "config_base_health");
    private static final Identifier SPEED_OVERRIDE_ID =
            Identifier.fromNamespaceAndPath(MOD_ID, "config_base_move_speed");

    public SmartGolem(IEventBus modEventBus, ModContainer modContainer) {

        // Load (and create with defaults) the config file up front.
        GolemConfig.get();

        // These are game-bus events, not mod-bus ones, so they register on NeoForge.EVENT_BUS rather
        // than the modEventBus handed to this constructor.
        NeoForge.EVENT_BUS.addListener(SmartGolem::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(SmartGolem::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(SmartGolem::onServerStopped);

        LOGGER.info("Copper Golems are smart now!");
    }

    /** /coppergolem op command tree. */
    private static void onRegisterCommands(RegisterCommandsEvent event) {
        CopperGolemCommand.register(event.getDispatcher());
    }

    /**
     * Applies configurable base health / move speed when a copper golem loads.
     *
     * <p>Unlike Fabric's ServerEntityEvents.ENTITY_LOAD, EntityJoinLevelEvent also fires on the
     * client, so the client-side copy has to be filtered out — otherwise single-player applies the
     * modifier twice, once per logical side.
     */
    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {

        if (event.getLevel().isClientSide()) {
            return;
        }

        if (event.getEntity() instanceof CopperGolem golem) {
            applyGolemStats(golem);
        }
    }

    /**
     * Chest locks are static, so on an integrated server they would otherwise leak into the next
     * world loaded in the same JVM.
     */
    private static void onServerStopped(ServerStoppedEvent event) {
        ChestLockRegistry.clear();
    }

    /** Applies configured base health / move speed to a golem. Values &lt;= 0 leave the vanilla value. */
    private static void applyGolemStats(CopperGolem golem) {
        GolemConfig config = GolemConfig.get();

        applyOverride(golem.getAttribute(Attributes.MAX_HEALTH), HEALTH_OVERRIDE_ID, config.baseHealth);
        applyOverride(golem.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_OVERRIDE_ID, config.baseMoveSpeed);

        // Only ever clamps downward, so lowering max health does not leave a golem above its cap and
        // raising it does not heal one. The previous unconditional setHealth healed every damaged
        // golem on every chunk reload.
        golem.setHealth(Math.min(golem.getHealth(), golem.getMaxHealth()));
    }

    /**
     * Expresses the override as a named modifier rather than a new base value. setBaseValue is
     * persisted into the entity's NBT, so it could never be undone: setting the knob back to -1 left
     * every golem already touched stuck at the old value.
     */
    private static void applyOverride(AttributeInstance attribute, Identifier id, double target) {
        if (attribute == null) {
            return;
        }

        attribute.removeModifier(id);

        if (target > 0) {
            attribute.addOrReplacePermanentModifier(new AttributeModifier(
                    id, target - attribute.getBaseValue(), AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
