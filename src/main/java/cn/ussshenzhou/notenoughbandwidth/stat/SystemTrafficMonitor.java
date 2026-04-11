package cn.ussshenzhou.notenoughbandwidth.stat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors system-level NIC traffic on Linux, Windows, and macOS.
 * Sums traffic across all non-loopback interfaces.
 * Polls OS counters every second and computes bytes/sec rates.
 */
public final class SystemTrafficMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-SystemTraffic");

    private static ScheduledExecutorService executor;
    private static volatile boolean available;
    private static volatile long inboundBytesPerSec;
    private static volatile long outboundBytesPerSec;

    private static long prevRx = -1;
    private static long prevTx = -1;
    private static long prevTimeMs;

    private static final int POLL_INTERVAL_MS = 1000;

    private enum Platform { LINUX, WINDOWS, MAC, UNKNOWN }

    private static Platform platform;

    private SystemTrafficMonitor() {}

    public static synchronized void init() {
        if (executor != null) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            platform = Platform.LINUX;
        } else if (os.contains("win")) {
            platform = Platform.WINDOWS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            platform = Platform.MAC;
        } else {
            platform = Platform.UNKNOWN;
            LOGGER.warn("Unsupported OS for system traffic monitoring: {}", os);
            return;
        }

        available = true;
        prevTimeMs = System.currentTimeMillis();

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NEB-SystemTrafficMonitor");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(SystemTrafficMonitor::poll, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOGGER.info("System traffic monitor started (platform={})", platform);
    }

    public static void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        available = false;
        prevRx = -1;
        prevTx = -1;
        inboundBytesPerSec = 0;
        outboundBytesPerSec = 0;
    }

    public static boolean isAvailable() {
        return available;
    }

    public static long getInboundBytesPerSec() {
        return inboundBytesPerSec;
    }

    public static long getOutboundBytesPerSec() {
        return outboundBytesPerSec;
    }

    private static void poll() {
        try {
            long[] rxTx = switch (platform) {
                case LINUX -> pollLinux();
                case WINDOWS -> pollWindows();
                case MAC -> pollMac();
                default -> null;
            };

            if (rxTx == null) {
                return;
            }

            long now = System.currentTimeMillis();
            if (prevRx >= 0) {
                long elapsed = now - prevTimeMs;
                if (elapsed > 0) {
                    long deltaRx = Math.max(0, rxTx[0] - prevRx);
                    long deltaTx = Math.max(0, rxTx[1] - prevTx);
                    inboundBytesPerSec = deltaRx * 1000 / elapsed;
                    outboundBytesPerSec = deltaTx * 1000 / elapsed;
                }
            }
            prevRx = rxTx[0];
            prevTx = rxTx[1];
            prevTimeMs = now;
        } catch (Exception e) {
            LOGGER.error("Failed to poll system traffic, disabling monitor", e);
            available = false;
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Parses /proc/net/dev, summing rx/tx bytes across all non-loopback interfaces.
     * Format: "  iface: rx_bytes rx_packets ... tx_bytes tx_packets ..."
     */
    private static long[] pollLinux() throws Exception {
        long totalRx = 0;
        long totalTx = 0;
        boolean found = false;
        for (String line : Files.readAllLines(Path.of("/proc/net/dev"))) {
            String trimmed = line.trim();
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0) {
                continue;
            }
            String iface = trimmed.substring(0, colonIdx).trim();
            if (iface.equals("lo")) {
                continue;
            }
            String[] parts = trimmed.substring(colonIdx + 1).trim().split("\\s+");
            totalRx += Long.parseLong(parts[0]);
            totalTx += Long.parseLong(parts[8]);
            found = true;
        }
        return found ? new long[]{totalRx, totalTx} : null;
    }

    /**
     * Runs "netstat -e" on Windows and parses the Bytes row.
     * Already aggregates across all interfaces.
     */
    private static long[] pollWindows() throws Exception {
        Process p = new ProcessBuilder("netstat", "-e")
                .redirectErrorStream(true)
                .start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 3 && parts[0].equalsIgnoreCase("Bytes")) {
                    long rx = Long.parseLong(parts[1]);
                    long tx = Long.parseLong(parts[2]);
                    return new long[]{rx, tx};
                }
            }
        } finally {
            p.destroyForcibly();
        }
        return null;
    }

    /**
     * Runs "netstat -ib" on macOS, summing rx/tx bytes across all non-loopback link-layer rows.
     * Header: Name Mtu Network Address Ipkts Ierrs Ibytes Opkts Oerrs Obytes
     */
    private static long[] pollMac() throws Exception {
        long totalRx = 0;
        long totalTx = 0;
        boolean found = false;
        Process p = new ProcessBuilder("netstat", "-ib")
                .redirectErrorStream(true)
                .start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 10 && !parts[0].equals("lo0") && parts[2].contains("<Link")) {
                    totalRx += Long.parseLong(parts[6]);
                    totalTx += Long.parseLong(parts[9]);
                    found = true;
                }
            }
        } finally {
            p.destroyForcibly();
        }
        return found ? new long[]{totalRx, totalTx} : null;
    }
}
