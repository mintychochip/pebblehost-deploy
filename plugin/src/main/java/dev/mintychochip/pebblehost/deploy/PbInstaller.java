package dev.mintychochip.pebblehost.deploy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the pb CLI binary: explicit pbBinary > PATH > auto-install from
 * mintychochip/pebblehost-cli GitHub releases (sha256-verified, cached per tag).
 */
public class PbInstaller {
    static final String DEFAULT_BINARY = "pb";
    private static final String DEFAULT_API_BASE =
        "https://api.github.com/repos/mintychochip/pebblehost-cli/releases/";

    private final Path cacheRoot;
    private final List<String> pathDirs;
    private final Logger logger;
    private final String apiBase;

    public PbInstaller(Path cacheRoot, Logger logger) {
        this(cacheRoot, currentPathDirs(), logger, DEFAULT_API_BASE);
    }

    PbInstaller(Path cacheRoot, List<String> pathDirs, Logger logger, String apiBase) {
        this.cacheRoot = cacheRoot;
        this.pathDirs = pathDirs;
        this.logger = logger;
        this.apiBase = apiBase;
    }

    /** Returns an absolute filesystem path to a usable pb binary. */
    public String resolve(String pbBinary, String pbVersion) {
        if (isExplicit(pbBinary)) {
            try {
                Path explicit = Path.of(pbBinary);
                if (!Files.isRegularFile(explicit)) {
                    throw new GradleException("pbBinary '" + pbBinary
                        + "' does not exist. Point it at an existing pb binary, or leave it at '"
                        + DEFAULT_BINARY + "' to let the plugin manage installation.");
                }
                return explicit.toAbsolutePath().toString();
            } catch (InvalidPathException e) {
                throw new GradleException("pbBinary '" + pbBinary + "' is not a valid filesystem path.", e);
            }
        }
        Path onPath = findOnPath();
        if (onPath != null) {
            return onPath.toAbsolutePath().toString();
        }
        return install(pbVersion == null || pbVersion.isBlank() ? "latest" : pbVersion.trim());
    }

    private static boolean isExplicit(String pbBinary) {
        return pbBinary != null && !pbBinary.isBlank() && !pbBinary.equals(DEFAULT_BINARY);
    }

    private Path findOnPath() {
        String name = binaryName();
        for (String dir : pathDirs) {
            Path candidate = Path.of(dir, name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String install(String versionSpec) {
        String tag = normalizeTag(versionSpec);
        String endpoint;
        if (tag.equals("latest")) {
            endpoint = apiBase + "latest";
        } else {
            // Pinned: the tag is known up front, so a cache hit is fully offline.
            Path cached = cacheRoot.resolve(tag).resolve(binaryName());
            if (Files.isRegularFile(cached)) {
                logger.lifecycle("Using cached pb {} from {}", tag, cached);
                return cached.toAbsolutePath().toString();
            }
            endpoint = apiBase + "tags/" + tag;
        }

        JsonObject release = getJson(endpoint, "pb release metadata");
        JsonElement tagEl = release.get("tag_name");
        if (tagEl == null || !tagEl.isJsonPrimitive()) {
            throw new GradleException("pb release metadata is missing a usable 'tag_name' string field.");
        }
        String resolvedTag = tagEl.getAsString();
        if (!resolvedTag.startsWith("v")) {
            throw new GradleException("Unexpected pb release tag '" + resolvedTag + "' (expected a v-prefixed tag).");
        }
        String version = resolvedTag.substring(1);
        String target = platformTarget(System.getProperty("os.name"), System.getProperty("os.arch"));
        String assetName = "pebblehost-cli-" + version + "-" + target + ".tar.gz";

        String downloadUrl = null;
        String expectedSha256 = null;
        try {
            for (JsonElement element : release.getAsJsonArray("assets")) {
                JsonObject asset = element.getAsJsonObject();
                if (assetName.equals(asset.get("name").getAsString())) {
                    downloadUrl = asset.get("browser_download_url").getAsString();
                    expectedSha256 = requireSha256Digest(asset, assetName);
                    break;
                }
            }
        } catch (GradleException ge) {
            throw ge;
        } catch (RuntimeException e) {
            throw new GradleException("pb release metadata from " + endpoint + " has an unexpected asset shape: "
                + e.getMessage(), e);
        }
        if (downloadUrl == null) {
            throw new GradleException("pb release " + resolvedTag + " publishes no asset '" + assetName
                + "'. Supported platforms: linux (x86_64, aarch64, armv7), macOS (x86_64, aarch64), Windows (x86_64).");
        }

        Path dir = cacheRoot.resolve(resolvedTag);
        Path binary = dir.resolve(binaryName());
        if (Files.isRegularFile(binary)) {
            logger.lifecycle("Using cached pb {} from {}", resolvedTag, binary);
            return binary.toAbsolutePath().toString();
        }
        downloadAndExtract(downloadUrl, assetName, expectedSha256, dir, binary);
        return binary.toAbsolutePath().toString();
    }

    private void downloadAndExtract(String url, String assetName, String expectedSha256, Path dir, Path binary) {
        Path staging = null;
        try {
            Files.createDirectories(dir.getParent());
            staging = Files.createTempDirectory(dir.getParent(), dir.getFileName().toString() + "-");
            Path tarball = Files.createTempFile(staging, assetName, ".part");
            String actualSha256;
            try (InputStream in = open(url, assetName); OutputStream out = Files.newOutputStream(tarball)) {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    sha256.update(buffer, 0, read);
                }
                actualSha256 = HexFormat.of().formatHex(sha256.digest());
            }
            if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(tarball);
                throw new GradleException("Downloaded " + assetName + " failed sha256 verification: expected "
                    + expectedSha256 + ", got " + actualSha256 + ". Nothing was installed.");
            }
            Process tar = new ProcessBuilder("tar", "-xzf", tarball.toString(), "-C", staging.toString())
                .redirectErrorStream(true)
                .start();
            boolean finished = tar.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                tar.destroyForcibly();
                throw new GradleException("timed out extracting " + assetName);
            }
            if (tar.exitValue() != 0) {
                String tarOutput = new String(tar.getInputStream().readAllBytes());
                throw new GradleException("Failed to extract " + assetName + ": " + tarOutput.strip());
            }
            Files.deleteIfExists(tarball);
            Path stagingBinary = staging.resolve(binary.getFileName());
            if (!Files.isRegularFile(stagingBinary)) {
                throw new GradleException("Archive " + assetName + " did not contain a " + binary.getFileName() + " binary.");
            }
            if (!isWindows()) {
                Files.setPosixFilePermissions(stagingBinary, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            Files.createDirectories(dir);
            try {
                Files.move(stagingBinary, binary, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                deleteRecursively(staging);
                if (Files.isRegularFile(binary)) {
                    logger.lifecycle("Using concurrently installed pb at {}", binary);
                } else {
                    throw new GradleException("Cannot atomically publish pb into " + dir
                        + " on this filesystem; refusing a partially-visible install. Set pebblehost.pbBinary to an existing pb"
                        + " or use a Gradle home on a local filesystem.", e);
                }
            }
            logger.lifecycle("Installed pb {} to {}", dir.getFileName(), binary);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM lacks SHA-256", e);
        } catch (IOException e) {
            throw new GradleException("pb auto-install failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("pb auto-install interrupted", e);
        } finally {
            deleteRecursively(staging);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private static String requireSha256Digest(JsonObject asset, String assetName) {
        JsonElement digest = asset.get("digest");
        if (digest == null || !digest.getAsString().startsWith("sha256:")) {
            throw new GradleException("pb release asset '" + assetName
                + "' publishes no sha256 digest; refusing to install an unverified binary.");
        }
        String rawDigest = digest.getAsString();
        String hex = rawDigest.substring("sha256:".length());
        if (hex.isEmpty() || hex.length() != 64 || !hex.matches("[0-9a-fA-F]{64}")) {
            throw new GradleException("pb release asset '" + assetName + "' publishes a malformed sha256 digest ('"
                + rawDigest + "'); refusing to install.");
        }
        return hex;
    }

    private InputStream open(String url, String what) {
        HttpResponse<InputStream> response = send(url, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GradleException("Failed to download " + what + " (HTTP " + response.statusCode()
                + rateLimitHint(response.statusCode()) + ").");
        }
        return response.body();
    }

    private JsonObject getJson(String url, String what) {
        HttpResponse<String> response = send(url, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GradleException("Failed to fetch " + what + " from " + url + " (HTTP " + response.statusCode()
                + rateLimitHint(response.statusCode()) + ").");
        }
        JsonObject release;
        try {
            release = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new GradleException("pb release metadata from " + url + " was malformed: " + e.getMessage(), e);
        }
        if (release.get("tag_name") == null || release.get("assets") == null) {
            throw new GradleException("pb release metadata is missing expected fields (tag_name/assets).");
        }
        return release;
    }

    private <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> handler) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new GradleException("pb auto-install received an invalid URL '" + url + "': " + e.getMessage(), e);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .GET();
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        try {
            return client.send(request.build(), handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Network failure fetching " + url, e);
        } catch (IOException e) {
            throw new GradleException("Network failure fetching " + url + ": " + e.getMessage(), e);
        }
    }

    private static String rateLimitHint(int statusCode) {
        return statusCode == 403 || statusCode == 429
            ? " — rate limited; set GITHUB_TOKEN in the environment to authenticate" : "";
    }

    static String normalizeTag(String versionSpec) {
        return versionSpec.equals("latest") || versionSpec.startsWith("v") ? versionSpec : "v" + versionSpec;
    }

    static String platformTarget(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.contains("linux")) {
            if (x64) return "x86_64-unknown-linux-gnu";
            if (arm64) return "aarch64-unknown-linux-gnu";
            if (arch.startsWith("arm")) return "armv7-unknown-linux-gnueabihf";
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (x64) return "x86_64-apple-darwin";
            if (arm64) return "aarch64-apple-darwin";
        } else if (os.contains("windows")) {

            return "x86_64-pc-windows-msvc";
        }
        throw new GradleException("pb auto-install does not support os='" + osName + "' arch='" + osArch
            + "'. Supported: linux (x86_64, aarch64, armv7), macOS (x86_64, aarch64), Windows (x86_64). "
            + "Set pebblehost.pbBinary to an existing pb to bypass auto-install.");
    }

    private static String binaryName() {
        return isWindows() ? "pb.exe" : "pb";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    static List<String> currentPathDirs() {
        String path = System.getenv("PATH");
        return path == null || path.isBlank() ? List.of() : List.of(path.split(java.io.File.pathSeparator));
    }
}
