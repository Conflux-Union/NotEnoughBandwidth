package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.util.DefaultChannelPipelineHelper;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;

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
        if (listener instanceof ServerPlayNetworkHandler) return true;
        // Avoid loading client-only class on dedicated server
        return listener != null
                && listener.getClass().getName().equals("net.minecraft.client.network.ClientPlayNetworkHandler");
    }

    private static final int MAX_C2S_PAYLOAD = 32767;
    private static final int MAX_S2C_PAYLOAD = 1048576;

    private static void flushInternal(ClientConnection connection, @Nullable ArrayList<AggregatedEncodePacket> packets) {
        try {
            if (packets == null || packets.isEmpty()) {
                return;
            }
            if (!connection.isOpen() || !isPlayPhase(connection)) {
                packets.clear();
                return;
            }
            if (NebConnectionRegistry.isPending(connection)) {
                // Handshake in progress — keep packets buffered until NebAck arrives.
                return;
            }
            if (!NebConnectionRegistry.isEnabled(connection)) {
                packets.clear();
                return;
            }
            var encoder = DefaultChannelPipelineHelper.getPacketEncoder(
                    connection.channel.pipeline());
            if (encoder == null) {
                LOGGER.error("Failed to get PacketEncoder of connection {} {}.",
                        connection.getSide(), connection.getAddress());
                return;
            }
            // Access-widened PacketEncoder.side gives the outbound direction
            NetworkSide encoderSide = encoder.side;
            var sendPackets = new ArrayList<>(packets);
            packets.clear();

            int maxPayload = encoderSide == NetworkSide.CLIENTBOUND ? MAX_S2C_PAYLOAD : MAX_C2S_PAYLOAD;
            sendBatched(connection, sendPackets, encoderSide, maxPayload);
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to flush packets.", e);
        }
    }

    private static void sendBatched(ClientConnection connection,
                                     ArrayList<AggregatedEncodePacket> batch,
                                     NetworkSide encoderSide, int maxPayload) {
        if (batch.isEmpty()) return;

        var aggregationPayload = new PacketAggregationPacket(batch, encoderSide, connection);
        PacketByteBuf buf = new PacketByteBuf(ByteBufAllocator.DEFAULT.buffer());
        aggregationPayload.write(buf);

        if (buf.readableBytes() <= maxPayload || batch.size() == 1) {
            Packet<?> wrapper = encoderSide == NetworkSide.CLIENTBOUND
                    ? new CustomPayloadS2CPacket(PacketAggregationPacket.CHANNEL, buf)
                    : new CustomPayloadC2SPacket(PacketAggregationPacket.CHANNEL, buf);
            connection.send(wrapper);
            connection.channel.flush();
        } else {
            buf.release();
            int mid = batch.size() / 2;
            sendBatched(connection, new ArrayList<>(batch.subList(0, mid)), encoderSide, maxPayload);
            sendBatched(connection, new ArrayList<>(batch.subList(mid, batch.size())), encoderSide, maxPayload);
        }
    }
}
