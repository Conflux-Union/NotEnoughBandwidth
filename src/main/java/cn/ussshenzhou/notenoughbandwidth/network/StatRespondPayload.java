package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

public record StatRespondPayload(
        long inboundBytesBaked,
        long inboundBytesRaw,
        long outboundBytesBaked,
        long outboundBytesRaw,
        double inboundSpeedBaked,
        double inboundSpeedRaw,
        double outboundSpeedBaked,
        double outboundSpeedRaw,
        int dictSize,
        int dictSampleCount,
        int dictSampleThreshold,
        long chunkCacheHits,
        long chunkCacheMisses,
        long chunkCacheSavedBytes,
        long nicInboundSpeed,
        long nicOutboundSpeed
) {
    public static final Identifier CHANNEL = new Identifier("neb", "stat_resp");

    public void write(PacketByteBuf buf) {
        buf.writeLong(inboundBytesBaked);
        buf.writeLong(inboundBytesRaw);
        buf.writeLong(outboundBytesBaked);
        buf.writeLong(outboundBytesRaw);
        buf.writeDouble(inboundSpeedBaked);
        buf.writeDouble(inboundSpeedRaw);
        buf.writeDouble(outboundSpeedBaked);
        buf.writeDouble(outboundSpeedRaw);
        buf.writeVarInt(dictSize);
        buf.writeVarInt(dictSampleCount);
        buf.writeVarInt(dictSampleThreshold);
        buf.writeLong(chunkCacheHits);
        buf.writeLong(chunkCacheMisses);
        buf.writeLong(chunkCacheSavedBytes);
        buf.writeLong(nicInboundSpeed);
        buf.writeLong(nicOutboundSpeed);
    }

    public static StatRespondPayload read(PacketByteBuf buf) {
        return new StatRespondPayload(
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong()
        );
    }
}
