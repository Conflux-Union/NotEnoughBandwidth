package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager.LOCAL;

public class ModNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Network");

    public static void registerCommon() {
        // Register payload types
        PayloadTypeRegistry.playS2C().register(PacketAggregationPacket.TYPE, PacketAggregationPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(PacketAggregationPacket.TYPE, PacketAggregationPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(StatQueryPayload.TYPE, StatQueryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StatRespondPayload.TYPE, StatRespondPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(NebAckPayload.TYPE, NebAckPayload.CODEC);

        // Server-side handlers
        ServerPlayNetworking.registerGlobalReceiver(PacketAggregationPacket.TYPE, (payload, context) -> {
            payload.handle(context.player().networkHandler.connection);
        });

        // Client confirmed NEB presence: enable compression path for this connection
        ServerPlayNetworking.registerGlobalReceiver(NebAckPayload.TYPE, (payload, context) -> {
            var connection = context.player().networkHandler.connection;
            NebConnectionRegistry.markEnabled(connection);
            AggregationManager.init();
            LOGGER.info("NEB ack received from {}, compression path enabled",
                    context.player().getName().getString());
        });

        ServerPlayNetworking.registerGlobalReceiver(StatQueryPayload.TYPE, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player.hasPermissionLevel(2)) {
                ServerPlayNetworking.send(player, new StatRespondPayload(
                        LOCAL.inboundBytesBaked().get(),
                        LOCAL.inboundBytesRaw().get(),
                        LOCAL.outboundBytesBaked().get(),
                        LOCAL.outboundBytesRaw().get(),
                        LOCAL.inboundSpeedBaked().averageIn1s(),
                        LOCAL.inboundSpeedRaw().averageIn1s(),
                        LOCAL.outboundSpeedBaked().averageIn1s(),
                        LOCAL.outboundSpeedRaw().averageIn1s()
                ));
            }
        });
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(PacketAggregationPacket.TYPE, (payload, context) -> {
            payload.handle(context.player().networkHandler.connection);
        });

        ClientPlayNetworking.registerGlobalReceiver(StatRespondPayload.TYPE, (payload, context) -> {
            SimpleStatManager.inboundBytesBakedServer = payload.inboundBytesBaked();
            SimpleStatManager.inboundBytesRawServer = payload.inboundBytesRaw();
            SimpleStatManager.outboundBytesBakedServer = payload.outboundBytesBaked();
            SimpleStatManager.outboundBytesRawServer = payload.outboundBytesRaw();
            SimpleStatManager.inboundSpeedBakedServer = payload.inboundSpeedBaked();
            SimpleStatManager.inboundSpeedRawServer = payload.inboundSpeedRaw();
            SimpleStatManager.outboundSpeedBakedServer = payload.outboundSpeedBaked();
            SimpleStatManager.outboundSpeedRawServer = payload.outboundSpeedRaw();
        });
    }
}
