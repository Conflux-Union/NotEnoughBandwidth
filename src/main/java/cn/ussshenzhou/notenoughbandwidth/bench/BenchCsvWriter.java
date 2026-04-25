package cn.ussshenzhou.notenoughbandwidth.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects per-tick wire byte/packet samples and writes them as a two-file pair:
 *   - {output}.csv       : per-tick samples (tick, bytes_delta, bytes_cum, packets_delta, packets_cum)
 *   - {output}.meta.json : run metadata (mode, scenario, ticks, totals, compression ratio markers)
 * <p>
 * The meta file is written as raw JSON by hand; avoiding a JSON dep keeps the bench
 * code free of extra classpath concerns.
 */
public final class BenchCsvWriter {
    private final List<long[]> samples = new ArrayList<>(1500);
    private long prevBytes;
    private long prevPackets;

    public void sample(int tick, long bytesCum, long packetsCum) {
        long db = bytesCum - prevBytes;
        long dp = packetsCum - prevPackets;
        prevBytes = bytesCum;
        prevPackets = packetsCum;
        samples.add(new long[]{tick, db, bytesCum, dp, packetsCum});
    }

    public int sampleCount() {
        return samples.size();
    }

    public long totalBytes() {
        return prevBytes;
    }

    public long totalPackets() {
        return prevPackets;
    }

    public void writeCsv(Path path, String mode, String scenario) throws IOException {
        Files.createDirectories(path.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            w.write("# mode=" + mode + " scenario=" + scenario + " samples=" + samples.size()
                    + " total_bytes=" + prevBytes + " total_packets=" + prevPackets + "\n");
            w.write("tick,bytes_delta,bytes_cum,packets_delta,packets_cum\n");
            for (long[] s : samples) {
                w.write(s[0] + "," + s[1] + "," + s[2] + "," + s[3] + "," + s[4] + "\n");
            }
        }
    }

    public void writeMeta(Path path, String mode, String scenario, long durationMs) throws IOException {
        Files.createDirectories(path.getParent());
        String json = "{"
                + "\"mode\":\"" + mode + "\","
                + "\"scenario\":\"" + scenario + "\","
                + "\"ticks\":" + samples.size() + ","
                + "\"total_bytes\":" + prevBytes + ","
                + "\"total_packets\":" + prevPackets + ","
                + "\"wall_ms\":" + durationMs
                + "}";
        Files.writeString(path, json);
    }
}
