package cn.ussshenzhou.notenoughbandwidth.stat;

/**
 * Fixed-capacity ring buffer for time-series data points.
 * Thread-safe via synchronized methods.
 */
public class TimeSeries {
    private final long[] data;
    private int head;
    private int count;

    public TimeSeries(int capacity) {
        this.data = new long[capacity];
    }

    public synchronized void push(long value) {
        data[head] = value;
        head = (head + 1) % data.length;
        if (count < data.length) {
            count++;
        }
    }

    /**
     * @param index 0 = oldest, size()-1 = newest
     */
    public synchronized long get(int index) {
        if (index < 0 || index >= count) {
            return 0;
        }
        int realIndex = (head - count + index + data.length) % data.length;
        return data[realIndex];
    }

    public synchronized int size() {
        return count;
    }

    public int capacity() {
        return data.length;
    }

    public synchronized long max() {
        long m = 0;
        for (int i = 0; i < count; i++) {
            int realIndex = (head - count + i + data.length) % data.length;
            if (data[realIndex] > m) {
                m = data[realIndex];
            }
        }
        return m;
    }

    /**
     * Returns a snapshot of all data points (oldest first) for safe iteration outside the lock.
     */
    public synchronized long[] snapshot() {
        long[] snap = new long[count];
        for (int i = 0; i < count; i++) {
            int realIndex = (head - count + i + data.length) % data.length;
            snap[i] = data[realIndex];
        }
        return snap;
    }

    public synchronized void reset() {
        head = 0;
        count = 0;
    }
}
