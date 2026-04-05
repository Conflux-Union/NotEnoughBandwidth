package cn.ussshenzhou.notenoughbandwidth.indextype;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds and maintains the bidirectional mapping between Identifiers and
 * compact integer indices used for packet header compression.
 * <p>
 * On Fabric, initialization is driven by a configuration-phase handshake
 * that synchronizes the index table between client and server.
 * <p>
 * For 1.20.1, vanilla packet classes are enumerated from NetworkState.PLAY
 * at runtime and assigned deterministic Identifiers based on class names.
 */
public class NamespaceIndexManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Index");
    private static volatile boolean initialized = false;
    private static final ArrayList<String> NAMESPACES = new ArrayList<>();
    private static final ArrayList<ArrayList<String>> PATHS = new ArrayList<>();
    private static final Object2IntMap<String> NAMESPACE_MAP = new Object2IntOpenHashMap<>();
    private static final Int2ObjectArrayMap<Object2IntMap<String>> PATH_MAPS = new Int2ObjectArrayMap<>();

    // Vanilla packet class <-> NEB Identifier mapping
    private static final Map<Class<?>, Identifier> VANILLA_CLASS_TO_IDENTIFIER = new HashMap<>();
    private static final Map<Identifier, Integer> VANILLA_ID_S2C = new HashMap<>();
    private static final Map<Identifier, Integer> VANILLA_ID_C2S = new HashMap<>();

    static {
        NAMESPACE_MAP.defaultReturnValue(-1);
    }

    public synchronized static void init(List<Identifier> types) {
        initialized = false;
        NAMESPACES.clear();
        PATHS.clear();
        NAMESPACE_MAP.clear();
        PATH_MAPS.clear();
        VANILLA_CLASS_TO_IDENTIFIER.clear();
        VANILLA_ID_S2C.clear();
        VANILLA_ID_C2S.clear();

        AtomicInteger namespaceIndex = new AtomicInteger(1);
        NAMESPACES.add("ILLEGAL");
        PATHS.add(new ArrayList<>());

        initVanillaPackets(namespaceIndex);
        indexCustomPayloads(types, namespaceIndex);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("NamespaceIndexManager initialized with {} namespaces.", NAMESPACES.size());
            NAMESPACE_MAP.forEach((namespace, id) -> {
                LOGGER.debug("  namespace: {} id: {}", namespace, id);
                PATH_MAPS.get(id).forEach((path, pid) -> LOGGER.debug("    path: {} id: {}", path, pid));
            });
        }
        if (NAMESPACES.size() > 4096 || PATHS.stream().anyMatch(l -> l.size() > 4096)) {
            throw new RuntimeException("Too many namespaces/paths (max 4096 each). NEB cannot handle this many mods.");
        }
        initialized = true;
    }

    private static void initVanillaPackets(AtomicInteger namespaceIndex) {
        initVanillaForSide(NetworkSide.CLIENTBOUND, namespaceIndex);
        initVanillaForSide(NetworkSide.SERVERBOUND, namespaceIndex);
    }

    @SuppressWarnings("unchecked")
    private static void initVanillaForSide(NetworkSide side, AtomicInteger namespaceIndex) {
        Int2ObjectMap<Class<? extends Packet<?>>> map =
                (Int2ObjectMap<Class<? extends Packet<?>>>) (Int2ObjectMap<?>) NetworkState.PLAY.getPacketIdToPacketMap(side);

        // Sort by int ID for deterministic ordering
        var entries = new ArrayList<>(map.int2ObjectEntrySet());
        entries.sort(Comparator.comparingInt(Int2ObjectMap.Entry::getIntKey));

        for (var entry : entries) {
            Class<?> clazz = entry.getValue();
            // Skip if already mapped from the other side
            if (VANILLA_CLASS_TO_IDENTIFIER.containsKey(clazz)) {
                Identifier existingId = VANILLA_CLASS_TO_IDENTIFIER.get(clazz);
                if (side == NetworkSide.CLIENTBOUND) {
                    VANILLA_ID_S2C.put(existingId, entry.getIntKey());
                } else {
                    VANILLA_ID_C2S.put(existingId, entry.getIntKey());
                }
                continue;
            }

            String path = toSnakeCase(clazz.getSimpleName());
            Identifier id = new Identifier("minecraft", path);
            fillSingle(namespaceIndex, id);
            VANILLA_CLASS_TO_IDENTIFIER.put(clazz, id);
            if (side == NetworkSide.CLIENTBOUND) {
                VANILLA_ID_S2C.put(id, entry.getIntKey());
            } else {
                VANILLA_ID_C2S.put(id, entry.getIntKey());
            }
        }
    }

    /**
     * Convert a CamelCase class name to snake_case path.
     * E.g. "ChunkDataS2CPacket" -> "chunk_data_s2c_packet"
     */
    private static String toSnakeCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    // Don't insert underscore between consecutive uppercase (e.g. S2C)
                    char prev = name.charAt(i - 1);
                    if (!Character.isUpperCase(prev) && prev != '_') {
                        sb.append('_');
                    } else if (Character.isUpperCase(prev) && i + 1 < name.length()
                            && Character.isLowerCase(name.charAt(i + 1))) {
                        sb.append('_');
                    }
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void indexCustomPayloads(List<Identifier> types, AtomicInteger namespaceIndex) {
        types.stream()
                .sorted(Comparator.comparing(Identifier::getNamespace).thenComparing(Identifier::getPath))
                .forEach(type -> fillSingle(namespaceIndex, type));
    }

    private static void fillSingle(AtomicInteger namespaceIndex, Identifier packetId) {
        if (!NAMESPACE_MAP.containsKey(packetId.getNamespace())) {
            NAMESPACE_MAP.put(packetId.getNamespace(), namespaceIndex.get());
            NAMESPACES.add(packetId.getNamespace());
            PATHS.add(new ArrayList<>());
            namespaceIndex.getAndIncrement();
        }
        int nsIdx = NAMESPACE_MAP.getInt(packetId.getNamespace());
        PATH_MAPS.compute(nsIdx, (k, pathMap) -> {
            if (pathMap == null) {
                pathMap = new Object2IntOpenHashMap<>();
            }
            pathMap.put(packetId.getPath(), pathMap.size());
            return pathMap;
        });
        PATHS.get(nsIdx).add(packetId.getPath());
    }

    public static boolean contains(Identifier type) {
        if (!initialized) return false;
        int nsId = NAMESPACE_MAP.getInt(type.getNamespace());
        if (nsId == -1) return false;
        var pathMap = PATH_MAPS.get(nsId);
        return pathMap != null && pathMap.containsKey(type.getPath());
    }

    public static Pair<Integer, Integer> getCheckedIndex(Identifier type) {
        int namespaceId = NAMESPACE_MAP.getInt(type.getNamespace());
        return new Pair<>(namespaceId, PATH_MAPS.get(namespaceId).getInt(type.getPath()));
    }

    public static Identifier getIdentifier(int namespaceIndex, int pathIndex) {
        if (!initialized) return null;
        if (namespaceIndex == 0) {
            throw new UnsupportedOperationException("namespaceIndex should not be 0");
        }
        return new Identifier(NAMESPACES.get(namespaceIndex), PATHS.get(namespaceIndex).get(pathIndex));
    }

    /**
     * Get the NEB Identifier assigned to a vanilla packet class.
     * Returns null if the class is not a registered vanilla packet.
     */
    @Nullable
    public static Identifier getVanillaIdentifier(Class<?> packetClass) {
        return VANILLA_CLASS_TO_IDENTIFIER.get(packetClass);
    }

    /**
     * Get the vanilla int packet ID for a given NEB Identifier on the specified side.
     * Returns null if the identifier is not a vanilla packet on that side.
     */
    @Nullable
    public static Integer getVanillaPacketId(Identifier type, NetworkSide side) {
        if (side == NetworkSide.CLIENTBOUND) {
            return VANILLA_ID_S2C.get(type);
        } else {
            return VANILLA_ID_C2S.get(type);
        }
    }

    public static boolean ready() {
        return initialized;
    }
}
