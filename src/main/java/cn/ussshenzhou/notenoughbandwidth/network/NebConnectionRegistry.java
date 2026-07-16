package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.Connection;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Tracks which ClientConnections have a NEB-capable peer on the other end.
 * <p>
 * Server side: a connection is marked enabled when the client sends NebAckPayload.
 * On 1.20.1 the server additionally uses a PENDING state between sending
 * IndexSync and receiving NebAck: packets are intercepted and buffered but not
 * flushed until the client confirms (or a timeout demotes the connection).
 * Later versions never mark PENDING, so isActive() degenerates to isEnabled().
 * <p>
 * Client side: a connection is marked enabled when the server sends IndexSyncPayload.
 * <p>
 * Backed by WeakHashMaps so dead connections are GC'd automatically.
 */
public class NebConnectionRegistry {
    private static final Set<Connection> ENABLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Connection> PENDING =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * Mark connection as pending — packets will be buffered but not flushed.
     * Called on the server after sending IndexSync, before NebAck arrives.
     */
    public static void markPending(Connection connection) {
        PENDING.add(connection);
    }

    /**
     * Promote connection to fully enabled.
     * Synchronized on PENDING to be atomic w.r.t. tryDemoteFromPending.
     */
    public static void markEnabled(Connection connection) {
        synchronized (PENDING) {
            PENDING.remove(connection);
            ENABLED.add(connection);
        }
    }

    public static void markDisabled(Connection connection) {
        synchronized (PENDING) {
            PENDING.remove(connection);
            ENABLED.remove(connection);
        }
    }

    /** Connection is fully active — flush is allowed. */
    public static boolean isEnabled(Connection connection) {
        return ENABLED.contains(connection);
    }

    /** Connection is pending — buffer packets but do not flush. */
    public static boolean isPending(Connection connection) {
        return PENDING.contains(connection);
    }

    /**
     * Atomically demote a pending connection to disabled.
     * Returns true if the connection was actually pending (and is now disabled).
     * Returns false if it was already promoted to enabled or was never pending.
     */
    public static boolean tryDemoteFromPending(Connection connection) {
        synchronized (PENDING) {
            if (PENDING.remove(connection)) {
                ENABLED.remove(connection);
                return true;
            }
            return false;
        }
    }

    /** Connection should intercept and buffer packets (pending OR enabled). */
    public static boolean isActive(Connection connection) {
        synchronized (PENDING) {
            return ENABLED.contains(connection) || PENDING.contains(connection);
        }
    }
}
