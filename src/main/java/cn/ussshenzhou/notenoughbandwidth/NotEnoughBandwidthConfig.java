package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.config.TConfig;
import cn.ussshenzhou.notenoughbandwidth.network.*;
import com.google.gson.annotations.Expose;
import net.minecraft.util.math.MathHelper;

import java.util.HashSet;
import java.util.UUID;

public class NotEnoughBandwidthConfig implements TConfig {

    public String serverUUID = "";
    public boolean compatibleMode = false;
    public HashSet<String> blackList = new HashSet<>() {{
        add("minecraft:command_suggestion");
        add("minecraft:command_suggestions");
        add("minecraft:commands");
        add("minecraft:player_info_update");
        add("minecraft:player_info_remove");
    }};
    public boolean debugLog = false;
    public int compressionLevel = 6;
    public int contextLevel = 23;
    public int dccSizeLimit = 60;
    public int dccDistance = 5;
    public int dccTimeout = 60;
    public boolean chunkCacheEnabled = true;
    public int chunkCacheMaxSizeMB = 2048;
    public boolean lightStripEnabled = true;

    @Expose(serialize = false, deserialize = false)
    public static final HashSet<String> COMMON_BLOCK_LIST = new HashSet<>() {{
        add("minecraft:finish_configuration");
        add(PacketAggregationPacket.TYPE.id().toString());
        add(DictionarySyncPayload.TYPE.id().toString());
        add(IndexSyncPayload.TYPE.id().toString());
        add(NebAckPayload.TYPE.id().toString());
        add(ChunkCacheManifestPayload.TYPE.id().toString());
        add(ChunkHashPayload.TYPE.id().toString());
        add(ChunkRequestPayload.TYPE.id().toString());
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
        return MathHelper.clamp(compressionLevel, 1, 19);
    }

    public int getContextLevel() {
        return MathHelper.clamp(contextLevel, 21, 25);
    }
}
