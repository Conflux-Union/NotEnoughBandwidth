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
    private String clientNicStatus = "";
    private String serverNicStatus = "";

    private static final TimeSeries[] CLIENT_CHART_SERIES = {
            ChartSampler.clientNic, ChartSampler.clientBaked, ChartSampler.clientRaw
    };
    private static final TimeSeries[] SERVER_CHART_SERIES = {
            ChartSampler.serverNic, ChartSampler.serverBaked, ChartSampler.serverRaw
    };
    private static final int[] CHART_COLORS = {0xFF4488FF, 0xFF44FF88, 0xFFFF8844};
    private static final String[] CLIENT_CHART_LABELS = {"Client NIC", "Client Actual", "Client Raw"};
    private static final String[] SERVER_CHART_LABELS = {"Server NIC", "Server Actual", "Server Raw"};

    private static final int CONTENT_HEIGHT = 512;
    private static final int SCROLL_SPEED = 10;

    private int tick = 0;
    private double scrollOffset = 0;

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
                    + getReadableSpeed((long) LOCAL.inboundSpeedBaked().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.inboundBytesBaked().get())
                    + "    \u2191 Outbound  "
                    + getReadableSpeed((long) LOCAL.outboundSpeedBaked().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.outboundBytesBaked().get());
            rawC = "\u2193 Inbound  "
                    + getReadableSpeed((long) LOCAL.inboundSpeedRaw().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.inboundBytesRaw().get())
                    + "    \u2191 Outbound  "
                    + getReadableSpeed((long) LOCAL.outboundSpeedRaw().averageIn1s())
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
                    + getReadableSpeed((long) inboundSpeedBakedServer)
                    + "  Total  "
                    + getReadableSize(inboundBytesBakedServer)
                    + "    \u2191 Outbound  "
                    + getReadableSpeed((long) outboundSpeedBakedServer)
                    + "  Total  "
                    + getReadableSize(outboundBytesBakedServer);
            rawS = "\u2193 Inbound  "
                    + getReadableSpeed((long) inboundSpeedRawServer)
                    + "  Total  "
                    + getReadableSize(inboundBytesRawServer)
                    + "    \u2191 Outbound  "
                    + getReadableSpeed((long) outboundSpeedRawServer)
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

            if (SystemTrafficMonitor.isAvailable()) {
                clientNicStatus = "Client NIC  \u2193 "
                        + getReadableSpeed(SystemTrafficMonitor.getInboundBytesPerSec())
                        + "  \u2191 "
                        + getReadableSpeed(SystemTrafficMonitor.getOutboundBytesPerSec());
            } else {
                clientNicStatus = "Client NIC  \u00a77unavailable\u00a7r";
            }

            long serverNicTotal = nicInboundSpeedServer + nicOutboundSpeedServer;
            if (serverNicTotal > 0) {
                serverNicStatus = "Server NIC  \u2193 "
                        + getReadableSpeed(nicInboundSpeedServer)
                        + "  \u2191 "
                        + getReadableSpeed(nicOutboundSpeedServer);
            } else {
                serverNicStatus = "Server NIC  \u00a77-\u00a7r";
            }

            ChartSampler.sample();
        }
        tick++;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollOffset -= amount * SCROLL_SPEED;
        int maxScroll = Math.max(0, CONTENT_HEIGHT - this.height);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        var tr = this.textRenderer;

        context.getMatrices().push();
        context.getMatrices().translate(0, -scrollOffset, 0);

        // Text stats
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
        context.drawTextWithShadow(tr, clientNicStatus, 10, 270, 0xFFFFFF);
        context.drawTextWithShadow(tr, serverNicStatus, 10, 290, 0xFFFFFF);

        // Charts — side by side, with extra spacing below text
        int chartY = 342;
        int chartH = 120;
        int gap = 10;
        int chartW = (this.width - 30) / 2;

        context.drawTextWithShadow(tr, "Client", 10, chartY - 22, 0xFF88CCFF);
        LineChart.render(context, tr, 10, chartY, chartW, chartH,
                CLIENT_CHART_SERIES, CHART_COLORS, CLIENT_CHART_LABELS);

        int rightX = 10 + chartW + gap;
        context.drawTextWithShadow(tr, "Server", rightX, chartY - 22, 0xFFFFCC88);
        LineChart.render(context, tr, rightX, chartY, chartW, chartH,
                SERVER_CHART_SERIES, CHART_COLORS, SERVER_CHART_LABELS);

        context.getMatrices().pop();

        // Scroll indicator when content overflows
        int maxScroll = Math.max(0, CONTENT_HEIGHT - this.height);
        if (maxScroll > 0) {
            int barHeight = Math.max(10, this.height * this.height / CONTENT_HEIGHT);
            int barY = (int) (scrollOffset / maxScroll * (this.height - barHeight));
            context.fill(this.width - 4, barY, this.width - 1, barY + barHeight, 0x80FFFFFF);
        }
    }

    private String getReadableSpeed(long bytes) {
        if (bytes < 1000) {
            return bytes + " \u00a77Bytes/S\u00a7r";
        } else if (bytes < 1000_000) {
            return String.format("%.1f \u00a77KiB/S\u00a7r", bytes / 1024.0);
        } else {
            return String.format("%.2f \u00a77MiB/S\u00a7r", bytes / (1024 * 1024.0));
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
