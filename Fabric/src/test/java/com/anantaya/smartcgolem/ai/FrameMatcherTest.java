package com.anantaya.smartcgolem.ai;

import com.anantaya.smartcgolem.config.GolemConfig;

import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truth table for {@link FrameMatcher#isFallbackEligible}, the rule that decides which non-matching
 * chests a golem may dump into when nothing carries a matching frame.
 *
 * <p>This is the sorting guarantee users actually notice: getting it wrong means a golem posting
 * items into a labelled chest that says something else. The function is a pure switch over three
 * booleans, so it is worth pinning exhaustively.
 *
 * <p>{@code matchedFrameItem} is null throughout -- the rule never reads it, and building a real
 * ItemStack would drag in the item registry for no benefit.
 */
class FrameMatcherTest {

    /** A chest with a frame showing something that is not what the golem is holding. */
    private static FrameMatcher.FrameFilterResult framedMismatch() {
        return new FrameMatcher.FrameFilterResult(true, false, false, (ItemStack) null);
    }

    /** A chest with a frame showing exactly what the golem is holding. */
    private static FrameMatcher.FrameFilterResult framedMatch() {
        return new FrameMatcher.FrameFilterResult(true, true, false, (ItemStack) null);
    }

    /** A chest carrying an empty item frame: opted in as a catch-all. */
    private static FrameMatcher.FrameFilterResult blankFrame() {
        return new FrameMatcher.FrameFilterResult(false, false, true, (ItemStack) null);
    }

    /** A chest with no frame at all. */
    private static FrameMatcher.FrameFilterResult unframed() {
        return new FrameMatcher.FrameFilterResult(false, false, false, (ItemStack) null);
    }

    @ParameterizedTest
    @EnumSource(GolemConfig.FallbackMode.class)
    @DisplayName("a chest labelled for something else is never a fallback, in any mode")
    void neverFallsBackOntoAMismatchedLabel(GolemConfig.FallbackMode mode) {
        // The whole point of the feature: a frame is a claim on the chest's contents, so a golem
        // must not dump an unrelated item into it just because it ran out of options.
        assertFalse(FrameMatcher.isFallbackEligible(framedMismatch(), mode),
                "mode " + mode + " allowed fallback onto a mismatched label");
    }

    @ParameterizedTest
    @EnumSource(GolemConfig.FallbackMode.class)
    @DisplayName("NONE refuses every fallback, and only NONE refuses a blank frame")
    void noneRefusesEverything(GolemConfig.FallbackMode mode) {
        boolean expected = mode != GolemConfig.FallbackMode.NONE;

        assertTrue(FrameMatcher.isFallbackEligible(blankFrame(), mode) == expected,
                "mode " + mode + " disagreed on a blank-framed chest");
    }

    @Test
    @DisplayName("BLANK_FRAME_ONLY takes blank frames but not bare chests")
    void blankFrameOnlyRequiresAnExplicitBlankFrame() {
        // The strict mode: an empty frame is a deliberate "anything goes here" marker, whereas an
        // unlabelled chest is just a chest the player never thought about.
        GolemConfig.FallbackMode mode = GolemConfig.FallbackMode.BLANK_FRAME_ONLY;

        assertTrue(FrameMatcher.isFallbackEligible(blankFrame(), mode));
        assertFalse(FrameMatcher.isFallbackEligible(unframed(), mode));
    }

    @Test
    @DisplayName("UNFRAMED_OR_BLANK takes both blank frames and bare chests")
    void unframedOrBlankAcceptsEither() {
        GolemConfig.FallbackMode mode = GolemConfig.FallbackMode.UNFRAMED_OR_BLANK;

        assertTrue(FrameMatcher.isFallbackEligible(blankFrame(), mode));
        assertTrue(FrameMatcher.isFallbackEligible(unframed(), mode));
    }

    @Test
    @DisplayName("NONE takes nothing at all")
    void noneAcceptsNothing() {
        GolemConfig.FallbackMode mode = GolemConfig.FallbackMode.NONE;

        assertFalse(FrameMatcher.isFallbackEligible(blankFrame(), mode));
        assertFalse(FrameMatcher.isFallbackEligible(unframed(), mode));
        assertFalse(FrameMatcher.isFallbackEligible(framedMatch(), mode));
    }

    @ParameterizedTest
    @EnumSource(GolemConfig.FallbackMode.class)
    @DisplayName("a matching chest is not routed as a fallback in any mode")
    void aMatchIsNotAFallback(GolemConfig.FallbackMode mode) {
        // Matching chests are claimed by the earlier matched-destination pass, so by the time the
        // fallback rule runs, hasFrame means "labelled, and not for this item".
        assertFalse(FrameMatcher.isFallbackEligible(framedMatch(), mode),
                "mode " + mode + " routed a matching chest through the fallback path");
    }
}
