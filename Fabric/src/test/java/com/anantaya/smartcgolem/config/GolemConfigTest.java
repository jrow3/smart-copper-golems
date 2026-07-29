package com.anantaya.smartcgolem.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for config normalisation.
 *
 * <p>Everything here drives {@link GolemConfig#clamp()} on a plain instance. The singleton and the
 * on-disk file are deliberately never touched, so these tests neither read nor write
 * {@code coppergolem.json}.
 *
 * <p>The values under test all reach the game as raw numbers -- search radii become chunk loops,
 * haulSpeed becomes a pathfinding multiplier, the interaction ticks become countdown gates. A bad
 * one is not a wrong answer, it is a hang or a crash, which is why clamping is worth pinning down.
 */
class GolemConfigTest {

    @Nested
    @DisplayName("search distances")
    class SearchDistances {

        @Test
        @DisplayName("clamps to 1..64 from both directions")
        void clampsSearchDistances() {
            GolemConfig config = new GolemConfig();

            config.horizontalSearchDistance = 999;
            config.verticalSearchDistance = -5;
            config.clamp();

            assertEquals(64, config.horizontalSearchDistance);
            assertEquals(1, config.verticalSearchDistance);
        }

        @Test
        @DisplayName("leaves in-range values alone")
        void keepsValidSearchDistances() {
            GolemConfig config = new GolemConfig();

            config.horizontalSearchDistance = 32;
            config.verticalSearchDistance = 8;
            config.clamp();

            assertEquals(32, config.horizontalSearchDistance);
            assertEquals(8, config.verticalSearchDistance);
        }
    }

    @Nested
    @DisplayName("haul speed")
    class HaulSpeed {

        @Test
        @DisplayName("clamps to 0.1..5.0")
        void clampsHaulSpeed() {
            GolemConfig fast = new GolemConfig();
            fast.haulSpeed = 100.0F;
            fast.clamp();
            assertEquals(5.0F, fast.haulSpeed);

            GolemConfig slow = new GolemConfig();
            slow.haulSpeed = 0.0F;
            slow.clamp();
            assertEquals(0.1F, slow.haulSpeed);
        }

        @Test
        @DisplayName("replaces NaN with the default instead of clamping it")
        void repairsNaNHaulSpeed() {
            // The reason the NaN check exists: Math.min/max propagate NaN rather than rejecting it,
            // so clamping alone would let NaN through into pathfinding as a speed multiplier.
            GolemConfig config = new GolemConfig();

            config.haulSpeed = Float.NaN;
            config.clamp();

            assertFalse(Float.isNaN(config.haulSpeed), "NaN survived clamp()");
            assertEquals(1.0F, config.haulSpeed);
        }
    }

    @Nested
    @DisplayName("interaction ticks")
    class InteractionTicks {

        @Test
        @DisplayName("keeps close strictly after open")
        void enforcesCloseAfterOpen() {
            // The golem opens a chest at openTicks and leaves at closeTicks. If close <= open the
            // countdown gate can never be satisfied and the golem stands at the chest forever.
            GolemConfig config = new GolemConfig();

            config.interactionOpenTicks = 40;
            config.interactionCloseTicks = 10;
            config.clamp();

            assertTrue(config.interactionCloseTicks > config.interactionOpenTicks,
                    "close (" + config.interactionCloseTicks + ") must exceed open ("
                            + config.interactionOpenTicks + ")");
            assertEquals(41, config.interactionCloseTicks);
        }

        @Test
        @DisplayName("keeps the invariant at the ceiling, where open + 1 could overflow")
        void enforcesCloseAfterOpenAtCeiling() {
            // MAX_INTERACTION_TICKS exists precisely so the open + 1 floor below cannot overflow.
            GolemConfig config = new GolemConfig();

            config.interactionOpenTicks = Integer.MAX_VALUE;
            config.interactionCloseTicks = Integer.MAX_VALUE;
            config.clamp();

            assertEquals(12000, config.interactionOpenTicks);
            assertEquals(12001, config.interactionCloseTicks);
            assertTrue(config.interactionCloseTicks > config.interactionOpenTicks);
        }

        @Test
        @DisplayName("raises a zero or negative open gate to 1")
        void clampsOpenTicksFloor() {
            GolemConfig config = new GolemConfig();

            config.interactionOpenTicks = 0;
            config.interactionCloseTicks = 60;
            config.clamp();

            assertEquals(1, config.interactionOpenTicks);
            assertEquals(60, config.interactionCloseTicks);
        }

        @Test
        @DisplayName("leaves the vanilla defaults untouched")
        void keepsVanillaDefaults() {
            GolemConfig config = new GolemConfig();
            config.clamp();

            assertEquals(9, config.interactionOpenTicks);
            assertEquals(60, config.interactionCloseTicks);
        }
    }

    @Nested
    @DisplayName("unroutable item cooldown")
    class UnroutableCooldown {

        @Test
        @DisplayName("allows zero but rejects negatives")
        void clampsCooldown() {
            // Zero is meaningful -- it disables the cooldown -- so the floor is 0, not 1.
            GolemConfig disabled = new GolemConfig();
            disabled.unroutableItemCooldownTicks = -1;
            disabled.clamp();
            assertEquals(0, disabled.unroutableItemCooldownTicks);

            GolemConfig huge = new GolemConfig();
            huge.unroutableItemCooldownTicks = Integer.MAX_VALUE;
            huge.clamp();
            assertEquals(12000, huge.unroutableItemCooldownTicks);
        }
    }

    @Nested
    @DisplayName("golem stat overrides")
    class StatOverrides {

        @Test
        @DisplayName("repairs NaN to the sentinel that means leave vanilla alone")
        void repairsNaNStats() {
            GolemConfig config = new GolemConfig();

            config.baseHealth = Double.NaN;
            config.baseMoveSpeed = Double.NaN;
            config.clamp();

            assertEquals(-1.0D, config.baseHealth);
            assertEquals(-1.0D, config.baseMoveSpeed);
        }

        @Test
        @DisplayName("does not clamp real stat values, which are intentionally unbounded")
        void keepsExplicitStats() {
            GolemConfig config = new GolemConfig();

            config.baseHealth = 200.0D;
            config.baseMoveSpeed = 0.45D;
            config.clamp();

            assertEquals(200.0D, config.baseHealth);
            assertEquals(0.45D, config.baseMoveSpeed);
        }
    }

    @Nested
    @DisplayName("enum repair")
    class EnumRepair {

        @Test
        @DisplayName("replaces nulls left by malformed or partial JSON")
        void repairsNullEnums() {
            // Gson leaves a field null when the JSON omits it or names an enum constant that does
            // not exist, so both enums can arrive null however well-formed the file looks.
            GolemConfig config = new GolemConfig();

            config.matchStrictness = null;
            config.fallbackMode = null;
            config.clamp();

            assertEquals(GolemConfig.MatchStrictness.EXACT, config.matchStrictness);
            assertEquals(GolemConfig.FallbackMode.UNFRAMED_OR_BLANK, config.fallbackMode);
        }

        @Test
        @DisplayName("keeps a deliberate non-default choice")
        void keepsChosenEnums() {
            GolemConfig config = new GolemConfig();

            config.matchStrictness = GolemConfig.MatchStrictness.ITEM_ONLY;
            config.fallbackMode = GolemConfig.FallbackMode.NONE;
            config.clamp();

            assertEquals(GolemConfig.MatchStrictness.ITEM_ONLY, config.matchStrictness);
            assertEquals(GolemConfig.FallbackMode.NONE, config.fallbackMode);
        }
    }

    @Test
    @DisplayName("is idempotent -- clamping an already-clamped config changes nothing")
    void clampIsIdempotent() {
        // save() clamps on every /coppergolem set, so a config that drifts on each pass would drift
        // a little further every time the knob is touched.
        GolemConfig config = new GolemConfig();

        config.horizontalSearchDistance = 999;
        config.haulSpeed = Float.NaN;
        config.interactionOpenTicks = 40;
        config.interactionCloseTicks = 10;
        config.clamp();

        int horizontal = config.horizontalSearchDistance;
        float speed = config.haulSpeed;
        int open = config.interactionOpenTicks;
        int close = config.interactionCloseTicks;

        config.clamp();

        assertEquals(horizontal, config.horizontalSearchDistance);
        assertEquals(speed, config.haulSpeed);
        assertEquals(open, config.interactionOpenTicks);
        assertEquals(close, config.interactionCloseTicks);
    }
}
