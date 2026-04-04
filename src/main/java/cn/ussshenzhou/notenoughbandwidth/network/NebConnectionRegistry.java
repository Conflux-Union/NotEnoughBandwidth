package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.ClientConnection;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Tracks which ClientConnections have a NEB-capable peer on the other end.
 * <p>
 * Server side: a connection is marked enabled when the client sends NebAckPayload.
 * Client side: a connection is marked enabled when the server sends IndexSyncPayload.
 * <p>
 * Backed by a WeakHashMap so dead connections are GC'd automatically.
 */
public class NebConnectionRegistry {
    private static final Set<ClientConnection> ENABLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public static void markEnabled(ClientConnection connection) {
        ENABLED.add(connection);
    }

    public static void markDisabled(ClientConnection connection) {
        ENABLED.remove(connection);
    }

    public static boolean isEnabled(ClientConnection connection) {
        return ENABLED.contains(connection);
    }
}
