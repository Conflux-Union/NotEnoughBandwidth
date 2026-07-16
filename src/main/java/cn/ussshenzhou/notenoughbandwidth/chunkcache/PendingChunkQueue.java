package cn.ussshenzhou.notenoughbandwidth.chunkcache;

// 1.20.1-only: on 1.20.2+ the server knows the client's NEB state before the
// chunk burst (no PENDING phase), so this queue is never needed there.
//#if MC<12005
//$$ import cn.ussshenzhou.notenoughbandwidth.network.ChunkHashPayload;
//$$ import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
//$$ import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
//$$ import net.minecraft.network.Connection;
//$$ import net.minecraft.network.FriendlyByteBuf;
//$$ import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
//$$ import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
//$$ import net.minecraft.server.level.ServerPlayer;
//$$ import org.slf4j.Logger;
//$$ import org.slf4j.LoggerFactory;
//$$
//$$ import java.util.ArrayList;
//$$ import java.util.List;
//$$ import java.util.concurrent.ConcurrentHashMap;
//$$
//$$ /**
//$$  * Holds chunk data packets queued during the PENDING handshake phase so they
//$$  * can be replayed with PCC (bloom filter) awareness once the client's manifest
//$$  * has arrived and the connection is fully enabled.
//$$  *
//$$  * All enqueue calls happen on the server thread (from ChunkMap.playerLoadedChunk).
//$$  * All drain calls happen on the server thread (from server.execute()).
//$$  */
//$$ public class PendingChunkQueue {
//$$     private static final Logger LOGGER = LoggerFactory.getLogger("NEB-PendingChunks");
//$$
//$$     private record Entry(ServerPlayer player, ClientboundLevelChunkWithLightPacket packet) {}
//$$
//$$     private static final ConcurrentHashMap<Connection, List<Entry>> QUEUES = new ConcurrentHashMap<>();
//$$
//$$     public static void enqueue(Connection conn, ServerPlayer player, ClientboundLevelChunkWithLightPacket packet) {
//$$         QUEUES.compute(conn, (k, list) -> {
//$$             if (list == null) list = new ArrayList<>();
//$$             list.add(new Entry(player, packet));
//$$             return list;
//$$         });
//$$     }
//$$
//$$     /**
//$$      * Replay all queued chunk sends with PCC awareness.
//$$      * Called on the server thread after NebAck (bloom filter should be stored).
//$$      */
//$$     public static void drainAndSend(Connection connection) {
//$$         List<Entry> entries = QUEUES.remove(connection);
//$$         if (entries == null || entries.isEmpty()) return;
//$$
//$$         int hits = 0, misses = 0;
//$$         for (Entry entry : entries) {
//$$             if (!entry.player().connection.connection.isConnected()) continue;
//$$
//$$             ChunkHashUtil.Result result = ChunkHashUtil.compute(
//$$                     entry.packet().getChunkData(), entry.player().serverLevel().registryAccess(),
//$$                     "SERVER", entry.packet().getX(), entry.packet().getZ());
//$$
//$$             if (ChunkCacheManager.serverMightHaveChunk(connection, result.hash())) {
//$$                 FriendlyByteBuf buf = PacketByteBufs.create();
//$$                 new ChunkHashPayload(entry.packet().getX(), entry.packet().getZ(), result.hash()).write(buf);
//$$                 entry.player().connection.send(
//$$                         new ClientboundCustomPayloadPacket(ChunkHashPayload.CHANNEL, buf));
//$$                 SimpleStatManager.chunkCacheHits.incrementAndGet();
//$$                 SimpleStatManager.chunkCacheSavedBytes.addAndGet(result.dataBytes());
//$$                 SimpleStatManager.outRaw((int) Math.min(result.dataBytes(), Integer.MAX_VALUE));
//$$                 hits++;
//$$             } else {
//$$                 entry.player().connection.send(entry.packet());
//$$                 SimpleStatManager.chunkCacheMisses.incrementAndGet();
//$$                 misses++;
//$$             }
//$$         }
//$$         LOGGER.info("Drained {} pending chunks ({} hits, {} misses)", entries.size(), hits, misses);
//$$     }
//$$
//$$     public static void discard(Connection conn) {
//$$         QUEUES.remove(conn);
//$$     }
//$$ }
//#endif
