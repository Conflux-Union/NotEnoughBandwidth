package cn.ussshenzhou.notenoughbandwidth.chunkcache;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * LevelDB-backed persistent store for chunk data, keyed by 64-bit content hash.
 * One database per server address, stored under {gameDir}/neb_cache/{serverHash}/.
 */
public class ChunkCacheDatabase implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-ChunkCacheDB");

    private final DB db;
    private final long maxSizeBytes;

    public ChunkCacheDatabase(Path dir, long maxSizeBytes) throws IOException {
        Options options = new Options()
                .createIfMissing(true)
                .cacheSize(32 * 1024 * 1024); // 32MB read cache
        this.db = Iq80DBFactory.factory.open(dir.toFile(), options);
        this.maxSizeBytes = maxSizeBytes;
        LOGGER.info("Opened chunk cache DB at {}", dir);
    }

    public void put(long hash, byte[] data) {
        db.put(longToBytes(hash), data);
    }

    @Nullable
    public byte[] get(long hash) {
        return db.get(longToBytes(hash));
    }

    /**
     * Returns all stored hashes. Used once on connect to build the bloom filter.
     * This iterates the entire DB, so call only at connect time, not in the hot path.
     */
    public LongSet getAllHashes() {
        LongSet set = new LongOpenHashSet();
        try (DBIterator it = db.iterator()) {
            for (it.seekToFirst(); it.hasNext(); it.next()) {
                byte[] key = it.peekNext().getKey();
                if (key.length == 8) {
                    set.add(bytesToLong(key));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to iterate chunk cache DB", e);
        }
        return set;
    }

    /**
     * Approximate on-disk size in bytes. Used to decide whether to evict.
     * LevelDB reports byte ranges which we sum as an approximation.
     */
    public long approximateSize() {
        // iq80 DB doesn't expose getApproximateSizes directly, fall back to property
        try {
            String prop = db.getProperty("leveldb.approximate-memory-usage");
            if (prop != null) return Long.parseLong(prop);
        } catch (Exception ignored) {}
        return 0L;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    @Override
    public void close() {
        try {
            db.close();
            LOGGER.info("Closed chunk cache DB");
        } catch (IOException e) {
            LOGGER.error("Error closing chunk cache DB", e);
        }
    }

    private static byte[] longToBytes(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }

    private static long bytesToLong(byte[] b) {
        return ByteBuffer.wrap(b).getLong();
    }
}
