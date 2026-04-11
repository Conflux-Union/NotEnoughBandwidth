package cn.ussshenzhou.notenoughbandwidth.stat;

import static cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager.*;

/**
 * Bridges live data sources into TimeSeries ring buffers for chart display.
 * Maintains 6 series: 3 for client-side, 3 for server-side.
 */
public final class ChartSampler {
    private static final int CAPACITY = 120;

    // Client-side series
    public static final TimeSeries clientNic = new TimeSeries(CAPACITY);
    public static final TimeSeries clientBaked = new TimeSeries(CAPACITY);
    public static final TimeSeries clientRaw = new TimeSeries(CAPACITY);

    // Server-side series
    public static final TimeSeries serverNic = new TimeSeries(CAPACITY);
    public static final TimeSeries serverBaked = new TimeSeries(CAPACITY);
    public static final TimeSeries serverRaw = new TimeSeries(CAPACITY);

    private ChartSampler() {}

    /**
     * Sample current speeds into all six series. Called every 10 ticks (500ms).
     */
    public static void sample() {
        // Client NIC
        if (SystemTrafficMonitor.isAvailable()) {
            clientNic.push(
                    SystemTrafficMonitor.getInboundBytesPerSec()
                            + SystemTrafficMonitor.getOutboundBytesPerSec()
            );
        } else {
            clientNic.push(0);
        }

        // Client mod stats
        clientBaked.push(
                (long) (LOCAL.inboundSpeedBaked().averageIn1s()
                        + LOCAL.outboundSpeedBaked().averageIn1s())
        );
        clientRaw.push(
                (long) (LOCAL.inboundSpeedRaw().averageIn1s()
                        + LOCAL.outboundSpeedRaw().averageIn1s())
        );

        // Server NIC
        serverNic.push(nicInboundSpeedServer + nicOutboundSpeedServer);

        // Server mod stats
        serverBaked.push((long) (inboundSpeedBakedServer + outboundSpeedBakedServer));
        serverRaw.push((long) (inboundSpeedRawServer + outboundSpeedRawServer));
    }

    public static void reset() {
        clientNic.reset();
        clientBaked.reset();
        clientRaw.reset();
        serverNic.reset();
        serverBaked.reset();
        serverRaw.reset();
    }
}
