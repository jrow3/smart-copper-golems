package com.anantaya.smartcgolem.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Live-mutable, JSON-backed configuration for Smart Copper Golem.
 *
 * <p>Loaded once on mod init from {@code <configDir>/coppergolem.json} (created with defaults if
 * absent), held as a singleton, mutated live by the {@code /coppergolem} command, and persisted on
 * change. The sorting/search knobs take effect on the next golem tick; {@link #baseHealth} and
 * {@link #baseMoveSpeed} are applied when a golem loads (see the entity-load hook in the initializer).
 */
public final class GolemConfig {

    /** How strictly a held item must match an item-frame label to count as that chest's item. */
    public enum MatchStrictness {
        /** Same item and same components/NBT (vanilla-strict). */
        EXACT,
        /** Same item, ignoring components/NBT. */
        ITEM_ONLY
    }

    /** What non-matching chests a golem may fall back to when no framed match is found. */
    public enum FallbackMode {
        /** Only chests carrying a blank (empty) item frame. */
        BLANK_FRAME_ONLY,
        /** Chests with no non-empty frame: truly unframed OR blank-framed. */
        UNFRAMED_OR_BLANK,
        /** No fallback: only deposit into a chest whose frame matches the held item. */
        NONE
    }

    // ---- Sorting / search knobs (live) ----
    public int horizontalSearchDistance = 32;
    public int verticalSearchDistance = 8;
    public float haulSpeed = 1.0F;
    /** Ticks a golem waits at a chest before acting (vanilla: 9). */
    public int interactionOpenTicks = 9;
    /** Ticks a golem stays at a chest before closing/leaving (vanilla: 60). */
    public int interactionCloseTicks = 60;
    public MatchStrictness matchStrictness = MatchStrictness.EXACT;
    public FallbackMode fallbackMode = FallbackMode.UNFRAMED_OR_BLANK;
    public boolean debug = false;
    /**
     * Ticks a golem ignores an item it just failed to route, so an unroutable stack costs one round
     * trip back to its source chest rather than an endless pick-up/put-back shuttle.
     */
    public int unroutableItemCooldownTicks = 200;

    // ---- Golem stat knobs (applied on golem load). <= 0 means "leave the vanilla value". ----
    public double baseHealth = -1.0D;
    public double baseMoveSpeed = -1.0D;

    // ---- machinery ----
    private static final Logger LOGGER = LoggerFactory.getLogger("smart-copper-golem/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("coppergolem.json");
    private static final Path BACKUP_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("coppergolem.json.bak");

    /** Upper bound on the interaction tick gates, low enough that {@code open + 1} cannot overflow. */
    private static final int MAX_INTERACTION_TICKS = 12000;

    private static GolemConfig INSTANCE;

    /** Returns the singleton, loading (and creating) the file on first access. */
    public static synchronized GolemConfig get() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    /** (Re)reads the config file from disk into the singleton, then persists normalized values. */
    public static synchronized void load() {
        GolemConfig loaded = null;

        if (Files.exists(CONFIG_PATH)) {
            try {
                loaded = GSON.fromJson(Files.readString(CONFIG_PATH), GolemConfig.class);
            } catch (Exception e) {
                LOGGER.error("Could not read {}, falling back to defaults", CONFIG_PATH, e);
                backUpUnreadableConfig();
            }
        }

        if (loaded == null) {
            loaded = new GolemConfig();
        }

        loaded.clamp();
        INSTANCE = loaded;
        writeToDisk();
    }

    /**
     * Preserves a config we failed to parse before defaults overwrite it. A single stray comma should
     * not silently cost someone their hand-tuned settings.
     */
    private static void backUpUnreadableConfig() {
        try {
            Files.move(CONFIG_PATH, BACKUP_PATH, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.error("Saved the unreadable config to {}", BACKUP_PATH);
        } catch (Exception e) {
            LOGGER.error("Could not back up the unreadable config at {}", CONFIG_PATH, e);
        }
    }

    /** Re-reads from disk (used by {@code /coppergolem reload}). */
    public static synchronized void reload() {
        load();
    }

    /** Normalizes and persists the current in-memory config (used after a {@code set}/{@code toggle}). */
    public static synchronized void save() {
        if (INSTANCE == null) {
            load();
            return;
        }
        INSTANCE.clamp();
        writeToDisk();
    }

    private static void writeToDisk() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (Exception e) {
            LOGGER.error("Could not save {}", CONFIG_PATH, e);
        }
    }

    /**
     * Clamps values into safe ranges and repairs nulls left by malformed JSON.
     *
     * <p>Package-private rather than private so the tests can drive it on a plain instance. Going
     * through {@link #load()} instead would mean touching the real config file.
     */
    void clamp() {
        horizontalSearchDistance = Math.max(1, Math.min(64, horizontalSearchDistance));
        verticalSearchDistance = Math.max(1, Math.min(64, verticalSearchDistance));

        // Math.min/max propagate NaN, so NaN has to be filtered before clamping or it survives
        // into pathfinding as a speed modifier.
        if (Float.isNaN(haulSpeed)) {
            haulSpeed = 1.0F;
        }
        haulSpeed = Math.max(0.1F, Math.min(5.0F, haulSpeed));

        interactionOpenTicks = Math.max(1, Math.min(MAX_INTERACTION_TICKS, interactionOpenTicks));
        interactionCloseTicks = Math.max(interactionOpenTicks + 1,
                Math.min(MAX_INTERACTION_TICKS + 1, interactionCloseTicks));

        unroutableItemCooldownTicks = Math.max(0, Math.min(MAX_INTERACTION_TICKS, unroutableItemCooldownTicks));

        if (Double.isNaN(baseHealth)) {
            baseHealth = -1.0D;
        }
        if (Double.isNaN(baseMoveSpeed)) {
            baseMoveSpeed = -1.0D;
        }

        if (matchStrictness == null) {
            matchStrictness = MatchStrictness.EXACT;
        }
        if (fallbackMode == null) {
            fallbackMode = FallbackMode.UNFRAMED_OR_BLANK;
        }
    }

    /** Convenience: gate debug logging behind {@code config.debug}. */
    public static void debugLog(String message) {
        if (get().debug) {
            LOGGER.info(message);
        }
    }
}
