package cn.ussshenzhou.notenoughbandwidth.chunkcache;

import cn.ussshenzhou.notenoughbandwidth.network.ChunkHashPayload;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds chunk data packets queued during the PENDING handshake phase so they
 * can be replayed with PCC (bloom filter) awareness once the client's manifest
 * has arrived and the connection is fully enabled.
 *
 * All enqueue calls happen on the server thread (from sendChunkDataPackets).
 * All drain calls happen on the server thread (from server.execute()).
 */
public class PendingChunkQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-PendingChunks");

    private record Entry(ServerPlayerEntity player, ChunkDataS2CPacket packet) {}

    private static final ConcurrentHashMap<ClientConnection, List<Entry>> QUEUES = new ConcurrentHashMap<>();

    public static void enqueue(ClientConnection conn, ServerPlayerEntity player, ChunkDataS2CPacket packet) {
        QUEUES.compute(conn, (k, list) -> {
            if (list == null) list = new ArrayList<>();
            list.add(new Entry(player, packet));
            return list;
        });
    }

    /**
     * Replay all queued chunk sends with PCC awareness.
     * Called on the server thread after NebAck (bloom filter should be stored).
     */
    public static void drainAndSend(ClientConnection connection) {
        List<Entry> entries = QUEUES.remove(connection);
        if (entries == null || entries.isEmpty()) return;

        int hits = 0, misses = 0;
        for (Entry entry : entries) {
            if (!entry.player.networkHandler.connection.isOpen()) continue;

            ChunkHashUtil.Result result = ChunkHashUtil.compute(
                    entry.packet.getChunkData(), "SERVER",
                    entry.packet.getX(), entry.packet.getZ());

            if (ChunkCacheManager.serverMightHaveChunk(connection, result.hash())) {
                PacketByteBuf buf = PacketByteBufs.create();
                new ChunkHashPayload(entry.packet.getX(), entry.packet.getZ(), result.hash()).write(buf);
                entry.player.networkHandler.sendPacket(
                        new CustomPayloadS2CPacket(ChunkHashPayload.CHANNEL, buf));
                SimpleStatManager.chunkCacheHits.incrementAndGet();
                SimpleStatManager.chunkCacheSavedBytes.addAndGet(result.dataBytes());
                SimpleStatManager.outRaw((int) Math.min(result.dataBytes(), Integer.MAX_VALUE));
                hits++;
            } else {
                entry.player.networkHandler.sendPacket(entry.packet);
                SimpleStatManager.chunkCacheMisses.incrementAndGet();
                misses++;
            }
        }
        LOGGER.info("Drained {} pending chunks ({} hits, {} misses)", entries.size(), hits, misses);
    }

    public static void discard(ClientConnection conn) {
        QUEUES.remove(conn);
    }
}
