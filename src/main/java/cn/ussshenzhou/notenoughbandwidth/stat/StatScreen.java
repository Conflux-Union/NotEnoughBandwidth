package cn.ussshenzhou.notenoughbandwidth.stat;

import cn.ussshenzhou.notenoughbandwidth.network.StatQueryPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

    private int tick = 0;

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
                    + getReadableSpeed((int) LOCAL.inboundSpeedBaked().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.inboundBytesBaked().get())
                    + "    ↑ Outbound  "
                    + getReadableSpeed((int) LOCAL.outboundSpeedBaked().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.outboundBytesBaked().get());
            rawC = "↓ Inbound  "
                    + getReadableSpeed((int) LOCAL.inboundSpeedRaw().averageIn1s())
                    + "  Total  "
                    + getReadableSize(LOCAL.inboundBytesRaw().get())
                    + "    ↑ Outbound  "
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

            actualS = "↓ Inbound  "
                    + getReadableSpeed((int) inboundSpeedBakedServer)
                    + "  Total  "
                    + getReadableSize(inboundBytesBakedServer)
                    + "    ↑ Outbound  "
                    + getReadableSpeed((int) outboundSpeedBakedServer)
                    + "  Total  "
                    + getReadableSize(outboundBytesBakedServer);
            rawS = "↓ Inbound  "
                    + getReadableSpeed((int) inboundSpeedRawServer)
                    + "  Total  "
                    + getReadableSize(inboundBytesRawServer)
                    + "    ↑ Outbound  "
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
        }
        tick++;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x80000000);
        var tr = this.textRenderer;
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

        super.render(context, mouseX, mouseY, delta);
    }

    private String getReadableSpeed(int bytes) {
        if (bytes < 1000) {
            return bytes + " §7Bytes/S§r";
        } else if (bytes < 1000_000) {
            return String.format("%.1f §7KiB/S§r", bytes / 1024f);
        } else {
            return String.format("%.2f §7MiB/S§r", bytes / (1024 * 1024f));
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
