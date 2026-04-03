package cn.ussshenzhou.notenoughbandwidth.config;

public interface TConfig {
    default String getChildDirName() {
        return "";
    }
}
