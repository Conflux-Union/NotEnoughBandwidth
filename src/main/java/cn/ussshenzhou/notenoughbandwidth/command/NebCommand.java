package cn.ussshenzhou.notenoughbandwidth.command;

import cn.ussshenzhou.notenoughbandwidth.stat.ChunkPacketBreakdown;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketTypeStatManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

public final class NebCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Command");
    private static final Identifier LEVEL_CHUNK_WITH_LIGHT = Identifier.withDefaultNamespace("level_chunk_with_light");

    private NebCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("neb")
                        .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.literal("dumpstats")
                                .executes(ctx -> dumpStats(ctx.getSource(), "default"))
                                .then(Commands.argument("label", StringArgumentType.word())
                                        .executes(ctx -> dumpStats(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "label")))))
                        .then(Commands.literal("resetstats")
                                .executes(ctx -> {
                                    PacketTypeStatManager.reset();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("[NEB] per-packet counters reset."), true);
                                    return 1;
                                }))));
    }

    private static int dumpStats(CommandSourceStack source, String label) {
        try {
            Path file = PacketTypeStatManager.dumpCsv(label);
            long sec = PacketTypeStatManager.elapsedSeconds();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "[NEB] CSV written: %s (window: %d s)", file, sec)), true);

            long cd = ChunkPacketBreakdown.chunkDataBytes.get();
            if (cd > 0) {
                long chunkPktRaw = PacketTypeStatManager.getS2cRawBytes(LEVEL_CHUNK_WITH_LIGHT);
                long lightInsideChunkPkt = Math.max(0, chunkPktRaw - cd);
                if (chunkPktRaw > 0) {
                    final String breakdown = String.format(Locale.ROOT,
                            "[NEB] chunk packet split: terrain=%d B  light=%d B  light_share=%.2f%%",
                            cd, lightInsideChunkPkt, 100.0 * lightInsideChunkPkt / chunkPktRaw);
                    source.sendSuccess(() -> Component.literal(breakdown), false);
                }
            }

            var rows = PacketTypeStatManager.top(15);
            if (rows.isEmpty()) {
                source.sendSuccess(() -> Component.literal("[NEB] (no traffic recorded yet)"), false);
                return 1;
            }
            source.sendSuccess(() -> Component.literal("[NEB] top 15 by baked bytes:"), false);
            for (var r : rows) {
                final String line = String.format(Locale.ROOT,
                        "  %s %-50s baked=%d raw=%d count=%d",
                        r.direction() == PacketFlow.CLIENTBOUND ? "S2C" : "C2S",
                        r.type().toString(), r.bakedBytes(), r.rawBytes(), r.count());
                source.sendSuccess(() -> Component.literal(line), false);
            }
            return 1;
        } catch (Exception e) {
            LOGGER.error("dumpstats failed", e);
            source.sendFailure(Component.literal("[NEB] dumpstats failed: " + e.getMessage()));
            return 0;
        }
    }
}
