package com.anantaya.smartcgolem;

import com.anantaya.smartcgolem.command.CopperGolemCommand;
import com.anantaya.smartcgolem.config.GolemConfig;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.CopperGolem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartCopperGolem implements ModInitializer {
	public static final String MOD_ID = "smart-copper-golem";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final ResourceLocation HEALTH_OVERRIDE_ID =
			ResourceLocation.fromNamespaceAndPath(MOD_ID, "config_base_health");
	private static final ResourceLocation SPEED_OVERRIDE_ID =
			ResourceLocation.fromNamespaceAndPath(MOD_ID, "config_base_move_speed");

	@Override
	public void onInitialize() {

		// Load (and create with defaults) the config file up front.
		GolemConfig.get();

		// /coppergolem op command tree.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				CopperGolemCommand.register(dispatcher));

		// Apply configurable base health / move speed when a copper golem loads.
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof CopperGolem golem) {
				applyGolemStats(golem);
			}
		});

		LOGGER.info("Copper Golems are smart now!");
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
	private static void applyOverride(AttributeInstance attribute, ResourceLocation id, double target) {
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
