package cn.ussshenzhou.notenoughbandwidth.indextype;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.Identifier;
//#if MC<12005
//$$ import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
//$$ import net.minecraft.network.ConnectionProtocol;
//$$ import net.minecraft.network.protocol.Packet;
//$$ import net.minecraft.network.protocol.PacketFlow;
//$$ import org.jetbrains.annotations.Nullable;
//#endif
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
 */
public class NamespaceIndexManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Index");
    private static volatile boolean initialized = false;
    private static final ArrayList<String> NAMESPACES = new ArrayList<>();
    private static final ArrayList<ArrayList<String>> PATHS = new ArrayList<>();
    private static final Object2IntMap<String> NAMESPACE_MAP = new Object2IntOpenHashMap<>();
    private static final Int2ObjectArrayMap<Object2IntMap<String>> PATH_MAPS = new Int2ObjectArrayMap<>();

    //#if MC<12005
    //$$ // 1.20.1: vanilla packets have no Identifier-keyed PacketType registry, so
    //$$ // classes are enumerated from ConnectionProtocol.PLAY at runtime and given
    //$$ // deterministic Identifiers derived from their class names.
    //$$ private static final Map<Class<?>, ResourceLocation> VANILLA_CLASS_TO_IDENTIFIER = new HashMap<>();
    //$$ private static final Map<ResourceLocation, Integer> VANILLA_ID_S2C = new HashMap<>();
    //$$ private static final Map<ResourceLocation, Integer> VANILLA_ID_C2S = new HashMap<>();
    //#endif

    static {
        NAMESPACE_MAP.defaultReturnValue(-1);
    }

    /**
     * Vanilla game packet paths for minecraft namespace.
     * Must match the PacketType IDs registered in GamePacketTypes.
     */
    private static final List<String> VANILLA_PATHS = List.of(
            // S2C packets
            "bundle", "bundle_delimiter",
            "add_entity", "animate", "award_stats",
            "block_changed_ack", "block_destruction", "block_entity_data", "block_event", "block_update",
            "boss_event", "change_difficulty",
            "chunk_batch_finished", "chunk_batch_start", "chunks_biomes",
            "clear_titles", "command_suggestions", "commands",
            "container_close", "container_set_content", "container_set_data", "container_set_slot",
            "cooldown", "custom_chat_completions",
            "damage_event", "debug_sample", "delete_chat", "disguised_chat",
            "entity_event", "entity_position_sync", "explode",
            "forget_level_chunk", "game_event",
            "horse_screen_open", "hurt_animation", "initialize_border",
            "level_chunk_with_light", "level_event", "level_particles", "light_update",
            "login", "map_item_data", "merchant_offers",
            "move_entity_pos", "move_entity_pos_rot", "move_minecart_along_track",
            "move_entity_rot", "move_vehicle",
            "open_book", "open_screen", "open_sign_editor",
            "place_ghost_recipe", "player_abilities", "player_chat",
            "player_combat_end", "player_combat_enter", "player_combat_kill",
            "player_info_remove", "player_info_update", "player_look_at",
            "player_position", "player_rotation",
            "projectile_power",
            "recipe_book_add", "recipe_book_remove", "recipe_book_settings",
            "remove_entities", "remove_mob_effect", "respawn", "rotate_head",
            "section_blocks_update", "select_advancements_tab", "server_data",
            "set_action_bar_text", "set_border_center", "set_border_lerp_size",
            "set_border_size", "set_border_warning_delay", "set_border_warning_distance",
            "set_camera", "set_chunk_cache_center", "set_chunk_cache_radius",
            "set_default_spawn_position", "set_display_objective",
            "set_entity_data", "set_entity_link", "set_entity_motion",
            "set_equipment", "set_experience", "set_health", "set_held_slot",
            "set_objective", "set_passengers", "set_player_team", "set_score",
            "set_simulation_distance", "set_subtitle_text", "set_time",
            "set_title_text", "set_titles_animation",
            "sound_entity", "sound", "start_configuration", "stop_sound",
            "system_chat", "tab_list", "tag_query", "take_item_entity", "teleport_entity",
            "test_instance_block_status",
            "update_advancements", "update_attributes", "update_mob_effect", "update_recipes",
            "waypoint",
            "reset_score", "ticking_state", "ticking_step",
            "set_cursor_item", "set_player_inventory",
            // C2S packets
            "accept_teleportation", "block_entity_tag_query",
            "bundle_item_selected", "change_game_mode",
            "chat_ack", "chat_command", "chat_command_signed", "chat", "chat_session_update",
            "chunk_batch_received", "client_command", "client_tick_end",
            "command_suggestion", "configuration_acknowledged",
            "container_button_click", "container_click", "container_slot_state_changed",
            "debug_sample_subscription",
            "edit_book", "entity_tag_query", "interact", "jigsaw_generate",
            "lock_difficulty",
            "move_player_pos", "move_player_pos_rot", "move_player_rot", "move_player_status_only",
            "paddle_boat", "pick_item_from_block", "pick_item_from_entity",
            "place_recipe", "player_action", "player_command", "player_input", "player_loaded",
            "recipe_book_change_settings", "recipe_book_seen_recipe",
            "rename_item", "seen_advancements", "select_trade",
            "set_beacon", "set_carried_item", "set_command_block", "set_command_minecart",
            "set_creative_mode_slot", "set_jigsaw_block", "set_structure_block",
            "set_test_block", "sign_update", "swing", "teleport_to_entity",
            "test_instance_block_action",
            "use_item_on", "use_item"
    );

    public synchronized static void init(List<Identifier> types) {
        initialized = false;
        NAMESPACES.clear();
        PATHS.clear();
        NAMESPACE_MAP.clear();
        PATH_MAPS.clear();
        //#if MC<12005
        //$$ VANILLA_CLASS_TO_IDENTIFIER.clear();
        //$$ VANILLA_ID_S2C.clear();
        //$$ VANILLA_ID_C2S.clear();
        //#endif

        AtomicInteger namespaceIndex = new AtomicInteger(1);
        NAMESPACES.add("ILLEGAL");
        PATHS.add(new ArrayList<>());

        indexVanillaPackets(namespaceIndex);
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

    //#if MC>=12005
    private static void indexVanillaPackets(AtomicInteger namespaceIndex) {
        VANILLA_PATHS.forEach(path -> fillSingle(namespaceIndex, Identifier.withDefaultNamespace(path)));
    }
    //#else
    //$$ private static void indexVanillaPackets(AtomicInteger namespaceIndex) {
    //$$     initVanillaForSide(PacketFlow.CLIENTBOUND, namespaceIndex);
    //$$     initVanillaForSide(PacketFlow.SERVERBOUND, namespaceIndex);
    //$$ }
    //$$
    //$$ @SuppressWarnings("unchecked")
    //$$ private static void initVanillaForSide(PacketFlow side, AtomicInteger namespaceIndex) {
    //$$     Int2ObjectMap<Class<? extends Packet<?>>> map =
    //$$             (Int2ObjectMap<Class<? extends Packet<?>>>) (Int2ObjectMap<?>) ConnectionProtocol.PLAY.getPacketsByIds(side);
    //$$
    //$$     // Sort by int ID for a deterministic table on both ends.
    //$$     var entries = new ArrayList<>(map.int2ObjectEntrySet());
    //$$     entries.sort(Comparator.comparingInt(Int2ObjectMap.Entry::getIntKey));
    //$$
    //$$     for (var entry : entries) {
    //$$         Class<?> clazz = entry.getValue();
    //$$         // Bundle delimiter etc. can appear on both sides — reuse the identifier.
    //$$         if (VANILLA_CLASS_TO_IDENTIFIER.containsKey(clazz)) {
    //$$             ResourceLocation existingId = VANILLA_CLASS_TO_IDENTIFIER.get(clazz);
    //$$             if (side == PacketFlow.CLIENTBOUND) {
    //$$                 VANILLA_ID_S2C.put(existingId, entry.getIntKey());
    //$$             } else {
    //$$                 VANILLA_ID_C2S.put(existingId, entry.getIntKey());
    //$$             }
    //$$             continue;
    //$$         }
    //$$
    //$$         String path = toSnakeCase(clazz.getSimpleName());
    //$$         ResourceLocation id = new ResourceLocation("minecraft", path);
    //$$         fillSingle(namespaceIndex, id);
    //$$         VANILLA_CLASS_TO_IDENTIFIER.put(clazz, id);
    //$$         if (side == PacketFlow.CLIENTBOUND) {
    //$$             VANILLA_ID_S2C.put(id, entry.getIntKey());
    //$$         } else {
    //$$             VANILLA_ID_C2S.put(id, entry.getIntKey());
    //$$         }
    //$$     }
    //$$ }
    //$$
    //$$ /**
    //$$  * Convert a CamelCase class name to a snake_case path.
    //$$  * E.g. "ClientboundLevelChunkWithLightPacket" -> "clientbound_level_chunk_with_light_packet".
    //$$  * Only used locally (never transmitted): both ends enumerate the same classes
    //$$  * in the same order, so the (namespace, path) indices line up regardless of
    //$$  * whether the runtime names are mojmap (dev) or intermediary (production).
    //$$  */
    //$$ private static String toSnakeCase(String name) {
    //$$     StringBuilder sb = new StringBuilder();
    //$$     for (int i = 0; i < name.length(); i++) {
    //$$         char c = name.charAt(i);
    //$$         if (Character.isUpperCase(c)) {
    //$$             if (i > 0) {
    //$$                 // Don't insert underscore between consecutive uppercase (e.g. S2C)
    //$$                 char prev = name.charAt(i - 1);
    //$$                 if (!Character.isUpperCase(prev) && prev != '_') {
    //$$                     sb.append('_');
    //$$                 } else if (Character.isUpperCase(prev) && i + 1 < name.length()
    //$$                         && Character.isLowerCase(name.charAt(i + 1))) {
    //$$                     sb.append('_');
    //$$                 }
    //$$             }
    //$$             sb.append(Character.toLowerCase(c));
    //$$         } else {
    //$$             sb.append(c);
    //$$         }
    //$$     }
    //$$     return sb.toString();
    //$$ }
    //$$
    //$$ /**
    //$$  * Get the NEB Identifier assigned to a vanilla packet class.
    //$$  * Returns null if the class is not a registered vanilla packet.
    //$$  */
    //$$ @Nullable
    //$$ public static ResourceLocation getVanillaIdentifier(Class<?> packetClass) {
    //$$     return VANILLA_CLASS_TO_IDENTIFIER.get(packetClass);
    //$$ }
    //$$
    //$$ /**
    //$$  * Get the vanilla int packet ID for a given NEB Identifier on the specified side.
    //$$  * Returns null if the identifier is not a vanilla packet on that side.
    //$$  */
    //$$ @Nullable
    //$$ public static Integer getVanillaPacketId(ResourceLocation type, PacketFlow side) {
    //$$     if (side == PacketFlow.CLIENTBOUND) {
    //$$         return VANILLA_ID_S2C.get(type);
    //$$     } else {
    //$$         return VANILLA_ID_C2S.get(type);
    //$$     }
    //$$ }
    //#endif

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

    public static Index getCheckedIndex(Identifier type) {
        int namespaceId = NAMESPACE_MAP.getInt(type.getNamespace());
        return new Index(namespaceId, PATH_MAPS.get(namespaceId).getInt(type.getPath()));
    }

    public record Index(int getA, int getB) {}

    public static Identifier getIdentifier(int namespaceIndex, int pathIndex) {
        if (!initialized) return null;
        if (namespaceIndex == 0) {
            throw new UnsupportedOperationException("namespaceIndex should not be 0");
        }
        return Identifier.fromNamespaceAndPath(NAMESPACES.get(namespaceIndex), PATHS.get(namespaceIndex).get(pathIndex));
    }

    public static boolean ready() {
        return initialized;
    }
}
