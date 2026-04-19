package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.util.DefaultChannelPipelineHelper;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.channel.DefaultChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class AggregationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Aggregation");
    private static final int MIN_BATCH_PACKETS = 4;
    private static final int MAX_EXTRA_CYCLES = 2;
    private static final ConcurrentHashMap<Connection, ArrayList<AggregatedEncodePacket>> PACKET_BUFFER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Connection, Integer> FLUSH_WAIT = new ConcurrentHashMap<>();
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

    public static void takeOver(Packet<?> packet, Connection connection) {
        var type = PacketUtil.getTrueType(packet);
        var list = PACKET_BUFFER.computeIfAbsent(connection, k -> new ArrayList<>());
        synchronized (list) {
            list.add(new AggregatedEncodePacket(packet, type));
        }
    }

    private static void flush() {
        // Purge dead connections without holding a global lock.
        PACKET_BUFFER.keySet().removeIf(c -> !c.isConnected());
        FLUSH_WAIT.keySet().removeIf(c -> !c.isConnected());
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

    public static void flushConnection(Connection connection) {
        TIMER.execute(() -> flushConnectionInternal(connection));
    }

    /**
     * Synchronously flush buffered packets for this connection on the calling thread.
     * Used when a skip-type packet must be sent immediately after the buffered batch
     * to preserve packet ordering.
     */
    public static void flushConnectionSync(Connection connection) {
        flushConnectionInternal(connection);
    }

    public static void discardConnection(Connection connection) {
        var packets = PACKET_BUFFER.remove(connection);
        if (packets != null) {
            synchronized (packets) {
                packets.clear();
            }
        }
        FLUSH_WAIT.remove(connection);
    }

    private static void flushConnectionInternal(Connection connection) {
        PACKET_BUFFER.keySet().removeIf(c -> !c.isConnected());
        FLUSH_WAIT.remove(connection);
        var packets = PACKET_BUFFER.get(connection);
        if (packets == null) return;
        synchronized (packets) {
            flushInternal(connection, packets);
        }
    }

    private static void flushInternal(Connection connection, @Nullable ArrayList<AggregatedEncodePacket> packets) {
        try {
            if (packets == null || packets.isEmpty()) {
                return;
            }
            var listener = connection.getPacketListener();
            if (!connection.isConnected() || listener == null
                    || listener.protocol() != ConnectionProtocol.PLAY
                    || !NebConnectionRegistry.isEnabled(connection)) {
                packets.clear();
                return;
            }
            var encoder = DefaultChannelPipelineHelper.getPacketEncoder(
                    (DefaultChannelPipeline) connection.channel.pipeline());
            if (encoder == null) {
                LOGGER.error("Failed to get PacketEncoder of connection {} {}.",
                        connection.getReceiving(), connection.getRemoteAddress());
                return;
            }
            var sendPackets = new ArrayList<>(packets);
            packets.clear();
            var aggregationPayload = new PacketAggregationPacket(
                    sendPackets, encoder.protocolInfo, connection);
            // encoder.protocolInfo.flow() = outbound direction (CLIENTBOUND on server, SERVERBOUND on client)
            Packet<?> wrapper = encoder.protocolInfo.flow() == PacketFlow.CLIENTBOUND
                    ? new ClientboundCustomPayloadPacket(aggregationPayload)
                    : new ServerboundCustomPayloadPacket(aggregationPayload);
            connection.send(wrapper);
            connection.flushChannel();
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to flush packets.", e);
        }
    }
}
