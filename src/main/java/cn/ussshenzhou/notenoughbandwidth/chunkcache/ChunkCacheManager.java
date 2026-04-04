package cn.ussshenzhou.notenoughbandwidth.chunkcache;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.network.ClientConnection;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.WeakHashMap;

/**
 * Central coordinator for the chunk content-hash cache.
 *
 * CLIENT SIDE: one active DB per server session, bloom filter built from DB keys on connect.
 * SERVER SIDE: per-connection bloom filter received from the client on join.
 */
public class ChunkCacheManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-ChunkCache");
    private static final int BLOOM_EXPECTED_INSERTIONS = 200_000;
    private static final double BLOOM_FPR = 0.01;

    // --- Client state ---
    @Nullable private static ChunkCacheDatabase clientDb;
    @Nullable private static BloomFilter<Long> clientBloomFilter;
    @Nullable private static Path gameDir;

    // How many new chunks must be cached before we push an updated bloom filter to the server.
    private static final int RESEND_THRESHOLD = 64;
    private static int pendingNewChunks = 0;

    // --- Server state ---
    // Separate lock object so server-side bloom filter operations don't contend
    // with client-side DB operations (which hold the class monitor).
    private static final Object SERVER_LOCK = new Object();
    private static final WeakHashMap<ClientConnection, BloomFilter<Long>> SERVER_BLOOM_FILTERS =
            new WeakHashMap<>();

    // -------------------------------------------------------------------------
    // Client-side API
    // -------------------------------------------------------------------------

    public static void setGameDir(Path dir) {
        gameDir = dir;
    }

    /**
     * Called when the client connects to a server. Opens the DB and builds the bloom filter.
     * serverAddress is used to namespace the cache directory.
     */
    public static synchronized void onClientConnect(String serverAddress) {
        if (gameDir == null) {
            LOGGER.error("Game dir not set, chunk cache disabled");
            return;
        }
        // Close any leftover DB from a previous session (e.g. Velocity server switch
        // where DISCONNECT may not fire before the new INIT).
        if (clientDb != null) {
            clientDb.close();
            clientDb = null;
        }
        clientBloomFilter = null;
        pendingNewChunks = 0;

        var cfg = NotEnoughBandwidthConfig.get();
        if (!cfg.chunkCacheEnabled) return;

        try {
            Path cacheDir = gameDir.resolve("neb_cache").resolve(addressHash(serverAddress));
            Files.createDirectories(cacheDir);
            long maxBytes = (long) cfg.chunkCacheMaxSizeMB * 1024 * 1024;
            clientDb = new ChunkCacheDatabase(cacheDir, maxBytes);

            // Build bloom filter from all stored hashes.
            LongSet hashes = clientDb.getAllHashes();
            clientBloomFilter = BloomFilter.create(Funnels.longFunnel(), BLOOM_EXPECTED_INSERTIONS, BLOOM_FPR);
            hashes.forEach((long h) -> clientBloomFilter.put(h));
            LOGGER.info("Chunk cache opened: {} entries in bloom filter for {}", hashes.size(), serverAddress);
        } catch (IOException e) {
            LOGGER.error("Failed to open chunk cache for {}", serverAddress, e);
            clientDb = null;
            clientBloomFilter = null;
        }
    }

    public static synchronized void onClientDisconnect() {
        if (clientDb != null) {
            clientDb.close();
            clientDb = null;
        }
        clientBloomFilter = null;
    }

    /** Returns bloom filter bytes to send to the server, or null if cache is disabled/empty. */
    @Nullable
    public static synchronized byte[] getClientBloomFilterBytes() {
        if (clientBloomFilter == null) return null;
        try {
            var out = new ByteArrayOutputStream();
            clientBloomFilter.writeTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            LOGGER.error("Failed to serialize bloom filter", e);
            return null;
        }
    }

    @Nullable
    public static synchronized byte[] getClientCachedChunk(long hash) {
        if (clientDb == null) return null;
        return clientDb.get(hash);
    }

    public static synchronized void deleteClientCachedChunk(long hash) {
        if (clientDb != null) clientDb.delete(hash);
    }

    public static synchronized void cacheChunk(long hash, byte[] data) {
        if (clientDb == null) return;
        clientDb.put(hash, data);
        if (clientBloomFilter != null) {
            clientBloomFilter.put(hash);
            pendingNewChunks++;
        }
    }

    /**
     * Returns true (and resets the counter) when enough new chunks have been cached
     * to warrant pushing an updated bloom filter to the server.
     * Called from the cache-writer background thread; the caller is responsible for
     * dispatching the actual packet send to the main thread.
     */
    public static synchronized boolean drainAndShouldResend() {
        if (pendingNewChunks < RESEND_THRESHOLD) return false;
        pendingNewChunks = 0;
        return true;
    }

    /**
     * Runs DB eviction (disk I/O heavy) and, if entries were deleted, rebuilds the
     * bloom filter so it no longer contains hashes for entries that no longer exist.
     *
     * Must NOT be called while holding the ChunkCacheManager class monitor — eviction
     * invokes {@code Files.walk} and LevelDB compaction, which can block for tens of ms
     * and would stall all other synchronized operations on this class.
     * Call this after {@link #drainAndShouldResend()} returns true, before fetching
     * the bloom filter bytes to send.
     */
    public static void evictAndRebuildIfNeeded() {
        // Read clientDb reference under lock, then release before doing I/O.
        ChunkCacheDatabase db;
        synchronized (ChunkCacheManager.class) {
            db = clientDb;
        }
        if (db == null) return;

        // evictIfNeeded does Files.walk + LevelDB ops — runs outside the class monitor.
        boolean evicted = db.evictIfNeeded();

        if (evicted) {
            // Bloom filter update requires the lock (modifies clientBloomFilter).
            synchronized (ChunkCacheManager.class) {
                if (clientDb != null) {
                    rebuildClientBloomFilter();
                }
            }
        }
    }

    private static void rebuildClientBloomFilter() {
        LongSet hashes = clientDb.getAllHashes();
        clientBloomFilter = BloomFilter.create(Funnels.longFunnel(), BLOOM_EXPECTED_INSERTIONS, BLOOM_FPR);
        hashes.forEach((long h) -> clientBloomFilter.put(h));
        LOGGER.info("Rebuilt bloom filter after eviction: {} entries", hashes.size());
    }

    public static boolean isClientEnabled() {
        return clientDb != null;
    }

    // -------------------------------------------------------------------------
    // Server-side API
    // -------------------------------------------------------------------------

    /** Stores the bloom filter received from the client for a given connection. */
    public static void setServerBloomFilter(ClientConnection connection, byte[] bloomFilterBytes) {
        try {
            BloomFilter<Long> filter = BloomFilter.readFrom(
                    new ByteArrayInputStream(bloomFilterBytes), Funnels.longFunnel());
            synchronized (SERVER_LOCK) {
                SERVER_BLOOM_FILTERS.put(connection, filter);
            }
            LOGGER.debug("Stored bloom filter for {}", connection.getAddress());
        } catch (IOException e) {
            LOGGER.error("Failed to deserialize client bloom filter", e);
        }
    }

    /**
     * Returns true if the client has likely cached a chunk with this hash.
     * Returns false conservatively when no bloom filter is available.
     */
    public static boolean serverMightHaveChunk(ClientConnection connection, long hash) {
        BloomFilter<Long> filter;
        synchronized (SERVER_LOCK) {
            filter = SERVER_BLOOM_FILTERS.get(connection);
        }
        return filter != null && filter.mightContain(hash);
    }

    public static void removeServerBloomFilter(ClientConnection connection) {
        synchronized (SERVER_LOCK) {
            SERVER_BLOOM_FILTERS.remove(connection);
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static String addressHash(String address) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(address.getBytes(StandardCharsets.UTF_8));
            // Use first 8 bytes → 16 hex chars as directory name
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available
            throw new AssertionError(e);
        }
    }
}
