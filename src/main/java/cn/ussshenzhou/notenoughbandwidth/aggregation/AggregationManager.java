package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.util.DefaultChannelPipelineHelper;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.channel.DefaultChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
//#if MC>=12005
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
//#else
//$$ import io.netty.buffer.ByteBufAllocator;
//$$ import net.minecraft.network.FriendlyByteBuf;
//$$ import net.minecraft.network.PacketListener;
//$$ import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
//$$ import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
//$$ import net.minecraft.network.protocol.game.ServerGamePacketListener;
//$$ import net.minecraft.network.protocol.game.ClientGamePacketListener;
//#endif
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

    //#if MC<12005
    //$$ /**
    //$$  * PLAY-phase check without PacketListener.protocol(), which doesn't exist on
    //$$  * 1.20.1. The PLAY listener interfaces live in the common jar, so this is
    //$$  * safe on a dedicated server and survives intermediary remapping in production
    //$$  * (unlike a class-name string comparison).
    //$$  */
    //$$ private static boolean isPlayPhase(PacketListener listener) {
    //$$     return listener instanceof ServerGamePacketListener || listener instanceof ClientGamePacketListener;
    //$$ }
    //$$
    //$$ /** Vanilla 1.20.1 custom payload size limits enforced by the packet readers. */
    //$$ private static final int MAX_C2S_PAYLOAD = 32767;
    //$$ private static final int MAX_S2C_PAYLOAD = 1048576;
    //#endif

    private static void flushInternal(Connection connection, @Nullable ArrayList<AggregatedEncodePacket> packets) {
        try {
            if (packets == null || packets.isEmpty()) {
                return;
            }
            var listener = connection.getPacketListener();
            //#if MC>=12005
            if (!connection.isConnected() || listener == null
                    || listener.protocol() != ConnectionProtocol.PLAY
                    || !NebConnectionRegistry.isEnabled(connection)) {
                packets.clear();
                return;
            }
            //#else
            //$$ if (!connection.isConnected() || listener == null || !isPlayPhase(listener)) {
            //$$     packets.clear();
            //$$     return;
            //$$ }
            //$$ if (NebConnectionRegistry.isPending(connection)) {
            //$$     // Handshake in progress — keep packets buffered until NebAck arrives.
            //$$     return;
            //$$ }
            //$$ if (!NebConnectionRegistry.isEnabled(connection)) {
            //$$     packets.clear();
            //$$     return;
            //$$ }
            //#endif
            var encoder = DefaultChannelPipelineHelper.getPacketEncoder(
                    (DefaultChannelPipeline) connection.channel.pipeline());
            if (encoder == null) {
                LOGGER.error("Failed to get PacketEncoder of connection {} {}.",
                        connection.getReceiving(), connection.getRemoteAddress());
                return;
            }
            var sendPackets = new ArrayList<>(packets);
            packets.clear();
            //#if MC>=12005
            var aggregationPayload = new PacketAggregationPacket(
                    sendPackets, encoder.protocolInfo, connection);
            // encoder.protocolInfo.flow() = outbound direction (CLIENTBOUND on server, SERVERBOUND on client)
            Packet<?> wrapper = encoder.protocolInfo.flow() == PacketFlow.CLIENTBOUND
                    ? new ClientboundCustomPayloadPacket(aggregationPayload)
                    : new ServerboundCustomPayloadPacket(aggregationPayload);
            connection.send(wrapper);
            connection.flushChannel();
            //#else
            //$$ // Access-widened PacketEncoder.flow gives the outbound direction.
            //$$ PacketFlow encoderFlow = encoder.flow;
            //$$ int maxPayload = encoderFlow == PacketFlow.CLIENTBOUND ? MAX_S2C_PAYLOAD : MAX_C2S_PAYLOAD;
            //$$ sendBatched(connection, sendPackets, encoderFlow, maxPayload);
            //#endif
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to flush packets.", e);
        }
    }

    //#if MC<12005
    //$$ /**
    //$$  * 1.20.1's CustomPayload packets enforce hard size limits at read time, so an
    //$$  * oversized aggregate must be split. Serialization is cheap relative to the
    //$$  * network, and Context uses stateless compression on 1.20.1, so re-encoding
    //$$  * the halves is safe (no shared stream state is corrupted by the discarded
    //$$  * oversized attempt).
    //$$  */
    //$$ private static void sendBatched(Connection connection,
    //$$                                 ArrayList<AggregatedEncodePacket> batch,
    //$$                                 PacketFlow encoderFlow, int maxPayload) {
    //$$     if (batch.isEmpty()) return;
    //$$
    //$$     var aggregationPayload = new PacketAggregationPacket(batch, encoderFlow, connection);
    //$$     FriendlyByteBuf buf = new FriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer());
    //$$     aggregationPayload.write(buf);
    //$$
    //$$     if (buf.readableBytes() <= maxPayload || batch.size() == 1) {
    //$$         Packet<?> wrapper = encoderFlow == PacketFlow.CLIENTBOUND
    //$$                 ? new ClientboundCustomPayloadPacket(PacketAggregationPacket.CHANNEL, buf)
    //$$                 : new ServerboundCustomPayloadPacket(PacketAggregationPacket.CHANNEL, buf);
    //$$         connection.send(wrapper);
    //$$         connection.channel.flush();
    //$$     } else {
    //$$         buf.release();
    //$$         int mid = batch.size() / 2;
    //$$         sendBatched(connection, new ArrayList<>(batch.subList(0, mid)), encoderFlow, maxPayload);
    //$$         sendBatched(connection, new ArrayList<>(batch.subList(mid, batch.size())), encoderFlow, maxPayload);
    //$$     }
    //$$ }
    //#endif
}
