package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.util.DefaultChannelPipelineHelper;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.DefaultChannelPipeline;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class AggregationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Aggregation");
    private static final int MIN_BATCH_PACKETS = 4;
    private static final int MAX_EXTRA_CYCLES = 2;
    private static final ConcurrentHashMap<ClientConnection, ArrayList<AggregatedEncodePacket>> PACKET_BUFFER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ClientConnection, Integer> FLUSH_WAIT = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("NEB-Flush-thread").setDaemon(true).build());
    private static final ArrayList<ScheduledFuture<?>> TASKS = new ArrayList<>();
    private static volatile boolean initialized = false;

    public static synchronized void init() {
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

    public static void takeOver(Packet<?> packet, ClientConnection connection) {
        var type = PacketUtil.getTrueType(packet);
        var list = PACKET_BUFFER.computeIfAbsent(connection, k -> new ArrayList<>());
        synchronized (list) {
            list.add(new AggregatedEncodePacket(packet, type));
        }
    }

    private static void flush() {
        PACKET_BUFFER.keySet().removeIf(c -> !c.isOpen());
        FLUSH_WAIT.keySet().removeIf(c -> !c.isOpen());
        for (var entry : PACKET_BUFFER.entrySet()) {
            var connection = entry.getKey();
            var packets = entry.getValue();
            if (packets == null) {
                continue;
            }
            synchronized (packets) {
                if (packets.isEmpty()) {
                    continue;
                }
                if (packets.size() < MIN_BATCH_PACKETS) {
                    int waited = FLUSH_WAIT.getOrDefault(connection, 0);
                    if (waited < MAX_EXTRA_CYCLES) {
                        FLUSH_WAIT.put(connection, waited + 1);
                        continue;
                    }
                }
                FLUSH_WAIT.remove(connection);
                flushInternal(connection, packets);
            }
        }
    }

    public static void flushConnection(ClientConnection connection) {
        TIMER.execute(() -> flushConnectionInternal(connection));
    }

    /**
     * Synchronously flush buffered packets for this connection on the calling thread.
     * Used when a skip-type packet must be sent immediately after the buffered batch
     * to preserve packet ordering.
     */
    public static void flushConnectionSync(ClientConnection connection) {
        flushConnectionInternal(connection);
    }

    public static void discardConnection(ClientConnection connection) {
        var packets = PACKET_BUFFER.remove(connection);
        if (packets != null) {
            synchronized (packets) {
                packets.clear();
            }
        }
        FLUSH_WAIT.remove(connection);
    }

    private static void flushConnectionInternal(ClientConnection connection) {
        PACKET_BUFFER.keySet().removeIf(c -> !c.isOpen());
        FLUSH_WAIT.remove(connection);
        var packets = PACKET_BUFFER.get(connection);
        if (packets == null) return;
        synchronized (packets) {
            flushInternal(connection, packets);
        }
    }

    private static boolean isPlayPhase(ClientConnection connection) {
        var listener = connection.getPacketListener();
        return listener instanceof ServerPlayNetworkHandler
                || listener instanceof ClientPlayNetworkHandler;
    }

    private static void flushInternal(ClientConnection connection, @Nullable ArrayList<AggregatedEncodePacket> packets) {
        try {
            if (packets == null || packets.isEmpty()) {
                return;
            }
            if (!connection.isOpen() || !isPlayPhase(connection)
                    || !NebConnectionRegistry.isEnabled(connection)) {
                packets.clear();
                return;
            }
            var encoder = DefaultChannelPipelineHelper.getPacketEncoder(
                    (DefaultChannelPipeline) connection.channel.pipeline());
            if (encoder == null) {
                LOGGER.error("Failed to get PacketEncoder of connection {} {}.",
                        connection.getSide(), connection.getAddress());
                return;
            }
            // Access-widened PacketEncoder.side gives the outbound direction
            NetworkSide encoderSide = encoder.side;
            var sendPackets = new ArrayList<>(packets);
            packets.clear();
            var aggregationPayload = new PacketAggregationPacket(
                    sendPackets, encoderSide, connection);

            PacketByteBuf buf = new PacketByteBuf(ByteBufAllocator.DEFAULT.buffer());
            aggregationPayload.write(buf);

            Packet<?> wrapper = encoderSide == NetworkSide.CLIENTBOUND
                    ? new CustomPayloadS2CPacket(PacketAggregationPacket.CHANNEL, buf)
                    : new CustomPayloadC2SPacket(PacketAggregationPacket.CHANNEL, buf);
            connection.send(wrapper);
            connection.channel.flush();
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to flush packets.", e);
        }
    }
}
