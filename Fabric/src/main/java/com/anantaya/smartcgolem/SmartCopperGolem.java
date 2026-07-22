package com.anantaya.smartcgolem;

import com.anantaya.smartcgolem.command.CopperGolemCommand;
import com.anantaya.smartcgolem.config.GolemConfig;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.CopperGolem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartCopperGolem implements ModInitializer {
	public static final String MOD_ID = "smart-copper-golem";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

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

		if (config.baseHealth > 0) {
			AttributeInstance maxHealth = golem.getAttribute(Attributes.MAX_HEALTH);
			if (maxHealth != null) {
				maxHealth.setBaseValue(config.baseHealth);
				golem.setHealth((float) config.baseHealth);
			}
		}

		if (config.baseMoveSpeed > 0) {
			AttributeInstance moveSpeed = golem.getAttribute(Attributes.MOVEMENT_SPEED);
			if (moveSpeed != null) {
				moveSpeed.setBaseValue(config.baseMoveSpeed);
			}
		}
	}
}
