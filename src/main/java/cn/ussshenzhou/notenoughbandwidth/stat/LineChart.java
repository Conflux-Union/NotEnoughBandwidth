package cn.ussshenzhou.notenoughbandwidth.stat;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Renders a multi-line time-series chart using DrawContext primitives.
 */
public final class LineChart {

    private static final int BG_COLOR = 0x80000000;
    private static final int GRID_COLOR = 0x40FFFFFF;
    private static final int AXIS_COLOR = 0xFFAAAAAA;
    private static final int GRID_LINES = 4;

    private LineChart() {}

    /**
     * @param series array of TimeSeries to plot
     * @param colors ARGB color for each series
     * @param labels display name for each series
     */
    public static void render(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h,
                              TimeSeries[] series, int[] colors, String[] labels) {
        // Background
        ctx.fill(x, y, x + w, y + h, BG_COLOR);

        // Axes
        drawVerticalLine(ctx, x, y, y + h, AXIS_COLOR);
        drawHorizontalLine(ctx, x, x + w, y + h, AXIS_COLOR);

        // Take consistent snapshots to avoid TOCTOU races with the sampling thread
        long[][] snapshots = new long[series.length][];
        for (int i = 0; i < series.length; i++) {
            snapshots[i] = series[i].snapshot();
        }
        int capacity = series.length > 0 ? series[0].capacity() : 120;

        // Compute global max for auto-scaling
        long globalMax = 1;
        for (long[] snap : snapshots) {
            for (long v : snap) {
                if (v > globalMax) {
                    globalMax = v;
                }
            }
        }
        globalMax = globalMax + globalMax / 10 + 1; // 10% headroom

        // Grid lines and Y-axis labels
        for (int i = 1; i <= GRID_LINES; i++) {
            int gy = y + h - (h * i / GRID_LINES);
            drawHorizontalLine(ctx, x + 1, x + w, gy, GRID_COLOR);
            long value = globalMax * i / GRID_LINES;
            String label = formatRate(value);
            ctx.drawText(tr, label, x + 2, gy - 9, 0xFFCCCCCC, false);
        }

        // X-axis labels
        ctx.drawText(tr, "60s", x + 2, y + h + 2, 0xFF999999, false);
        ctx.drawText(tr, "now", x + w - tr.getWidth("now"), y + h + 2, 0xFF999999, false);

        // Data lines
        int chartW = w - 1;
        int chartH = h - 1;
        for (int s = 0; s < snapshots.length; s++) {
            drawSeriesFromSnapshot(ctx, snapshots[s], capacity, colors[s], x + 1, y, chartW, chartH, globalMax);
        }

        // Legend
        int legendY = y + h + 13;
        int legendX = x;
        for (int i = 0; i < labels.length; i++) {
            ctx.fill(legendX, legendY, legendX + 8, legendY + 8, colors[i]);
            ctx.drawText(tr, labels[i], legendX + 11, legendY, 0xFFDDDDDD, false);
            legendX += 11 + tr.getWidth(labels[i]) + 12;
        }
    }

    private static void drawSeriesFromSnapshot(DrawContext ctx, long[] snap, int capacity, int color,
                                               int ox, int oy, int chartW, int chartH, long maxVal) {
        if (snap.length < 2) {
            return;
        }

        for (int i = 0; i < snap.length - 1; i++) {
            int x1 = ox + (int) ((long) i * chartW / (capacity - 1));
            int x2 = ox + (int) ((long) (i + 1) * chartW / (capacity - 1));
            int y1 = oy + chartH - (int) (snap[i] * chartH / maxVal);
            int y2 = oy + chartH - (int) (snap[i + 1] * chartH / maxVal);

            drawLineSegment(ctx, x1, y1, x2, y2, color);
        }
    }

    /**
     * Draw a line between two points using 1px-wide vertical fills (Bresenham-lite).
     */
    private static void drawLineSegment(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        int dx = x2 - x1;
        if (dx == 0) {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            ctx.fill(x1, minY, x1 + 1, maxY + 1, color);
            return;
        }

        for (int x = x1; x <= x2; x++) {
            int t = x - x1;
            int yInterp = y1 + (y2 - y1) * t / dx;
            ctx.fill(x, yInterp, x + 1, yInterp + 1, color);
        }

        // Fill gaps between adjacent interpolated Y values
        for (int x = x1; x < x2; x++) {
            int t1 = x - x1;
            int t2 = t1 + 1;
            int ya = y1 + (y2 - y1) * t1 / dx;
            int yb = y1 + (y2 - y1) * t2 / dx;
            if (ya != yb) {
                int minY = Math.min(ya, yb);
                int maxY = Math.max(ya, yb);
                ctx.fill(x, minY, x + 1, maxY + 1, color);
            }
        }
    }

    private static void drawVerticalLine(DrawContext ctx, int x, int y1, int y2, int color) {
        ctx.fill(x, y1, x + 1, y2, color);
    }

    private static void drawHorizontalLine(DrawContext ctx, int x1, int x2, int y, int color) {
        ctx.fill(x1, y, x2, y + 1, color);
    }

    private static String formatRate(long bytesPerSec) {
        if (bytesPerSec < 1000) {
            return bytesPerSec + " B/s";
        } else if (bytesPerSec < 1_000_000) {
            return String.format("%.1f KiB/s", bytesPerSec / 1024.0);
        } else {
            return String.format("%.1f MiB/s", bytesPerSec / (1024.0 * 1024.0));
        }
    }
}
