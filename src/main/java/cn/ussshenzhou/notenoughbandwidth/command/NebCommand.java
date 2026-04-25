package cn.ussshenzhou.notenoughbandwidth.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import cn.ussshenzhou.notenoughbandwidth.stat.ChunkPacketBreakdown;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketTypeStatManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

public final class NebCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Command");

    private NebCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("neb")
                        .requires(src -> src.getPermissions().hasPermission(DefaultPermissions.GAMEMASTERS))
                        .then(CommandManager.literal("dumpstats")
                                .executes(ctx -> dumpStats(ctx.getSource(), "default"))
                                .then(CommandManager.argument("label", StringArgumentType.word())
                                        .executes(ctx -> dumpStats(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "label")))))
                        .then(CommandManager.literal("resetstats")
                                .executes(ctx -> {
                                    PacketTypeStatManager.reset();
                                    ctx.getSource().sendFeedback(
                                            () -> Text.literal("[NEB] per-PacketType counters reset."), true);
                                    return 1;
                                }))));
    }

    private static int dumpStats(ServerCommandSource source, String label) {
        try {
            Path file = PacketTypeStatManager.dumpCsv(label);
            long sec = PacketTypeStatManager.elapsedSeconds();
            source.sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
                    "[NEB] CSV written: %s (window: %d s)", file, sec)), true);

            long cd = ChunkPacketBreakdown.chunkDataBytes.get();
            if (cd > 0) {
                long chunkPktRaw = PacketTypeStatManager.getS2cRawBytes(
                        net.minecraft.util.Identifier.of("minecraft", "level_chunk_with_light"));
                long lightInsideChunkPkt = Math.max(0, chunkPktRaw - cd);
                if (chunkPktRaw > 0) {
                    final String breakdown = String.format(Locale.ROOT,
                            "[NEB] chunk packet split: terrain=%d B  light=%d B  light_share=%.2f%%",
                            cd, lightInsideChunkPkt, 100.0 * lightInsideChunkPkt / chunkPktRaw);
                    source.sendFeedback(() -> Text.literal(breakdown), false);
                }
            }

            var rows = PacketTypeStatManager.top(15);
            if (rows.isEmpty()) {
                source.sendFeedback(() -> Text.literal("[NEB] (no traffic recorded yet)"), false);
                return 1;
            }
            source.sendFeedback(() -> Text.literal("[NEB] top 15 by baked bytes:"), false);
            for (var r : rows) {
                final String line = String.format(Locale.ROOT,
                        "  %s %-50s baked=%d raw=%d count=%d",
                        r.direction() == NetworkSide.CLIENTBOUND ? "S2C" : "C2S",
                        r.type().toString(), r.bakedBytes(), r.rawBytes(), r.count());
                source.sendFeedback(() -> Text.literal(line), false);
            }
            return 1;
        } catch (Exception e) {
            LOGGER.error("dumpstats failed", e);
            source.sendError(Text.literal("[NEB] dumpstats failed: " + e.getMessage()));
            return 0;
        }
    }
}
