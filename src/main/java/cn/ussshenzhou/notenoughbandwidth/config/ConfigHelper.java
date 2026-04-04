package cn.ussshenzhou.notenoughbandwidth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ConfigHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Config");
    private static final File CONFIG_DIR = Paths.get("config").toFile();
    private static final ConcurrentHashMap<Class<? extends TConfig>, TConfig> CACHE = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static void checkDir(File dir) {
        if (!dir.isDirectory()) {
            dir.mkdir();
        }
    }

    private static File checkFile(TConfig config) {
        checkDir(CONFIG_DIR);
        String configFileName = config.getClass().getSimpleName();
        var childDir = CONFIG_DIR.toPath().resolve(config.getChildDirName());
        checkDir(childDir.toFile());
        return childDir.resolve(configFileName + ".json").toFile();
    }

    public static void loadConfig(TConfig newInstance) {
        File configFile = checkFile(newInstance);
        loadConfigInternal(newInstance, configFile);
    }

    @SuppressWarnings("unchecked")
    public static <T extends TConfig> T getConfigRead(Class<T> configClass) {
        return (T) CACHE.get(configClass);
    }

    @SuppressWarnings("unchecked")
    public static <T extends TConfig> void getConfigWrite(Class<T> configClass, Consumer<T> setter) {
        T config = (T) CACHE.get(configClass);
        // Synchronize on the config instance so concurrent writes and the GSON
        // snapshot in saveConfigInternal see a consistent object state.
        synchronized (config) {
            setter.accept(config);
            saveConfig(config);
        }
    }

    public static <T extends TConfig> void saveConfig(T config) {
        File configFile = checkFile(config);
        saveConfigInternal(config, configFile);
    }

    private static void loadConfigInternal(TConfig newInstance, File configFile) {
        try {
            if (configFile.isFile()) {
                newInstance = GSON.fromJson(FileUtils.readFileToString(configFile, StandardCharsets.UTF_8), newInstance.getClass());
            } else {
                FileUtils.write(configFile, GSON.toJson(newInstance), StandardCharsets.UTF_8);
            }
            CACHE.put(newInstance.getClass(), newInstance);
            saveConfigInternal(newInstance, configFile);
        } catch (IOException ignored) {
            LOGGER.error("Failed to load config {}. Things may not work well.", newInstance.getClass());
        }
    }

    private static <T extends TConfig> void saveConfigInternal(T config, File configFile) {
        // Snapshot JSON on the calling thread to avoid racing with concurrent mutations.
        String json = GSON.toJson(config);
        CompletableFuture.runAsync(() -> {
            try {
                FileUtils.write(configFile, json, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                LOGGER.error("Failed to save config {}. Things may not work well.", config.getClass());
            }
        });
    }
}
