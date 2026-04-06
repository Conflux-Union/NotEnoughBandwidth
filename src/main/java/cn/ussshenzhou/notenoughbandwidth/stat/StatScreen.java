package cn.ussshenzhou.notenoughbandwidth.stat;

import cn.ussshenzhou.notenoughbandwidth.network.StatQueryPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import static cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager.*;

public class StatScreen extends Screen {
    private final String client = "Client";
    private final String actual = "Actual Transmission";
    private String actualC = "";
    private String raw = "Raw Payload";
    private String rawC = "";
    private String ratioC = "";

    private final String server = "Server";
    private String actualS = "-";
    private String rawS = "-";
    private String ratioS = "-";

    private String dictStatus = "";
    private String chunkCacheStatus = "";

    private int tick = 0;

    public StatScreen() {
        super(Text.empty());
    }

    @Override
    public void tick() {
        super.tick();
        if (tick % 10 == 0) {
            try {
                var buf = PacketByteBufs.create();
                new StatQueryPayload().write(buf);
                ClientPlayNetworking.send(StatQueryPayload.CHANNEL, buf);
            } catch (Exception ignored) {
            }
            actualC = "\u2193 Inbound  "
                    + getReadableSpeed((int) LOCAL.inboundSpeedBaked().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.inboundBytesBaked().get())
                    + "    \u2191 Outbound  "
                    + getReadableSpeed((int) LOCAL.outboundSpeedBaked().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.outboundBytesBaked().get());
            rawC = "\u2193 Inbound  "
                    + getReadableSpeed((int) LOCAL.inboundSpeedRaw().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.inboundBytesRaw().get())
                    + "    \u2191 Outbound  "
                    + getReadableSpeed((int) LOCAL.outboundSpeedRaw().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.outboundBytesRaw().get());

            long inBaked = LOCAL.inboundBytesBaked().get();
            long inRaw = LOCAL.inboundBytesRaw().get();
            long outBaked = LOCAL.outboundBytesBaked().get();
            long outRaw = LOCAL.outboundBytesRaw().get();
            ratioC = "Ratio                            "
                    + (inRaw > 0 ? String.format("%.2f", 100d * inBaked / inRaw) : "N/A")
                    + "%                                        "
                    + (outRaw > 0 ? String.format("%.2f", 100d * outBaked / outRaw) : "N/A")
                    + "%";

            actualS = "\u2193 Inbound  "
                    + getReadableSpeed((int) inboundSpeedBakedServer)
                    + "  Total  "
                    + getReadableSize(inboundBytesBakedServer)
                    + "    \u2191 Outbound  "
                    + getReadableSpeed((int) outboundSpeedBakedServer)
                    + "  Total  "
                    + getReadableSize(outboundBytesBakedServer);
            rawS = "\u2193 Inbound  "
                    + getReadableSpeed((int) inboundSpeedRawServer)
                    + "  Total  "
                    + getReadableSize(inboundBytesRawServer)
                    + "    \u2191 Outbound  "
                    + getReadableSpeed((int) outboundSpeedRawServer)
                    + "  Total  "
                    + getReadableSize(outboundBytesRawServer);
            ratioS = "Ratio                            "
                    + (inboundBytesRawServer > 0
                    ? String.format("%.2f", 100d * inboundBytesBakedServer / inboundBytesRawServer) : "N/A")
                    + "%                                        "
                    + (outboundBytesRawServer > 0
                    ? String.format("%.2f", 100d * outboundBytesBakedServer / outboundBytesRawServer) : "N/A")
                    + "%";

            if (dictSizeServer > 0) {
                dictStatus = "Zstd Dictionary  \u00a7a\u2714 Active\u00a7r  (" + dictSizeServer / 1024 + " KiB)";
            } else if (dictSampleThresholdServer > 0) {
                dictStatus = "Zstd Dictionary  \u00a77Sampling\u00a7r  " + dictSampleCountServer + "/" + dictSampleThresholdServer;
            } else {
                dictStatus = "Zstd Dictionary  \u00a77-\u00a7r";
            }

            long total = chunkCacheHitsServer + chunkCacheMissesServer;
            String hitRate = total > 0
                    ? String.format("%.1f%%", 100.0 * chunkCacheHitsServer / total)
                    : "N/A";
            chunkCacheStatus = "Chunk Cache  "
                    + "\u00a7aHits\u00a7r " + chunkCacheHitsServer
                    + "  \u00a7cMisses\u00a7r " + chunkCacheMissesServer
                    + "  Hit Rate " + hitRate
                    + "  Saved " + getReadableSize(chunkCacheSavedBytesServer);
        }
        tick++;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        context.drawTextWithShadow(tr, client, 10, 10, 0xFFFFFF);
        context.drawTextWithShadow(tr, actual, 10, 30, 0xFFFFFF);
        context.drawTextWithShadow(tr, actualC, 10, 40, 0xFFFFFF);
        context.drawTextWithShadow(tr, raw, 10, 60, 0xFFFFFF);
        context.drawTextWithShadow(tr, rawC, 10, 70, 0xFFFFFF);
        context.drawTextWithShadow(tr, ratioC, 10, 90, 0xFFFFFF);

        context.drawTextWithShadow(tr, server, 10, 120, 0xFFFFFF);
        context.drawTextWithShadow(tr, actual, 10, 140, 0xFFFFFF);
        context.drawTextWithShadow(tr, actualS, 10, 150, 0xFFFFFF);
        context.drawTextWithShadow(tr, raw, 10, 170, 0xFFFFFF);
        context.drawTextWithShadow(tr, rawS, 10, 180, 0xFFFFFF);
        context.drawTextWithShadow(tr, ratioS, 10, 200, 0xFFFFFF);

        context.drawTextWithShadow(tr, dictStatus, 10, 230, 0xFFFFFF);
        context.drawTextWithShadow(tr, chunkCacheStatus, 10, 250, 0xFFFFFF);
    }

    private String getReadableSpeed(int bytes) {
        if (bytes < 1000) {
            return bytes + " \u00a77Bytes/S\u00a7r";
        } else if (bytes < 1000_000) {
            return String.format("%.1f \u00a77KiB/S\u00a7r", bytes / 1024f);
        } else {
            return String.format("%.2f \u00a77MiB/S\u00a7r", bytes / (1024 * 1024f));
        }
    }

    private String getReadableSize(long bytes) {
        if (bytes < 1000) {
            return bytes + " \u00a77Bytes\u00a7r";
        } else if (bytes < 1000_000) {
            return String.format("%.1f \u00a77KiB\u00a7r", bytes / 1024d);
        } else if (bytes < 1000_000_000) {
            return String.format("%.2f \u00a77MiB\u00a7r", bytes / (1024 * 1024d));
        } else {
            return String.format("%.2f \u00a77GiB\u00a7r", bytes / (1024 * 1024 * 1024d));
        }
    }
}
