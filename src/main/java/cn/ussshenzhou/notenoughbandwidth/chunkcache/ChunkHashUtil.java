package cn.ussshenzhou.notenoughbandwidth.chunkcache;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Deterministic content hash for chunk data packets.
 * <p>
 * Avoids CompoundTag's HashMap iteration order (non-deterministic across JVM
 * instances) by sorting compound keys before hashing. Block entities are sorted
 * by position so the hash is independent of map iteration order.
 * <p>
 * ClientboundLightUpdatePacketData is excluded — it is recomputed by the lighting engine after chunk
 * reload and is not stable across reconnects.
 */
public final class ChunkHashUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-ChunkHash");

    private ChunkHashUtil() {}

    public record Result(long hash, int dataBytes) {}

    public static Result compute(ClientboundLevelChunkPacketData chunkData, RegistryAccess registryManager) {
        return compute(chunkData, registryManager, "?", 0, 0);
    }

    public static Result compute(ClientboundLevelChunkPacketData chunkData, RegistryAccess registryManager,
                                 String side, int chunkX, int chunkZ) {
        var hasher = Hashing.murmur3_128().newHasher();

        // 1. Sections data — raw byte[], always roundtrip-stable.
        // 26.1: getSectionsDataBuf() is gone; getReadBuffer() returns a FriendlyByteBuf wrapping
        // the `buffer` byte[] which is exactly the sections payload we want.
        var sBuf = chunkData.getReadBuffer();
        int sectionsBytes = sBuf.readableBytes();
        byte[] sections = new byte[sectionsBytes];
        sBuf.getBytes(sBuf.readerIndex(), sections);
        sBuf.release();
        hasher.putBytes(sections);

        // 2. Heightmaps — Map<Heightmap.Types, long[]> since 1.21.5 (NBT compound before).
        // Sort by type name for determinism.
        //#if MC>=12106
        var heightmaps = chunkData.getHeightmaps();
        var sortedTypes = new ArrayList<Heightmap.Types>(heightmaps.keySet());
        sortedTypes.sort(Comparator.comparing(Heightmap.Types::name));
        hasher.putInt(sortedTypes.size());
        for (var type : sortedTypes) {
            hasher.putString(type.name(), StandardCharsets.UTF_8);
            long[] data = heightmaps.get(type);
            hasher.putInt(data.length);
            for (long l : data) hasher.putLong(l);
        }
        //#else
        //$$ hashNbtElement(hasher, chunkData.getHeightmaps());
        //#endif

        // 3. Block entities — collected via visitor, sorted by position.
        // 26.1: getBlockEntities(chunkX, chunkZ) is gone. Replacement is
        //   Consumer<BlockEntityTagOutput> getBlockEntitiesTagsConsumer(int, int)
        // i.e. you get a consumer that accepts YOUR BlockEntityTagOutput lambda.
        // BlockEntityTagOutput is a @FunctionalInterface with accept(BlockPos, BlockEntityType<?>, CompoundTag).
        record BE(BlockPos pos, BlockEntityType<?> type, CompoundTag nbt) {}
        var entities = new ArrayList<BE>();
        chunkData.getBlockEntitiesTagsConsumer(chunkX, chunkZ).accept((pos, type, nbt) ->
                entities.add(new BE(pos.immutable(), type, nbt)));
        entities.sort(Comparator.<BE>comparingInt(e -> e.pos.getX())
                .thenComparingInt(e -> e.pos.getY())
                .thenComparingInt(e -> e.pos.getZ()));

        hasher.putInt(entities.size());
        for (var e : entities) {
            hasher.putInt(e.pos.getX());
            hasher.putInt(e.pos.getY());
            hasher.putInt(e.pos.getZ());
            Identifier typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(e.type);
            if (typeId != null) {
                hasher.putString(typeId.toString(), StandardCharsets.UTF_8);
            }
            if (e.nbt != null) {
                hashNbtElement(hasher, e.nbt);
            }
        }

        long hash = hasher.hash().asLong();
        // Sections dominate packet size; heightmap/BE overhead is minor.
        int totalBytes = sectionsBytes;

        if (ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class).debugLog) {
            LOGGER.info("[{}] ChunkHash: hash={} sections={}B entities={}", side,
                    Long.toHexString(hash), sectionsBytes, entities.size());
        }

        return new Result(hash, totalBytes);
    }

    /**
     * Feeds a Tag into the hasher with deterministic key ordering.
     * CompoundTag keys are sorted alphabetically before hashing so the result
     * does not depend on HashMap iteration order.
     */
    private static void hashNbtElement(Hasher hasher, Tag element) {
        if (element == null) {
            hasher.putByte((byte) 0);
            return;
        }
        // 26.1: getType() returns TagType<?>, not byte. Use getId() for the tag-type byte.
        hasher.putByte(element.getId());
        switch (element) {
            case CompoundTag c -> {
                var keys = new ArrayList<>(c.keySet());
                Collections.sort(keys);
                hasher.putInt(keys.size());
                for (var key : keys) {
                    hasher.putInt(key.length());
                    hasher.putString(key, StandardCharsets.UTF_8);
                    hashNbtElement(hasher, c.get(key));
                }
            }
            case ListTag l -> {
                hasher.putInt(l.size());
                for (var e : l) hashNbtElement(hasher, e);
            }
            case ByteTag b -> hasher.putByte(b.byteValue());
            case ShortTag s -> hasher.putShort(s.shortValue());
            case IntTag i -> hasher.putInt(i.intValue());
            case LongTag l -> hasher.putLong(l.longValue());
            case FloatTag f -> hasher.putFloat(f.floatValue());
            case DoubleTag d -> hasher.putDouble(d.doubleValue());
            case StringTag s -> {
                //#if MC>=12106
                String val = s.value();
                //#else
                //$$ String val = s.getAsString();
                //#endif
                hasher.putInt(val.length());
                hasher.putString(val, StandardCharsets.UTF_8);
            }
            case ByteArrayTag a -> {
                byte[] raw = a.getAsByteArray();
                hasher.putInt(raw.length);
                for (byte b : raw) hasher.putByte(b);
            }
            case IntArrayTag a -> {
                int[] raw = a.getAsIntArray();
                hasher.putInt(raw.length);
                for (int i : raw) hasher.putInt(i);
            }
            case LongArrayTag a -> {
                long[] raw = a.getAsLongArray();
                hasher.putInt(raw.length);
                for (long l : raw) hasher.putLong(l);
            }
            default -> LOGGER.warn("Unknown NBT type id={} in chunk hash, hash may be unstable", element.getId());
        }
    }
}
