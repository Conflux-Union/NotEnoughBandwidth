package cn.ussshenzhou.notenoughbandwidth.chunkcache;

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
import java.util.List;
import java.util.stream.Stream;

/**
 * LevelDB-backed persistent store for chunk data, keyed by 64-bit content hash.
 * One database per server address, stored under {gameDir}/neb_cache/{serverHash}/.
 */
public class ChunkCacheDatabase implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-ChunkCacheDB");

    // Bump this when the on-disk format changes to invalidate old caches automatically.
    private static final int FORMAT_VERSION = 3;
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
        migrateIfNeeded();
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
        byte[] compressed = Zstd.compress(data, 6);
        db.put(longToBytes(hash), compressed);
    }

    public void delete(long hash) {
        db.delete(longToBytes(hash));
    }

    @Nullable
    public byte[] get(long hash) {
        byte[] compressed = db.get(longToBytes(hash));
        if (compressed == null) return null;
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
     * Deletes entries (in key-iteration order) until the DB is at 70% of max capacity.
     * Estimates average entry size by sampling the first few values — avoids dividing the
     * raw filesystem size (which includes LevelDB metadata) by entry count, which would
     * systematically over-estimate and delete too many entries.
     *
     * @return true if any entries were deleted
     */
    public boolean evictIfNeeded() {
        if (maxSizeBytes <= 0) return false;
        long currentSize = approximateSize();
        if (currentSize <= maxSizeBytes) return false;

        long targetSize = (long) (maxSizeBytes * 0.7);
        long bytesToFree = currentSize - targetSize;

        // Sample the first few values to get a realistic average entry size.
        // Chunk values are typically 10-20 KB each; sampling 10 is cheap and accurate.
        long sampledBytes = 0;
        int sampledCount = 0;
        try (DBIterator it = db.iterator()) {
            for (it.seekToFirst(); it.hasNext() && sampledCount < 10; it.next(), sampledCount++) {
                byte[] v = it.peekNext().getValue();
                sampledBytes += (v != null ? v.length : 0) + 8; // 8 bytes per key
            }
        } catch (IOException e) {
            LOGGER.error("Failed to sample entries for eviction", e);
            return false;
        }
        if (sampledCount == 0) return false;

        long avgEntrySize = sampledBytes / sampledCount;
        long entriesToDelete = avgEntrySize > 0 ? (bytesToFree / avgEntrySize) + 1 : sampledCount;

        // Collect keys first — do not delete while iterating.
        // Skip VERSION_KEY (non-8-byte keys) to avoid wiping the format version marker.
        List<byte[]> keysToDelete = new ArrayList<>((int) entriesToDelete);
        try (DBIterator it = db.iterator()) {
            for (it.seekToFirst(); it.hasNext() && keysToDelete.size() < entriesToDelete; it.next()) {
                byte[] key = it.peekNext().getKey();
                if (key.length == 8) keysToDelete.add(key);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to collect keys for eviction", e);
            return false;
        }

        for (byte[] key : keysToDelete) {
            db.delete(key);
        }

        try { db.compactRange(null, null); } catch (Exception ignored) {}
        LOGGER.info("Evicted {} entries from chunk cache (was {} MB, target {} MB)",
                keysToDelete.size(), currentSize / 1024 / 1024, targetSize / 1024 / 1024);
        return !keysToDelete.isEmpty();
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
