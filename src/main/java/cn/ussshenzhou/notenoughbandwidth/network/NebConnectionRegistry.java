package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.ClientConnection;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Tracks which ClientConnections have a NEB-capable peer on the other end.
 * <p>
 * Server side: a connection transitions through two states:
 *   1. PENDING — set immediately after sending IndexSync.  Packets are
 *      intercepted and buffered but NOT flushed until the client confirms.
 *   2. ENABLED — set when the client sends NebAckPayload.  Buffered
 *      packets are flushed and normal aggregation begins.
 * <p>
 * Client side: a connection is marked ENABLED when the server sends
 * IndexSyncPayload (no pending phase needed on the client).
 * <p>
 * Backed by a WeakHashMap so dead connections are GC'd automatically.
 */
public class NebConnectionRegistry {
    private static final Set<ClientConnection> ENABLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<ClientConnection> PENDING =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * Mark connection as pending — packets will be buffered but not flushed.
     * Called on the server after sending IndexSync, before NebAck arrives.
     */
    public static void markPending(ClientConnection connection) {
        PENDING.add(connection);
    }

    /**
     * Promote connection from pending to fully enabled.
     * Called when NebAck is received from the client.
     * Synchronized on PENDING to be atomic w.r.t. tryDemoteFromPending.
     */
    public static void markEnabled(ClientConnection connection) {
        synchronized (PENDING) {
            PENDING.remove(connection);
            ENABLED.add(connection);
        }
    }

    public static void markDisabled(ClientConnection connection) {
        PENDING.remove(connection);
        ENABLED.remove(connection);
    }

    /** Connection is fully active — flush is allowed. */
    public static boolean isEnabled(ClientConnection connection) {
        return ENABLED.contains(connection);
    }

    /** Connection is pending — buffer packets but do not flush. */
    public static boolean isPending(ClientConnection connection) {
        return PENDING.contains(connection);
    }

    /**
     * Atomically demote a pending connection to disabled.
     * Returns true if the connection was actually pending (and is now disabled).
     * Returns false if it was already promoted to enabled or was never pending.
     */
    public static boolean tryDemoteFromPending(ClientConnection connection) {
        synchronized (PENDING) {
            if (PENDING.remove(connection)) {
                ENABLED.remove(connection);
                return true;
            }
            return false;
        }
    }

    /** Connection should intercept and buffer packets (pending OR enabled). */
    public static boolean isActive(ClientConnection connection) {
        synchronized (PENDING) {
            return ENABLED.contains(connection) || PENDING.contains(connection);
        }
    }
}
