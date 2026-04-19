package cn.ussshenzhou.notenoughbandwidth.bench;

import cn.ussshenzhou.notenoughbandwidth.bench.scenarios.BenchScenario;
import cn.ussshenzhou.notenoughbandwidth.bench.scenarios.EntitiesScenario;
import cn.ussshenzhou.notenoughbandwidth.bench.scenarios.RoamScenario;
import io.netty.channel.Channel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-side CI benchmark orchestrator. Activated when `-Dneb.benchMode`
 * is set (values: neb | zlib | raw). Waits for the real Minecraft client
 * (launched as a separate JVM by the outer gradle task) to join, attaches
 * a Netty wire counter, runs the scenario for `neb.benchTicks` ticks, then
 * halts the server. The outer task drives the 3×2 matrix of modes and
 * scenarios across separate JVM pairs.
 */
public final class BenchmarkRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Bench");

    public static final String PROP_MODE = "neb.benchMode";
    public static final String PROP_SCENARIO = "neb.benchScenario";
    public static final String PROP_OUTPUT = "neb.benchOutput";
    public static final String PROP_TICKS = "neb.benchTicks";

    private static volatile boolean installed;

    /** Called unconditionally from the main mod entrypoint; no-op if benchMode isn't set. */
    public static void maybeInstall() {
        if (installed) return;
        String mode = System.getProperty(PROP_MODE, "").trim();
        if (mode.isEmpty()) return;
        installed = true;

        String scenarioName = System.getProperty(PROP_SCENARIO, "roam").trim();
        String output = System.getProperty(PROP_OUTPUT, "build/benchmark/" + scenarioName + "-" + mode + ".csv").trim();
        int ticks = Integer.parseInt(System.getProperty(PROP_TICKS, "1200").trim());

        BenchScenario scenario = switch (scenarioName) {
            case "entities" -> new EntitiesScenario();
            case "roam" -> new RoamScenario();
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenarioName);
        };
        if (!(mode.equals("neb") || mode.equals("zlib") || mode.equals("raw"))) {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }

        LOGGER.info("BenchmarkRunner armed: mode={} scenario={} ticks={} output={}",
                mode, scenarioName, ticks, output);

        State state = new State(mode, scenario, ticks, Paths.get(output));

        // First player to join in this dedicated run is our bench client —
        // the outer orchestrator gives each run a fresh empty world on a private port,
        // so there is no ambient traffic to confuse us.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (state.player != null) return;
            ServerPlayerEntity p = handler.player;
            state.player = p;
            Channel ch = handler.connection.channel;
            ch.pipeline().addFirst(WireByteCounter.HANDLER_NAME, state.counter);
            state.running = true;
            state.startWallMs = System.currentTimeMillis();
            LOGGER.info("Bench client joined: {} — WireByteCounter attached, scenario starts next tick",
                    p.getName().getString());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> onServerTick(server, state));
    }

    private static void onServerTick(MinecraftServer server, State state) {
        if (!state.running || state.player == null || state.finished) return;

        int tick = state.tickCounter.get();
        try {
            if (tick == 0) {
                state.scenario.setup(server, state.player);
                // Reset counter AFTER setup so spawn/initial-teleport traffic isn't
                // counted in the baseline. First real sample is at tick 1.
                state.counter.reset();
                state.csv.sample(0, 0L, 0L);
            } else {
                state.scenario.tick(tick, server, state.player);
                state.csv.sample(tick, state.counter.bytes(), state.counter.packets());
            }
        } catch (Throwable t) {
            LOGGER.error("Scenario tick {} crashed", tick, t);
            finishAndHalt(server, state);
            return;
        }

        if (tick >= state.ticks) {
            finishAndHalt(server, state);
            return;
        }
        state.tickCounter.incrementAndGet();
    }

    private static void finishAndHalt(MinecraftServer server, State state) {
        if (state.finished) return;
        state.finished = true;
        state.running = false;
        try {
            long wall = System.currentTimeMillis() - state.startWallMs;
            state.csv.writeCsv(state.output, state.mode, state.scenario.name());
            Path meta = Paths.get(state.output.toString().replaceFirst("\\.csv$", ".meta.json"));
            state.csv.writeMeta(meta, state.mode, state.scenario.name(), wall);
            LOGGER.info("Bench done: {} samples, {} bytes, {} ms wall — wrote {}",
                    state.csv.sampleCount(), state.csv.totalBytes(), wall, state.output);
        } catch (Throwable t) {
            LOGGER.error("Failed to write bench output to {}", state.output, t);
        }
        // Server stop triggers a clean DisconnectS2CPacket to the real client,
        // which then calls MinecraftClient.scheduleStop() on its DISCONNECT event.
        server.stop(false);
    }

    private static final class State {
        final String mode;
        final BenchScenario scenario;
        final int ticks;
        final Path output;
        final WireByteCounter counter = new WireByteCounter();
        final BenchCsvWriter csv = new BenchCsvWriter();
        final AtomicInteger tickCounter = new AtomicInteger();

        volatile ServerPlayerEntity player;
        volatile boolean running;
        volatile boolean finished;
        volatile long startWallMs;

        State(String mode, BenchScenario scenario, int ticks, Path output) {
            this.mode = mode;
            this.scenario = scenario;
            this.ticks = ticks;
            this.output = output;
        }
    }
}
