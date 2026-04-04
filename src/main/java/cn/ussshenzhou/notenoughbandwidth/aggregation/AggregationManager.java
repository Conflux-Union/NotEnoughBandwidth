package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.util.DefaultChannelPipelineHelper;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.channel.DefaultChannelPipeline;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
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
        // Purge dead connections without holding a global lock.
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

    public static void discardConnection(ClientConnection connection) {
        var packets = PACKET_BUFFER.remove(connection);
        if (packets != null) {
            packets.clear();
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

    private static void flushInternal(ClientConnection connection, @Nullable ArrayList<AggregatedEncodePacket> packets) {
        try {
            if (packets == null || packets.isEmpty()) {
                return;
            }
            var listener = connection.getPacketListener();
            if (!connection.isOpen() || listener == null
                    || listener.getPhase() != NetworkPhase.PLAY
                    || !NebConnectionRegistry.isEnabled(connection)) {
                packets.clear();
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
