package cn.ussshenzhou.notenoughbandwidth.zstd;

import com.github.luben.zstd.ZstdDictTrainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages Zstd dictionary lifecycle: sampling, training, persistence, and distribution.
 * <p>
 * Server side: collects raw aggregation buffers as training samples,
 * trains a dictionary once enough samples are gathered, persists to disk.
 * Client side: receives dictionary from server during handshake.
 */
public class DictionaryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Dict");

    private static final int SAMPLE_THRESHOLD = 2000;
    private static final int DICT_SIZE = 64 * 1024;
    private static final int MAX_SAMPLE_SIZE = 16 * 1024;
    private static final int MAX_SAMPLE_BYTES = 32 * 1024 * 1024;
    private static final Path DICT_PATH = Path.of("config", "neb_trained_dict.bin");

    private static volatile byte[] currentDict;
    private static volatile boolean serverSide = false;
    private static final List<byte[]> samples = new ArrayList<>();
    private static int totalSampleBytes = 0;
    private static final AtomicBoolean training = new AtomicBoolean(false);

    public static void loadFromDisk() {
        serverSide = true;
        try {
            if (Files.exists(DICT_PATH)) {
                currentDict = Files.readAllBytes(DICT_PATH);
                LOGGER.info("Loaded trained dictionary from disk ({} bytes)", currentDict.length);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load dictionary from disk", e);
        }
    }

    public static byte[] getDict() {
        return currentDict;
    }

    public static void setDict(byte[] dict) {
        if (dict != null && dict.length > 0) {
            currentDict = dict;
            LOGGER.info("Dictionary loaded ({} bytes)", dict.length);
        } else {
            currentDict = null;
        }
    }

    public static boolean isSampling() {
        return serverSide && currentDict == null && !training.get();
    }

    public static int getDictSize() {
        byte[] dict = currentDict;
        return dict != null ? dict.length : 0;
    }

    public static int getSampleCount() {
        synchronized (samples) {
            return samples.size();
        }
    }

    public static int getSampleThreshold() {
        return SAMPLE_THRESHOLD;
    }

    public static void collectSample(byte[] raw) {
        if (!isSampling() || raw.length > MAX_SAMPLE_SIZE) {
            return;
        }
        synchronized (samples) {
            if (totalSampleBytes >= MAX_SAMPLE_BYTES) {
                return;
            }
            samples.add(raw);
            totalSampleBytes += raw.length;
            int count = samples.size();
            if (count % 500 == 0) {
                LOGGER.info("Dictionary sampling: {}/{} samples ({} KB)",
                        count, SAMPLE_THRESHOLD, totalSampleBytes / 1024);
            }
            if (count >= SAMPLE_THRESHOLD) {
                trainAsync();
            }
        }
    }

    private static void trainAsync() {
        if (!training.compareAndSet(false, true)) {
            return;
        }
        final List<byte[]> snapshot;
        synchronized (samples) {
            snapshot = new ArrayList<>(samples);
            samples.clear();
            totalSampleBytes = 0;
        }
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("Training dictionary from {} samples...", snapshot.size());
                int sampleSum = snapshot.stream().mapToInt(s -> s.length).sum();
                var trainer = new ZstdDictTrainer(sampleSum, DICT_SIZE);
                for (byte[] sample : snapshot) {
                    trainer.addSample(sample);
                }
                byte[] dict = trainer.trainSamples();
                currentDict = dict;
                saveToDisk(dict);
                LOGGER.info("Dictionary trained and saved ({} bytes from {} samples)", dict.length, snapshot.size());
            } catch (Exception e) {
                LOGGER.error("Dictionary training failed", e);
            } finally {
                training.set(false);
            }
        });
    }

    private static void saveToDisk(byte[] dict) {
        try {
            Files.createDirectories(DICT_PATH.getParent());
            Files.write(DICT_PATH, dict);
        } catch (IOException e) {
            LOGGER.error("Failed to save dictionary to disk", e);
        }
    }
}
