package cn.ussshenzhou.notenoughbandwidth.stat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.NetworkSide;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-PacketType byte counters for the P-1 measurement probe.
 * Direction follows NetworkSide: CLIENTBOUND = S2C, SERVERBOUND = C2S.
 * Aggregated bundle bytes are attributed to sub-packets proportionally to
 * their pre-compression payload share.
 */
public class PacketTypeStatManager {

    public static final class Counter {
        public final AtomicLong rawBytes = new AtomicLong();
        public final AtomicLong bakedBytes = new AtomicLong();
        public final AtomicLong count = new AtomicLong();

        void add(long raw, long baked) {
            rawBytes.addAndGet(raw);
            bakedBytes.addAndGet(baked);
            count.incrementAndGet();
        }
    }

    private static final ConcurrentHashMap<Identifier, Counter> S2C = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Identifier, Counter> C2S = new ConcurrentHashMap<>();
    private static final Identifier LEVEL_CHUNK_WITH_LIGHT =
            Identifier.of("minecraft", "level_chunk_with_light");
    private static volatile long resetNanos = System.nanoTime();

    public static void record(NetworkSide direction, Identifier type, long rawBytes, long bakedBytes) {
        if (type == null || direction == null || rawBytes < 0 || bakedBytes < 0) return;
        var map = direction == NetworkSide.CLIENTBOUND ? S2C : C2S;
        map.computeIfAbsent(type, k -> new Counter()).add(rawBytes, bakedBytes);
    }

    public static void reset() {
        S2C.clear();
        C2S.clear();
        ChunkPacketBreakdown.reset();
        resetNanos = System.nanoTime();
    }

    public static long elapsedSeconds() {
        return Math.max(1L, (System.nanoTime() - resetNanos) / 1_000_000_000L);
    }

    public static long getS2cRawBytes(Identifier type) {
        Counter c = S2C.get(type);
        return c == null ? 0 : c.rawBytes.get();
    }

    public record TopRow(NetworkSide direction, Identifier type, long count, long rawBytes, long bakedBytes) {}

    public static List<TopRow> top(int n) {
        List<TopRow> rows = new ArrayList<>();
        S2C.forEach((id, c) -> rows.add(new TopRow(NetworkSide.CLIENTBOUND, id, c.count.get(), c.rawBytes.get(), c.bakedBytes.get())));
        C2S.forEach((id, c) -> rows.add(new TopRow(NetworkSide.SERVERBOUND, id, c.count.get(), c.rawBytes.get(), c.bakedBytes.get())));
        rows.sort(Comparator.comparingLong((TopRow r) -> r.bakedBytes).reversed());
        return rows.size() > n ? new ArrayList<>(rows.subList(0, n)) : rows;
    }

    /**
     * Write a CSV snapshot to config/neb-stats/. Returns the absolute file path.
     */
    public static Path dumpCsv(String label) throws IOException {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("neb-stats");
        Files.createDirectories(dir);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
        String safeLabel = (label == null || label.isBlank()) ? "default"
                : label.replaceAll("[^A-Za-z0-9_-]", "_");
        Path file = dir.resolve("neb-stats-" + safeLabel + "-" + stamp + ".csv");

        long elapsedSec = elapsedSeconds();
        long s2cTotalRaw = sumRaw(S2C);
        long s2cTotalBaked = sumBaked(S2C);
        long c2sTotalRaw = sumRaw(C2S);
        long c2sTotalBaked = sumBaked(C2S);

        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("# NEB per-PacketType byte counters\n");
            w.write("# label=" + safeLabel + "\n");
            w.write("# elapsed_seconds=" + elapsedSec + "\n");
            w.write("# note: single-player integrated server double-counts (both client and server pipelines run in-process). Use a dedicated server for accurate numbers.\n");
            long cdBytes = ChunkPacketBreakdown.chunkDataBytes.get();
            long cdCalls = ChunkPacketBreakdown.chunkDataCalls.get();
            long ldBytesTotal = ChunkPacketBreakdown.lightDataBytes.get();
            long ldCallsTotal = ChunkPacketBreakdown.lightDataCalls.get();
            // chunk_pkt_raw - chunk_data = light data inside chunk packets (plus a few bytes
            // of chunkX/chunkZ varints per packet — <0.1% overhead, ignored).
            Counter chunkPktCounter = S2C.get(LEVEL_CHUNK_WITH_LIGHT);
            long chunkPktRaw = chunkPktCounter == null ? 0 : chunkPktCounter.rawBytes.get();
            long lightInsideChunkPkt = Math.max(0, chunkPktRaw - cdBytes);
            double lightShareOfChunkPkt = chunkPktRaw == 0 ? 0 : 100.0 * lightInsideChunkPkt / chunkPktRaw;
            double lightTotalShareOfS2cRaw = s2cTotalRaw == 0 ? 0 : 100.0 * ldBytesTotal / s2cTotalRaw;
            w.write("# chunk_packet_breakdown (pre-NEB raw bytes, S2C only)\n");
            w.write(String.format(Locale.ROOT,
                    "#   chunk_data_bytes=%d (calls=%d)%n",
                    cdBytes, cdCalls));
            w.write(String.format(Locale.ROOT,
                    "#   light_data_total_bytes=%d (calls=%d, incl. chunk-packet and standalone light_update)%n",
                    ldBytesTotal, ldCallsTotal));
            w.write(String.format(Locale.ROOT,
                    "#   light_data_inside_chunk_packet=%d%n",
                    lightInsideChunkPkt));
            w.write(String.format(Locale.ROOT,
                    "#   light_share_of_chunk_packet=%.2f%% light_total_share_of_s2c_raw=%.2f%%%n",
                    lightShareOfChunkPkt, lightTotalShareOfS2cRaw));
            w.write("# s2c_total_raw_bytes=" + s2cTotalRaw + " s2c_total_baked_bytes=" + s2cTotalBaked + "\n");
            w.write("# c2s_total_raw_bytes=" + c2sTotalRaw + " c2s_total_baked_bytes=" + c2sTotalBaked + "\n");
            w.write("direction,packet_type,count,raw_bytes,baked_bytes,raw_share_pct,baked_share_pct,baked_bytes_per_second\n");
            writeRows(w, "S2C", S2C, s2cTotalRaw, s2cTotalBaked, elapsedSec);
            writeRows(w, "C2S", C2S, c2sTotalRaw, c2sTotalBaked, elapsedSec);
        }
        return file;
    }

    private static long sumBaked(Map<Identifier, Counter> m) {
        long t = 0;
        for (var c : m.values()) t += c.bakedBytes.get();
        return t;
    }

    private static long sumRaw(Map<Identifier, Counter> m) {
        long t = 0;
        for (var c : m.values()) t += c.rawBytes.get();
        return t;
    }

    private static void writeRows(Writer w, String dirLabel, Map<Identifier, Counter> map,
                                  long totalRaw, long totalBaked, long elapsedSec) throws IOException {
        var rows = new ArrayList<>(map.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue().bakedBytes.get(), a.getValue().bakedBytes.get()));
        for (var e : rows) {
            var c = e.getValue();
            long raw = c.rawBytes.get();
            long baked = c.bakedBytes.get();
            double rawPct = totalRaw == 0 ? 0 : 100.0 * raw / totalRaw;
            double bakedPct = totalBaked == 0 ? 0 : 100.0 * baked / totalBaked;
            long bps = baked / elapsedSec;
            w.write(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%.3f,%.3f,%d%n",
                    dirLabel, e.getKey(), c.count.get(), raw, baked, rawPct, bakedPct, bps));
        }
    }
}
