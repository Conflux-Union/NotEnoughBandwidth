package cn.ussshenzhou.notenoughbandwidth.chunkcache;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.*;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Deterministic content hash for chunk data packets.
 * <p>
 * Avoids NbtCompound's HashMap iteration order (non-deterministic across JVM
 * instances) by sorting compound keys before hashing. Block entities are sorted
 * by position so the hash is independent of map iteration order.
 * <p>
 * LightData is excluded — it is recomputed by the lighting engine after chunk
 * reload and is not stable across reconnects.
 */
public final class ChunkHashUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-ChunkHash");

    private ChunkHashUtil() {}

    public record Result(long hash, int dataBytes) {}

    @SuppressWarnings("deprecation")
    public static Result compute(ChunkData chunkData, DynamicRegistryManager registryManager) {
        return compute(chunkData, registryManager, "?", 0, 0);
    }

    @SuppressWarnings("deprecation")
    public static Result compute(ChunkData chunkData, DynamicRegistryManager registryManager,
                                 String side, int chunkX, int chunkZ) {
        var hasher = Hashing.murmur3_128().newHasher();

        // 1. Sections data — raw byte[], always roundtrip-stable.
        var sBuf = chunkData.getSectionsDataBuf();
        int sectionsBytes = sBuf.readableBytes();
        byte[] sections = new byte[sectionsBytes];
        sBuf.getBytes(sBuf.readerIndex(), sections);
        sBuf.release();
        hasher.putBytes(sections);

        // 2. Heightmap — NbtCompound with HashMap. Sort keys to be deterministic.
        hashNbtElement(hasher, chunkData.getHeightmap());

        // 3. Block entities — collected via visitor, sorted by position.
        record BE(BlockPos pos, BlockEntityType<?> type, NbtCompound nbt) {}
        var entities = new ArrayList<BE>();
        chunkData.getBlockEntities(chunkX, chunkZ).accept((pos, type, nbt) ->
                entities.add(new BE(pos.toImmutable(), type, nbt)));
        entities.sort(Comparator.<BE>comparingInt(e -> e.pos.getX())
                .thenComparingInt(e -> e.pos.getY())
                .thenComparingInt(e -> e.pos.getZ()));

        hasher.putInt(entities.size());
        for (var e : entities) {
            hasher.putInt(e.pos.getX());
            hasher.putInt(e.pos.getY());
            hasher.putInt(e.pos.getZ());
            Identifier typeId = Registries.BLOCK_ENTITY_TYPE.getId(e.type);
            if (typeId != null) {
                hasher.putString(typeId.toString(), StandardCharsets.UTF_8);
            }
            if (e.nbt != null) {
                hashNbtElement(hasher, e.nbt);
            }
        }

        long hash = hasher.hash().asLong();
        int totalBytes = sectionsBytes + 8;

        if (ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class).debugLog) {
            LOGGER.info("[{}] ChunkHash: hash={} sections={}B entities={}", side,
                    Long.toHexString(hash), sectionsBytes, entities.size());
        }

        return new Result(hash, totalBytes);
    }

    /**
     * Feeds an NbtElement into the hasher with deterministic key ordering.
     * NbtCompound keys are sorted alphabetically before hashing so the result
     * does not depend on HashMap iteration order.
     */
    private static void hashNbtElement(Hasher hasher, NbtElement element) {
        if (element == null) {
            hasher.putByte((byte) 0);
            return;
        }
        hasher.putByte(element.getType());
        switch (element) {
            case NbtCompound c -> {
                var keys = new ArrayList<>(c.getKeys());
                Collections.sort(keys);
                hasher.putInt(keys.size());
                for (var key : keys) {
                    hasher.putInt(key.length());
                    hasher.putString(key, StandardCharsets.UTF_8);
                    hashNbtElement(hasher, c.get(key));
                }
            }
            case NbtList l -> {
                hasher.putInt(l.size());
                for (var e : l) hashNbtElement(hasher, e);
            }
            case NbtByte b -> hasher.putByte(b.byteValue());
            case NbtShort s -> hasher.putShort(s.shortValue());
            case NbtInt i -> hasher.putInt(i.intValue());
            case NbtLong l -> hasher.putLong(l.longValue());
            case NbtFloat f -> hasher.putFloat(f.floatValue());
            case NbtDouble d -> hasher.putDouble(d.doubleValue());
            case NbtString s -> {
                hasher.putInt(s.asString().length());
                hasher.putString(s.asString(), StandardCharsets.UTF_8);
            }
            case NbtByteArray a -> {
                hasher.putInt(a.size());
                for (byte b : a.getByteArray()) hasher.putByte(b);
            }
            case NbtIntArray a -> {
                hasher.putInt(a.size());
                for (int i : a.getIntArray()) hasher.putInt(i);
            }
            case NbtLongArray a -> {
                hasher.putInt(a.size());
                for (long l : a.getLongArray()) hasher.putLong(l);
            }
            default -> {}
        }
    }
}
