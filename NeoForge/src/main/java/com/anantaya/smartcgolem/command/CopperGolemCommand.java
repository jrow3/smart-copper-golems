package com.anantaya.smartcgolem.command;

import com.anantaya.smartcgolem.config.GolemConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /coppergolem} op command tree (permission level 2): view and live-edit {@link GolemConfig}.
 *
 * <pre>
 *   /coppergolem list
 *   /coppergolem get &lt;key&gt;
 *   /coppergolem set &lt;key&gt; &lt;value&gt;
 *   /coppergolem toggle &lt;key&gt;        (boolean keys only, i.e. debug)
 *   /coppergolem reload               (re-read the file from disk)
 * </pre>
 */
public final class CopperGolemCommand {

    private static final String[] KEYS = {
            "horizontalSearchDistance",
            "verticalSearchDistance",
            "haulSpeed",
            "interactionOpenTicks",
            "interactionCloseTicks",
            "unroutableItemCooldownTicks",
            "matchStrictness",
            "fallbackMode",
            "debug",
            "baseHealth",
            "baseMoveSpeed"
    };

    private static final String[] BOOLEAN_KEYS = {"debug"};

    private CopperGolemCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("coppergolem")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("list")
                                .executes(CopperGolemCommand::list))
                        .then(Commands.literal("reload")
                                .executes(CopperGolemCommand::reload))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(CopperGolemCommand::suggestKeys)
                                        .executes(CopperGolemCommand::get)))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(CopperGolemCommand::suggestKeys)
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(CopperGolemCommand::set))))
                        .then(Commands.literal("toggle")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(CopperGolemCommand::suggestBooleanKeys)
                                        .executes(CopperGolemCommand::toggle)))
        );
    }

    private static CompletableFuture<Suggestions> suggestKeys(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(KEYS, builder);
    }

    /** toggle only accepts boolean keys, so suggesting the other nine would be nine guaranteed errors. */
    private static CompletableFuture<Suggestions> suggestBooleanKeys(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(BOOLEAN_KEYS, builder);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        GolemConfig c = GolemConfig.get();
        StringBuilder sb = new StringBuilder("Smart Copper Golem config:");
        for (String key : KEYS) {
            sb.append("\n  ").append(key).append(" = ").append(valueOf(c, key));
        }
        final String out = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    private static int get(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String value = valueOf(GolemConfig.get(), key);
        if (value == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown key: " + key));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(key + " = " + value), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String raw = StringArgumentType.getString(ctx, "value").trim();
        GolemConfig c = GolemConfig.get();

        try {
            switch (key) {
                case "horizontalSearchDistance" -> c.horizontalSearchDistance = Integer.parseInt(raw);
                case "verticalSearchDistance" -> c.verticalSearchDistance = Integer.parseInt(raw);
                case "haulSpeed" -> c.haulSpeed = Float.parseFloat(raw);
                case "interactionOpenTicks" -> c.interactionOpenTicks = Integer.parseInt(raw);
                case "interactionCloseTicks" -> c.interactionCloseTicks = Integer.parseInt(raw);
                case "unroutableItemCooldownTicks" -> c.unroutableItemCooldownTicks = Integer.parseInt(raw);
                case "matchStrictness" -> c.matchStrictness =
                        GolemConfig.MatchStrictness.valueOf(raw.toUpperCase(Locale.ROOT));
                case "fallbackMode" -> c.fallbackMode =
                        GolemConfig.FallbackMode.valueOf(raw.toUpperCase(Locale.ROOT));
                case "debug" -> c.debug = parseBoolean(raw);
                case "baseHealth" -> c.baseHealth = Double.parseDouble(raw);
                case "baseMoveSpeed" -> c.baseMoveSpeed = Double.parseDouble(raw);
                default -> {
                    ctx.getSource().sendFailure(Component.literal("Unknown key: " + key));
                    return 0;
                }
            }
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Invalid value '" + raw + "' for " + key + " (" + e.getMessage() + ")"));
            return 0;
        }

        String requested = valueOf(c, key);
        GolemConfig.save();
        String stored = valueOf(c, key);

        String message = requested.equals(stored)
                ? "Set " + key + " = " + stored
                : "Set " + key + " = " + stored + " (clamped from " + requested + ")";
        ctx.getSource().sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    /**
     * {@link Boolean#parseBoolean} never throws, so it would silently turn {@code set debug yes} into
     * {@code false} and report it as a success.
     */
    private static boolean parseBoolean(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalArgumentException("expected true or false");
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        GolemConfig c = GolemConfig.get();

        if (!"debug".equals(key)) {
            ctx.getSource().sendFailure(Component.literal(
                    "toggle only supports boolean keys (debug); use 'set' for " + key));
            return 0;
        }

        c.debug = !c.debug;
        GolemConfig.save();
        final boolean newValue = c.debug;
        ctx.getSource().sendSuccess(() -> Component.literal("Toggled debug -> " + newValue), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        GolemConfig.reload();
        ctx.getSource().sendSuccess(
                () -> Component.literal("Reloaded coppergolem.json from disk."), true);
        return 1;
    }

    private static String valueOf(GolemConfig c, String key) {
        return switch (key) {
            case "horizontalSearchDistance" -> Integer.toString(c.horizontalSearchDistance);
            case "verticalSearchDistance" -> Integer.toString(c.verticalSearchDistance);
            case "haulSpeed" -> Float.toString(c.haulSpeed);
            case "interactionOpenTicks" -> Integer.toString(c.interactionOpenTicks);
            case "interactionCloseTicks" -> Integer.toString(c.interactionCloseTicks);
            case "unroutableItemCooldownTicks" -> Integer.toString(c.unroutableItemCooldownTicks);
            case "matchStrictness" -> c.matchStrictness.name();
            case "fallbackMode" -> c.fallbackMode.name();
            case "debug" -> Boolean.toString(c.debug);
            case "baseHealth" -> Double.toString(c.baseHealth);
            case "baseMoveSpeed" -> Double.toString(c.baseMoveSpeed);
            default -> null;
        };
    }
}
