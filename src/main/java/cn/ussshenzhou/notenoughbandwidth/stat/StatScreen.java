package cn.ussshenzhou.notenoughbandwidth.stat;

import cn.ussshenzhou.notenoughbandwidth.network.StatQueryPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import static cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager.*;
import static cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager.chunkCacheSavedBytesServer;

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
                ClientPlayNetworking.send(new StatQueryPayload());
            } catch (Exception ignored) {
            }
            actualC = "↓ Inbound  "
                    + getReadableSpeed((long) LOCAL.inboundSpeedBaked().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.inboundBytesBaked().get())
                    + "    ↑ Outbound  "
                    + getReadableSpeed((long) LOCAL.outboundSpeedBaked().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.outboundBytesBaked().get());
            rawC = "↓ Inbound  "
                    + getReadableSpeed((long) LOCAL.inboundSpeedRaw().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.inboundBytesRaw().get())
                    + "    ↑ Outbound  "
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

            actualS = "↓ Inbound  "
                    + getReadableSpeed((long) inboundSpeedBakedServer)
                    + "  Total  "
                    + getReadableSize(inboundBytesBakedServer)
                    + "    ↑ Outbound  "
                    + getReadableSpeed((long) outboundSpeedBakedServer)
                    + "  Total  "
                    + getReadableSize(outboundBytesBakedServer);
            rawS = "↓ Inbound  "
                    + getReadableSpeed((long) inboundSpeedRawServer)
                    + "  Total  "
                    + getReadableSize(inboundBytesRawServer)
                    + "    ↑ Outbound  "
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
                dictStatus = "Zstd Dictionary  §a✔ Active§r  (" + dictSizeServer / 1024 + " KiB)";
            } else if (dictSampleThresholdServer > 0) {
                dictStatus = "Zstd Dictionary  §7Sampling§r  " + dictSampleCountServer + "/" + dictSampleThresholdServer;
            } else {
                dictStatus = "Zstd Dictionary  §7-§r";
            }

            long total = chunkCacheHitsServer + chunkCacheMissesServer;
            String hitRate = total > 0
                    ? String.format("%.1f%%", 100.0 * chunkCacheHitsServer / total)
                    : "N/A";
            chunkCacheStatus = "Chunk Cache  "
                    + "§aHits§r " + chunkCacheHitsServer
                    + "  §cMisses§r " + chunkCacheMissesServer
                    + "  Hit Rate " + hitRate
                    + "  Saved " + getReadableSize(chunkCacheSavedBytesServer);

            if (SystemTrafficMonitor.isAvailable()) {
                clientNicStatus = "Client NIC  ↓ "
                        + getReadableSpeed(SystemTrafficMonitor.getInboundBytesPerSec())
                        + "  ↑ "
                        + getReadableSpeed(SystemTrafficMonitor.getOutboundBytesPerSec());
            } else {
                clientNicStatus = "Client NIC  §7unavailable§r";
            }

            long serverNicTotal = nicInboundSpeedServer + nicOutboundSpeedServer;
            if (serverNicTotal > 0) {
                serverNicStatus = "Server NIC  ↓ "
                        + getReadableSpeed(nicInboundSpeedServer)
                        + "  ↑ "
                        + getReadableSpeed(nicOutboundSpeedServer);
            } else {
                serverNicStatus = "Server NIC  §7-§r";
            }

            ChartSampler.sample();
        }
        tick++;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollOffset -= verticalAmount * SCROLL_SPEED;
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
        context.drawText(tr, client, 10, 10, 0xFFFFFF, true);
        context.drawText(tr, actual, 10, 30, 0xFFFFFF, true);
        context.drawText(tr, actualC, 10, 40, 0xFFFFFF, true);
        context.drawText(tr, raw, 10, 60, 0xFFFFFF, true);
        context.drawText(tr, rawC, 10, 70, 0xFFFFFF, true);
        context.drawText(tr, ratioC, 10, 90, 0xFFFFFF, true);

        context.drawText(tr, server, 10, 120, 0xFFFFFF, true);
        context.drawText(tr, actual, 10, 140, 0xFFFFFF, true);
        context.drawText(tr, actualS, 10, 150, 0xFFFFFF, true);
        context.drawText(tr, raw, 10, 170, 0xFFFFFF, true);
        context.drawText(tr, rawS, 10, 180, 0xFFFFFF, true);
        context.drawText(tr, ratioS, 10, 200, 0xFFFFFF, true);

        context.drawText(tr, dictStatus, 10, 230, 0xFFFFFF, true);
        context.drawText(tr, chunkCacheStatus, 10, 250, 0xFFFFFF, true);
        context.drawText(tr, clientNicStatus, 10, 270, 0xFFFFFF, true);
        context.drawText(tr, serverNicStatus, 10, 290, 0xFFFFFF, true);

        // Charts — side by side, with extra spacing below text
        int chartY = 342;
        int chartH = 120;
        int gap = 10;
        int chartW = (this.width - 30) / 2;

        context.drawText(tr, "Client", 10, chartY - 22, 0xFF88CCFF, true);
        LineChart.render(context, tr, 10, chartY, chartW, chartH,
                CLIENT_CHART_SERIES, CHART_COLORS, CLIENT_CHART_LABELS);

        int rightX = 10 + chartW + gap;
        context.drawText(tr, "Server", rightX, chartY - 22, 0xFFFFCC88, true);
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
            return bytes + " §7Bytes/S§r";
        } else if (bytes < 1000_000) {
            return String.format("%.1f §7KiB/S§r", bytes / 1024.0);
        } else {
            return String.format("%.2f §7MiB/S§r", bytes / (1024 * 1024.0));
        }
    }

    private String getReadableSize(long bytes) {
        if (bytes < 1000) {
            return bytes + " §7Bytes§r";
        } else if (bytes < 1000_000) {
            return String.format("%.1f §7KiB§r", bytes / 1024d);
        } else if (bytes < 1000_000_000) {
            return String.format("%.2f §7MiB§r", bytes / (1024 * 1024d));
        } else {
            return String.format("%.2f §7GiB§r", bytes / (1024 * 1024 * 1024d));
        }
    }
}
