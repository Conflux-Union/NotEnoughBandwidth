package cn.ussshenzhou.notenoughbandwidth.util;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.util.Util;

public class TimeCounter {
    private final Long2IntOpenHashMap container = new Long2IntOpenHashMap();
    private final int windowsSizeMs;

    public TimeCounter(int windowsSizeMs) {
        this.windowsSizeMs = windowsSizeMs;
    }

    public TimeCounter() {
        this(2000);
    }

    private synchronized void update() {
        long now = Util.getMillis();
        container.keySet().removeIf(then -> now - then > windowsSizeMs);
    }

    public synchronized void put(int value) {
        update();
        container.addTo(Util.getMillis(), value);
    }

    public synchronized double averageIn1s() {
        update();
        return container.values().intStream().sum() / (double) windowsSizeMs * 1000;
    }
}
