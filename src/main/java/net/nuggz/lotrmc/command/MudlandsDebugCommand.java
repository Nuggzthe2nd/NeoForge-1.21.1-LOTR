package net.nuggz.lotrmc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.nuggz.lotrmc.world.MudlandsTerrainReplacer;
import net.nuggz.lotrmc.worlddata.MudlandsChunkData;
import net.nuggz.lotrmc.worlddata.MudlandsManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Debug command tree for testing mudlands systems without the ritual.
 *
 * All subcommands require op level 2 (same as /gamemode).
 *
 * Usage:
 *   /mudlands init                 — designate yourself as Sauron at your position
 *   /mudlands expand               — expand radius by 1 ring
 *   /mudlands collapse start       — begin collapse sequence
 *   /mudlands collapse stop        — abort collapse
 *   /mudlands collapse tick <n>    — force n collapse ticks instantly
 *   /mudlands pit                  — carve a mudpit at your feet
 *   /mudlands status               — print all current SavedData to chat
 *   /mudlands reset                — wipe all mudlands data (nuclear option)
 *   /mudlands convert              — convert just the chunk you're standing in
 */
public class MudlandsDebugCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("mudlands")
                        .requires(src -> src.hasPermission(2))

                        // /mudlands init
                        .then(Commands.literal("init")
                                .executes(ctx -> cmdInit(ctx.getSource())))

                        // /mudlands expand
                        .then(Commands.literal("expand")
                                .executes(ctx -> cmdExpand(ctx.getSource())))

                        // /mudlands collapse start|stop|tick <n>
                        .then(Commands.literal("collapse")
                                .then(Commands.literal("start")
                                        .executes(ctx -> cmdCollapseStart(ctx.getSource())))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> cmdCollapseStop(ctx.getSource())))
                                .then(Commands.literal("tick")
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                                .executes(ctx -> cmdCollapseTick(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))

                        // /mudlands pit
                        .then(Commands.literal("pit")
                                .executes(ctx -> cmdPit(ctx.getSource())))

                        // /mudlands status
                        .then(Commands.literal("status")
                                .executes(ctx -> cmdStatus(ctx.getSource())))

                        // /mudlands reset
                        .then(Commands.literal("reset")
                                .executes(ctx -> cmdReset(ctx.getSource())))

                        // /mudlands convert
                        .then(Commands.literal("convert")
                                .executes(ctx -> cmdConvert(ctx.getSource())))
        );
    }

    // -------------------------------------------------------------------------

    private static int cmdInit(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        ServerLevel level = src.getLevel();
        MudlandsChunkData data = MudlandsChunkData.get(level);

        if (data.hasSauron()) {
            src.sendFailure(Component.literal(
                    "A Sauron already exists: " + data.getSauronUUID()
                            + ". Run /mudlands reset first."));
            return 0;
        }

        BlockPos pos = player.blockPosition();
        MudlandsManager.initiateSauron(level, player.getUUID(), pos);

        src.sendSuccess(() -> Component.literal(
                "[Mudlands] Sauron set to " + player.getName().getString()
                        + " at " + pos + " with radius " + data.getSauronRadius()), true);
        return 1;
    }

    // -------------------------------------------------------------------------

    private static int cmdExpand(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        MudlandsChunkData data = MudlandsChunkData.get(level);

        if (!data.hasSauron()) {
            src.sendFailure(Component.literal("No active Sauron. Run /mudlands init first."));
            return 0;
        }

        MudlandsManager.expandSauron(level);
        src.sendSuccess(() -> Component.literal(
                "[Mudlands] Expanded to radius " + data.getSauronRadius()
                        + ". Total converted chunks: " + countOwnedChunks(data, data.getSauronUUID())), true);
        return 1;
    }

    // -------------------------------------------------------------------------

    private static int cmdCollapseStart(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        MudlandsChunkData data = MudlandsChunkData.get(level);

        if (!data.hasSauron()) {
            src.sendFailure(Component.literal("No active Sauron."));
            return 0;
        }
        if (data.isCollapseActive()) {
            src.sendFailure(Component.literal("Collapse already active."));
            return 0;
        }

        MudlandsManager.beginCollapse(level);
        src.sendSuccess(() -> Component.literal(
                "[Mudlands] Collapse started. Current radius: " + data.getSauronRadius()
                        + ". Tick interval: " + MudlandsManager.COLLAPSE_TICK_INTERVAL + " ticks."), true);
        return 1;
    }

    private static int cmdCollapseStop(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        MudlandsChunkData data = MudlandsChunkData.get(level);

        if (!data.isCollapseActive()) {
            src.sendFailure(Component.literal("No collapse in progress."));
            return 0;
        }

        MudlandsManager.abortCollapse(level);
        src.sendSuccess(() -> Component.literal(
                "[Mudlands] Collapse aborted. Radius held at " + data.getSauronRadius()), true);
        return 1;
    }

    /**
     * Force n collapse ticks instantly — useful for watching the ring-by-ring
     * revert without waiting 10 seconds per ring.
     */
    private static int cmdCollapseTick(CommandSourceStack src, int count) {
        ServerLevel level = src.getLevel();
        MudlandsChunkData data = MudlandsChunkData.get(level);

        if (!data.hasSauron()) {
            src.sendFailure(Component.literal("No active Sauron."));
            return 0;
        }

        // Temporarily override the game time check by calling tickCollapse
        // after faking the interval. We do this by manually calling the
        // shrink + revert path the same number of times as requested.
        int startRadius = data.getSauronRadius();
        for (int i = 0; i < count; i++) {
            if (data.getSauronRadius() <= 0) break;
            MudlandsManager.tickCollapse(level);

            // tickCollapse only fires every COLLAPSE_TICK_INTERVAL ticks,
            // so we nudge the stored start time backward to trick it each loop.
            // This is debug-only — do not do this in production code.
            if (data.isCollapseActive()) {
                data.startCollapse(level.getGameTime() - MudlandsManager.COLLAPSE_TICK_INTERVAL);
            }
        }

        int endRadius = data.getSauronRadius();
        src.sendSuccess(() -> Component.literal(
                "[Mudlands] Forced " + count + " collapse tick(s). Radius: "
                        + startRadius + " → " + endRadius
                        + (endRadius <= 0 ? " — COLLAPSE COMPLETE, crown recraftable." : "")), true);
        return 1;
    }

    // -------------------------------------------------------------------------

    private static int cmdPit(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        BlockPos feet = player.blockPosition();
        MudlandsTerrainReplacer.carveMudpit(src.getLevel(), feet);

        src.sendSuccess(() -> Component.literal(
                "[Mudlands] Mudpit carved at " + feet), true);
        return 1;
    }

    // -------------------------------------------------------------------------

    private static int cmdStatus(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        MudlandsChunkData data = MudlandsChunkData.get(level);

        if (!data.hasSauron()) {
            src.sendSuccess(() -> Component.literal("[Mudlands] No active Sauron."), false);
            return 1;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Mudlands Status ===\n");
        sb.append("Sauron: ").append(data.getSauronUUID()).append("\n");
        sb.append("Origin: ").append(data.getSauronOrigin()).append("\n");
        sb.append("Radius: ").append(data.getSauronRadius()).append("\n");
        sb.append("Collapse active: ").append(data.isCollapseActive()).append("\n");
        sb.append("Total converted chunks: ")
                .append(countOwnedChunks(data, data.getSauronUUID())).append("\n");
        sb.append("Lieutenants: ").append(data.getLieutenants().size()).append("\n");
        for (var lt : data.getLieutenants()) {
            ChunkPos ltOrigin = data.getLieutenantOrigin(lt);
            sb.append("  - ").append(lt)
                    .append(" origin=").append(ltOrigin)
                    .append(" radius=").append(data.getLieutenantRadius(lt))
                    .append(" (max=").append(data.getMaxLieutenantRadius()).append(")\n");
        }

        String output = sb.toString();
        src.sendSuccess(() -> Component.literal(output), false);
        return 1;
    }

    // -------------------------------------------------------------------------

    private static int cmdReset(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        MudlandsChunkData data = MudlandsChunkData.get(level);

        // Remove all lieutenant data — copy first to avoid ConcurrentModificationException
        for (var lt : new java.util.ArrayList<>(data.getLieutenants())) {
            data.removeLieutenant(lt);
        }
        data.stopCollapse();
        data.clearSauron();

        src.sendSuccess(() -> Component.literal(
                "[Mudlands] All mudlands data wiped. Note: converted blocks in the world "
                        + "are NOT reverted by reset — use /mudlands collapse tick <n> first if needed."), true);
        return 1;
    }

    // -------------------------------------------------------------------------

    private static int cmdConvert(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        ServerLevel level = src.getLevel();
        ChunkPos chunkPos = new ChunkPos(player.blockPosition());
        MudlandsTerrainReplacer.convertChunk(level, chunkPos);

        src.sendSuccess(() -> Component.literal(
                "[Mudlands] Converted chunk " + chunkPos
                        + " (blocks replaced, no SavedData entry written — use /mudlands init for full init)"), true);
        return 1;
    }

    // -------------------------------------------------------------------------

    private static int countOwnedChunks(MudlandsChunkData data, java.util.UUID owner) {
        // Count by iterating — fine for debug, not for hot paths
        int count = 0;
        for (int dx = -data.getSauronRadius(); dx <= data.getSauronRadius(); dx++) {
            for (int dz = -data.getSauronRadius(); dz <= data.getSauronRadius(); dz++) {
                ChunkPos pos = new ChunkPos(
                        new ChunkPos(data.getSauronOrigin()).x + dx,
                        new ChunkPos(data.getSauronOrigin()).z + dz);
                if (owner.equals(data.getChunkOwner(pos))) count++;
            }
        }
        return count;
    }
}