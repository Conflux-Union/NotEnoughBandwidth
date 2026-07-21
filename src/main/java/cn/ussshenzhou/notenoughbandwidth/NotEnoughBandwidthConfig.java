package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.config.TConfig;
import cn.ussshenzhou.notenoughbandwidth.network.*;
import com.google.gson.annotations.Expose;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.UUID;
import java.util.regex.Pattern;

public class NotEnoughBandwidthConfig implements TConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Config");

    public String serverUUID = "";
    public boolean compatibleMode = false;
    public HashSet<String> blackList = new HashSet<>() {{
        add("minecraft:command_suggestion");
        add("minecraft:command_suggestions");
        add("minecraft:commands");
        add("minecraft:player_info_update");
        add("minecraft:player_info_remove");
    }};
    // Players listed here get a per-connection Zstd Context that never reuses its
    // streaming state (and never receives a dictionary), so every aggregate frame is a
    // self-contained blob a replay/recording mod can decode on its own. Server-only.
    // The zero UUID is a placeholder documenting the expected format; it matches no player.
    public HashSet<String> playersDoNotUseContext = new HashSet<>() {{
        add("00000000-0000-0000-0000-000000000000");
    }};
    public boolean debugLog = false;
    public int compressionLevel = 6;
    public int contextLevel = 23;
    public int dccSizeLimit = 60;
    public int dccDistance = 5;
    public int dccTimeout = 60;
    public boolean chunkCacheEnabled = true;
    public int chunkCacheMaxSizeMB = 2048;
    public boolean lightStripEnabled = false;
    public boolean checkUpdate = true;
    public String maxPacketSize = "4MB";

    @Expose(serialize = false, deserialize = false)
    public static final HashSet<String> COMMON_BLOCK_LIST = new HashSet<>() {{
        add("minecraft:finish_configuration");
        // A mid-game reconfiguration's terminal packet must reach the client on its
        // own frame — aggregating it would prevent the vanilla protocol-swap
        // handshake from ever firing.
        add("minecraft:start_configuration");
        //#if MC>=12005
        add(PacketAggregationPacket.TYPE.id().toString());
        add(DictionarySyncPayload.TYPE.id().toString());
        add(IndexSyncPayload.TYPE.id().toString());
        add(NebAckPayload.TYPE.id().toString());
        add(ChunkCacheManifestPayload.TYPE.id().toString());
        add(ChunkHashPayload.TYPE.id().toString());
        add(ChunkRequestPayload.TYPE.id().toString());
        //#else
        //$$ add(PacketAggregationPacket.CHANNEL.toString());
        //$$ add(DictionarySyncPayload.CHANNEL.toString());
        //$$ add(IndexSyncPayload.CHANNEL.toString());
        //$$ add(NebAckPayload.CHANNEL.toString());
        //$$ add(ChunkCacheManifestPayload.CHANNEL.toString());
        //$$ add(ChunkHashPayload.CHANNEL.toString());
        //$$ add(ChunkRequestPayload.CHANNEL.toString());
        //#endif
        add("minecraft:login");
        add("minecraft:chat_command");
        add("minecraft:chat_command_signed");
        add("minecraft:chat");
    }};

    public static NotEnoughBandwidthConfig get() {
        return ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class);
    }

    public static boolean skipType(String type) {
        var cfg = get();
        return COMMON_BLOCK_LIST.contains(type) || (cfg.compatibleMode && cfg.blackList.contains(type));
    }

    public int getCompressionLevel() {
        return Mth.clamp(compressionLevel, 1, 19);
    }

    public int getContextLevel() {
        return Mth.clamp(contextLevel, 21, 25);
    }

    // ConfigHelper's Gson is built without excludeFieldsWithoutExposeAnnotation(),
    // so @Expose is not actually honored here — transient is what keeps this
    // derived cache out of the saved JSON (and out of a loaded one).
    private transient int maxPacketSizeByte = -1;

    public int getMaxPacketSize() {
        if (maxPacketSizeByte == -1) {
            maxPacketSizeByte = parseByteSize(maxPacketSize);
            int min = parseByteSize("2MB");
            int max = parseByteSize("64MB");
            if (maxPacketSizeByte < min || maxPacketSizeByte > max) {
                LOGGER.error("maxPacketSize should be between 2MB and 64MB");
            }
            maxPacketSizeByte = Mth.clamp(maxPacketSizeByte, min, max);
        }
        return maxPacketSizeByte;
    }

    private static int parseByteSize(String s) {
        var matcher = Pattern.compile("^([\\d.]+)\\s*(B|KB|MB)?$", Pattern.CASE_INSENSITIVE).matcher(s.trim());
        if (!matcher.matches()) {
            LOGGER.error("NEB: Invalid packet size: {} , use default 4MB instead.", s);
            return parseByteSize("4MB");
        }
        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            // The regex allows multiple dots (e.g. "1.2.3"), which Double.parseDouble
            // rejects; getMaxPacketSize() runs on every frame decode, so a malformed
            // value here must fall back instead of throwing.
            LOGGER.error("NEB: Invalid packet size: {} , use default 4MB instead.", s);
            return parseByteSize("4MB");
        }
        String unit = matcher.group(2);
        if (unit == null || "B".equalsIgnoreCase(unit)) {
            return (int) value;
        }
        return (int) switch (unit.toUpperCase()) {
            case "KB" -> value * 1024;
            case "MB" -> value * 1024 * 1024;
            default -> {
                LOGGER.error("NEB: Invalid packet size: {} , use default 4MB instead.", s);
                yield parseByteSize("4MB");
            }
        };
    }
}
