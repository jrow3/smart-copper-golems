package com.anantaya.smartcgolem;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SmartGolem.MOD_ID)
public class SmartGolem {

    public static final String MOD_ID = "smartcgolem";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public SmartGolem(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Copper Golems are smart now!");
    }
}