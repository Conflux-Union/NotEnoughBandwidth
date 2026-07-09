package cn.ussshenzhou.notenoughbandwidth.update;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Update");
    private static final String API_URL =
            "https://api.github.com/repos/RMS-Server/NotEnoughBandwidth/releases/latest";
    private static final String RELEASES_PAGE =
            "https://github.com/RMS-Server/NotEnoughBandwidth/releases";

    private static volatile UpdateInfo updateInfo = null;
    private static CompletableFuture<Void> checkFuture = null;

    public static class UpdateInfo {
        private final String latestVersion;
        private final String releaseUrl;

        public UpdateInfo(String latestVersion, String releaseUrl) {
            this.latestVersion = latestVersion;
            this.releaseUrl = releaseUrl;
        }

        public String latestVersion() {
            return latestVersion;
        }

        public String releaseUrl() {
            return releaseUrl;
        }
    }

    public static String getLocalVersion() {
        return FabricLoader.getInstance()
                .getModContainer(ModConstants.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public static void checkAsync() {
        String localVersion = getLocalVersion();
        checkFuture = CompletableFuture.runAsync(() -> doCheck(localVersion));
    }

    private static void doCheck(String localVersion) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "NotEnoughBandwidth-UpdateChecker")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("Update check failed: HTTP {}", response.statusCode());
                return;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String tagName = json.get("tag_name").getAsString();
            String htmlUrl = json.has("html_url") && !json.get("html_url").isJsonNull()
                    ? json.get("html_url").getAsString()
                    : RELEASES_PAGE;

            // Strip leading "v" (e.g. "v1.21.4-5" -> "1.21.4-5")
            String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;

            if (isNewerVersion(latestVersion, localVersion)) {
                updateInfo = new UpdateInfo(latestVersion, htmlUrl);
                LOGGER.info("Update available: {} (current: {})", latestVersion, localVersion);
            } else {
                LOGGER.info("NEB is up to date ({})", localVersion);
            }
        } catch (Exception e) {
            LOGGER.warn("Update check failed: {}", e.getMessage());
        }
    }

    public static UpdateInfo getUpdateInfo() {
        return updateInfo;
    }

    public static boolean isCheckComplete() {
        return checkFuture != null && checkFuture.isDone();
    }

    /**
     * Attach a callback that fires when the in-flight check completes.
     * If the check already finished, the callback runs immediately on the calling thread.
     */
    public static void onComplete(Runnable callback) {
        if (checkFuture != null) {
            checkFuture.thenRun(callback);
        }
    }

    /**
     * Version format: {@code {mc_version}-{build}}, e.g. {@code 1.21.4-5}.
     * A trailing {@code +suffix} (e.g. {@code +alpha}) is stripped before comparison.
     * <p>
     * Returns true when {@code remote} is strictly newer than {@code local}:
     * <ul>
     *   <li>Same MC-version prefix: compare build numbers numerically.</li>
     *   <li>Different MC-version prefix: always true (new release for a newer MC version).</li>
     *   <li>Cannot parse: fallback to string inequality.</li>
     * </ul>
     */
    static boolean isNewerVersion(String remote, String local) {
        remote = remote.split("\\+")[0];
        local = local.split("\\+")[0];

        if (remote.equals(local)) return false;

        int remoteDash = remote.lastIndexOf('-');
        int localDash = local.lastIndexOf('-');

        if (remoteDash > 0 && localDash > 0) {
            String remotePrefix = remote.substring(0, remoteDash);
            String localPrefix = local.substring(0, localDash);
            int remoteBuild = parseBuild(remote.substring(remoteDash + 1));
            int localBuild = parseBuild(local.substring(localDash + 1));

            if (remoteBuild >= 0 && localBuild >= 0) {
                if (!remotePrefix.equals(localPrefix)) return true;
                return remoteBuild > localBuild;
            }
        }

        return true;
    }

    private static int parseBuild(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
