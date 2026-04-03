package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.util.DefaultChannelPipelineHelper;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.channel.DefaultChannelPipeline;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class AggregationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Aggregation");
    private static final WeakHashMap<ClientConnection, ArrayList<AggregatedEncodePacket>> PACKET_BUFFER = new WeakHashMap<>();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("NEB-Flush-thread").setDaemon(true).build());
    private static final ArrayList<ScheduledFuture<?>> TASKS = new ArrayList<>();
    private static volatile boolean initialized = false;

    public synchronized static void init() {
        if (initialized) {
            return;
        }
        PACKET_BUFFER.clear();
        TASKS.forEach(task -> task.cancel(false));
        TASKS.clear();
        TASKS.add(TIMER.scheduleAtFixedRate(AggregationManager::flush, 0,
                AggregationFlushHelper.getFlushPeriodInMilliseconds(), TimeUnit.MILLISECONDS));
        initialized = true;
    }

    public synchronized static void takeOver(Packet<?> packet, ClientConnection connection) {
        var type = PacketUtil.getTrueType(packet);
        PACKET_BUFFER.computeIfAbsent(connection, k -> new ArrayList<>())
                .add(new AggregatedEncodePacket(packet, type));
    }

    private synchronized static void flush() {
        PACKET_BUFFER.entrySet().removeIf(e -> !e.getKey().isOpen());
        PACKET_BUFFER.forEach(AggregationManager::flushInternal);
    }

    public synchronized static void flushConnection(ClientConnection connection) {
        TIMER.execute(() -> {
            PACKET_BUFFER.entrySet().removeIf(e -> !e.getKey().isOpen());
            flushInternal(connection, PACKET_BUFFER.get(connection));
        });
    }

    private synchronized static void flushInternal(ClientConnection connection, @Nullable ArrayList<AggregatedEncodePacket> packets) {
        try {
            if (packets == null || packets.isEmpty()) {
                return;
            }
            var encoder = DefaultChannelPipelineHelper.getPacketEncoder(
                    (DefaultChannelPipeline) connection.channel.pipeline());
            if (encoder == null) {
                LOGGER.error("Failed to get EncoderHandler of connection {} {}.",
                        connection.getSide(), connection.getAddress());
                return;
            }
            var sendPackets = new ArrayList<>(packets);
            var aggregationPayload = new PacketAggregationPacket(
                    sendPackets, encoder.state, connection);
            // encoder.state.side() = outbound direction (CLIENTBOUND on server, SERVERBOUND on client)
            Packet<?> wrapper = encoder.state.side() == NetworkSide.CLIENTBOUND
                    ? new CustomPayloadS2CPacket(aggregationPayload)
                    : new CustomPayloadC2SPacket(aggregationPayload);
            connection.send(wrapper);
            packets.clear();
            connection.flush();
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to flush packets.", e);
        }
    }
}
