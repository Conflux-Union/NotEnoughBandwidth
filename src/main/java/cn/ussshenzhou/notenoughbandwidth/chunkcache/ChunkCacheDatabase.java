package cn.ussshenzhou.notenoughbandwidth.chunkcache;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import com.github.luben.zstd.Zstd;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * LevelDB-backed persistent store for chunk data, keyed by 64-bit content hash.
 * One database per server address, stored under {gameDir}/neb_cache/{serverHash}/.
 */
public class ChunkCacheDatabase implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-ChunkCacheDB");

    // Bump this when the on-disk format changes to invalidate old caches automatically.
    private static final int FORMAT_VERSION = 4;
    private static final int TIMESTAMP_BYTES = 8;
    private static final byte[] VERSION_KEY = "NEB_FORMAT_VERSION".getBytes(StandardCharsets.UTF_8);

    private final DB db;
    private final Path dbDir;
    private final long maxSizeBytes;

    public ChunkCacheDatabase(Path dir, long maxSizeBytes) throws IOException {
        Options options = new Options()
                .createIfMissing(true)
                .cacheSize(32 * 1024 * 1024); // 32MB read cache
        this.db = Iq80DBFactory.factory.open(dir.toFile(), options);
        this.dbDir = dir;
        this.maxSizeBytes = maxSizeBytes;
        try {
            migrateIfNeeded();
        } catch (Exception e) {
            try { db.close(); } catch (Exception suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
        LOGGER.info("Opened chunk cache DB at {}", dir);
    }

    /**
     * Clears the DB if the stored format version doesn't match FORMAT_VERSION.
     * Avoids trying to decompress legacy uncompressed entries.
     */
    private void migrateIfNeeded() {
        byte[] stored = db.get(VERSION_KEY);
        int storedVersion = (stored != null && stored.length == 4)
                ? ByteBuffer.wrap(stored).getInt() : -1;
        if (storedVersion == FORMAT_VERSION) return;

        LOGGER.info("Chunk cache format version mismatch (stored={}, current={}), clearing DB",
                storedVersion, FORMAT_VERSION);
        try (DBIterator it = db.iterator()) {
            List<byte[]> keys = new ArrayList<>();
            for (it.seekToFirst(); it.hasNext(); it.next()) {
                keys.add(it.peekNext().getKey());
            }
            for (byte[] key : keys) db.delete(key);
        } catch (IOException e) {
            LOGGER.error("Failed to clear DB during migration", e);
        }
        db.put(VERSION_KEY, ByteBuffer.allocate(4).putInt(FORMAT_VERSION).array());
    }

    public void put(long hash, byte[] data) {
        int compressionLevel = NotEnoughBandwidthConfig.get().getChunkCacheCompressionLevel();
        byte[] compressed = Zstd.compress(data, compressionLevel);
        byte[] value = new byte[TIMESTAMP_BYTES + compressed.length];
        ByteBuffer.wrap(value).putLong(System.currentTimeMillis());
        System.arraycopy(compressed, 0, value, TIMESTAMP_BYTES, compressed.length);
        db.put(longToBytes(hash), value);
    }

    public void delete(long hash) {
        db.delete(longToBytes(hash));
    }

    @Nullable
    public byte[] get(long hash) {
        byte[] value = db.get(longToBytes(hash));
        if (value == null) return null;
        if (value.length <= TIMESTAMP_BYTES) {
            LOGGER.warn("Corrupt entry for hash {} (length={}), dropping", hash, value.length);
            db.delete(longToBytes(hash));
            return null;
        }
        byte[] compressed = new byte[value.length - TIMESTAMP_BYTES];
        System.arraycopy(value, TIMESTAMP_BYTES, compressed, 0, compressed.length);
        long originalSize = Zstd.decompressedSize(compressed);
        if (originalSize < 0 || originalSize > 64 * 1024 * 1024) {
            LOGGER.warn("Invalid decompressed size {} for hash {}, dropping entry", originalSize, hash);
            db.delete(longToBytes(hash));
            return null;
        }
        if (originalSize == 0) return new byte[0];
        return Zstd.decompress(compressed, (int) originalSize);
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
     * Returns the actual on-disk size of the DB directory in bytes.
     */
    public long approximateSize() {
        try (Stream<Path> walk = Files.walk(dbDir)) {
            return walk.filter(Files::isRegularFile)
                       .mapToLong(p -> {
                           try { return Files.size(p); } catch (IOException e) { return 0L; }
                       })
                       .sum();
        } catch (IOException e) {
            LOGGER.warn("Failed to measure chunk cache size", e);
            return 0L;
        }
    }

    /**
     * Evicts oldest entries (by stored timestamp) until the DB is at 70% of max capacity.
     * Applies a 0.8 discount to the raw filesystem size to account for LevelDB metadata
     * overhead (SST indexes, bloom filters, log files) that inflate the directory size
     * beyond the actual user data stored.
     *
     * @return true if any entries were deleted
     */
    public boolean evictIfNeeded() {
        if (maxSizeBytes <= 0) return false;
        long rawSize = approximateSize();
        long effectiveSize = (long) (rawSize * 0.8);
        if (effectiveSize <= maxSizeBytes) return false;

        long targetSize = (long) (maxSizeBytes * 0.7);
        long bytesToFree = effectiveSize - targetSize;

        // Scan all entries, collecting key + timestamp + value size for sorting.
        record Entry(byte[] key, long timestamp, int valueSize) {}
        List<Entry> entries = new ArrayList<>();
        try (DBIterator it = db.iterator()) {
            for (it.seekToFirst(); it.hasNext(); it.next()) {
                byte[] key = it.peekNext().getKey();
                if (key.length != 8) continue; // skip VERSION_KEY
                byte[] value = it.peekNext().getValue();
                long ts = (value != null && value.length > TIMESTAMP_BYTES)
                        ? ByteBuffer.wrap(value, 0, TIMESTAMP_BYTES).getLong() : 0L;
                int size = (value != null ? value.length : 0) + 8; // value + key
                entries.add(new Entry(key, ts, size));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan entries for eviction", e);
            return false;
        }

        // Sort oldest first.
        entries.sort(Comparator.comparingLong(Entry::timestamp));

        long freed = 0;
        int deleted = 0;
        for (Entry entry : entries) {
            if (freed >= bytesToFree) break;
            db.delete(entry.key);
            freed += entry.valueSize;
            deleted++;
        }

        try { db.compactRange(null, null); } catch (Exception ignored) {}
        LOGGER.info("Evicted {} entries from chunk cache (effective {} MB, target {} MB)",
                deleted, effectiveSize / 1024 / 1024, targetSize / 1024 / 1024);
        return deleted > 0;
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
