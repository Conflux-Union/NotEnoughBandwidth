package cn.ussshenzhou.notenoughbandwidth.aggregation;

public class AggregationFlushHelper {
    public static int getFlushPeriodInMilliseconds() {
        return 20;
    }

    public static int getFlushCountInSeconds() {
        return Math.max(1000 / getFlushPeriodInMilliseconds(), 1);
    }

    public static int getThresholdCount1s() {
        return 20 * 2;
    }
}
