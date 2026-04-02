package me.s3b4s5.voicecast.nativevc;

import com.hypixel.hytale.logger.HytaleLogger;
import me.s3b4s5.voicecast.config.VoiceCastConfig;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Vosk model installer with per-language model directories.
 *  - If ModelPath points to a directory (recommended), install inside:
 *    Example: modelPath="vosk/models" -> "vosk/models/en" or "vosk/models/es"
 *  - If user explicitly points ModelPath to a specific model directory (contains am/conf),
 *    respect it and do NOT auto-append language.
 */
public final class VoskModelInstaller {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String OFFICIAL_BASE = "https://alphacephei.com/vosk/models/";

    private static final ConcurrentHashMap<Path, CompletableFuture<Path>> INFLIGHT = new ConcurrentHashMap<>();

    private VoskModelInstaller() {}

    /**
     * Resolves the base model path from config.
     */
    public static Path resolveBaseModelDir(Path dataDir, String modelPath) {
        if (modelPath == null || modelPath.isBlank()) return dataDir.resolve("vosk/models").normalize();
        Path p = Paths.get(modelPath);
        if (p.isAbsolute()) return p.normalize();
        return dataDir.resolve(p).normalize();
    }


    public static Path resolveModelDir(Path dataDir, VoiceCastConfig.Native nativeCfg) {
        VoiceCastConfig.Native.Vosk v = nativeCfg.vosk;

        Path base = resolveBaseModelDir(dataDir, v.modelPath);

        if (isValidModelDir(base)) return base;

        String langKey = toLangKey(nativeCfg.language);
        return base.resolve(langKey).normalize();
    }

    public static CompletableFuture<Path> ensureReadyAsync(Path dataDir, VoiceCastConfig.Native nativeCfg) {
        VoiceCastConfig.Native.Vosk v = nativeCfg.vosk;
        Path targetDir = resolveModelDir(dataDir, nativeCfg);

        if (!v.enabled) {
            return CompletableFuture.completedFuture(targetDir);
        }

        if (isValidModelDir(targetDir)) {
            return CompletableFuture.completedFuture(targetDir);
        }

        if (!v.autoDownload) {
            return CompletableFuture.completedFuture(targetDir);
        }

        return INFLIGHT.computeIfAbsent(targetDir, _ ->
                CompletableFuture.supplyAsync(() -> ensureModelReadyBlocking(dataDir, nativeCfg))
                        .whenComplete((_, _) -> INFLIGHT.remove(targetDir))
        );
    }

    public static Path ensureModelReadyBlocking(Path dataDir, VoiceCastConfig.Native nativeCfg) {
        VoiceCastConfig.Native.Vosk v = nativeCfg.vosk;
        Path targetDir = resolveModelDir(dataDir, nativeCfg);

        if (isValidModelDir(targetDir)) return targetDir;
        if (!v.enabled || !v.autoDownload) return targetDir;

        String url = pickModelUrl(nativeCfg.language, v.modelUrl);
        if (url.isBlank()) return targetDir;

        try {
            Path downloadsDir = dataDir.resolve("vosk").resolve("_downloads").normalize();
            Files.createDirectories(downloadsDir);

            Files.createDirectories(targetDir.getParent() != null ? targetDir.getParent() : targetDir);

            String fileName = fileNameFromUrl(url);
            if (fileName == null || fileName.isBlank()) fileName = "model.zip";
            Path zipFile = downloadsDir.resolve(fileName).normalize();

            download(url, zipFile, v.connectTimeoutMs, v.downloadTimeoutSeconds);

            Path tempDir = Files.createTempDirectory(downloadsDir, "extract-");
            extractZipFlattening(zipFile, tempDir);

            Path modelRoot = findModelRoot(tempDir);
            if (modelRoot == null) {
                deleteRecursive(tempDir);
                LOGGER.atWarning().log("Could not find model root inside zip (%s)", zipFile);
                return targetDir;
            }

            installReplaceDir(modelRoot, targetDir);

            deleteRecursive(tempDir);

            if (!isValidModelDir(targetDir)) {
                LOGGER.atWarning().log("Installed model invalid at %s", targetDir);
            } else {
                LOGGER.atInfo().log("Model installed at %s", targetDir);
            }

            return targetDir;
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("model auto-download failed");
            return targetDir;
        }
    }

    private static void download(String url, Path outFile, int connectTimeoutMs, int timeoutSeconds)
            throws IOException, InterruptedException {

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(Math.max(1000, connectTimeoutMs)))
                .build();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30, timeoutSeconds)))
                .GET()
                .build();

        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " downloading " + url);
        }

        Path tmp = outFile.resolveSibling(outFile.getFileName().toString() + ".part");
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) >= 0) {
                out.write(buf, 0, r);
            }
        }
        Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Extracts ZIP into extractTo and flattens if there's a single common top-level directory.
     */
    private static void extractZipFlattening(Path zipPath, Path extractTo) throws IOException {
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            String commonTop = commonTopLevelDir(zf);

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e == null) continue;

                String name = e.getName();
                if (name.isBlank()) continue;

                String rel = name.replace('\\', '/');
                while (rel.startsWith("/")) rel = rel.substring(1);
                if (rel.isBlank()) continue;

                if (commonTop != null) {
                    String pfx = commonTop + "/";
                    if (rel.startsWith(pfx)) rel = rel.substring(pfx.length());
                }

                if (rel.isBlank()) continue;

                Path outPath = extractTo.resolve(rel).normalize();
                if (!outPath.startsWith(extractTo.normalize())) {
                    throw new IOException("ZipSlip: " + name);
                }

                if (e.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, outPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    @Nullable
    private static String commonTopLevelDir(ZipFile zf) {
        String top = null;
        Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e == null) continue;

            String name = e.getName();

            name = name.replace('\\', '/');
            if (name.isBlank()) continue;
            if (name.startsWith("/")) name = name.substring(1);

            int idx = name.indexOf('/');
            if (idx <= 0) return null;

            String first = name.substring(0, idx);
            if (top == null) top = first;
            else if (!Objects.equals(top, first)) return null;
        }
        return top;
    }

    @Nullable
    private static Path findModelRoot(Path extractedRoot) throws IOException {
        if (isValidModelDir(extractedRoot)) return extractedRoot;

        try (var stream = Files.walk(extractedRoot, 4)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(VoskModelInstaller::isValidModelDir)
                    .findFirst()
                    .orElse(null);
        }
    }

    private static boolean isValidModelDir(Path dir) {
        try {
            if (dir == null) return false;
            if (!Files.isDirectory(dir)) return false;
            if (!Files.exists(dir.resolve("am"))) return false;
            return Files.exists(dir.resolve("conf"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Installs the content of sourceRoot into targetDir with a safe swap.
     * targetDir will contain am/conf etc.
     */
    private static void installReplaceDir(Path sourceRoot, Path targetDir) throws IOException {
        Path parent = targetDir.getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tmpInstall = Files.createTempDirectory(parent != null ? parent : Paths.get("."), "vosk-install-");

        copyRecursive(sourceRoot, tmpInstall);

        if (!isValidModelDir(tmpInstall)) {
            deleteRecursive(tmpInstall);
            throw new IOException("Extracted model invalid (tmpInstall missing am/conf): " + tmpInstall);
        }

        if (Files.exists(targetDir)) {
            Path backup = targetDir.resolveSibling(targetDir.getFileName().toString() + ".bak");
            deleteRecursive(backup);
            Files.move(targetDir, backup, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursive(backup);
        }

        Files.move(tmpInstall, targetDir, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyRecursive(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @NotNull
            @Override
            public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(dir);
                Files.createDirectories(dst.resolve(rel));
                return FileVisitResult.CONTINUE;
            }
            @NotNull
            @Override
            public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(file);
                Files.copy(file, dst.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @NotNull
            @Override
            public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @NotNull
            @Override
            public FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Nullable
    private static String fileNameFromUrl(String url) {
        int q = url.indexOf('?');
        String u = (q >= 0 ? url.substring(0, q) : url);
        int idx = u.lastIndexOf('/');
        if (idx < 0 || idx == u.length() - 1) return null;
        return u.substring(idx + 1);
    }

    /**
     * Normalizes language to a directory key. Examples:
     *  - "en" / "en-US" / "en_US" -> "en"
     *  - "es" / "es-ES" -> "es"
     *  - null/blank -> "en"
     */
    private static String toLangKey(String language) {
        String lang = (language == null ? "" : language).trim().toLowerCase(Locale.ROOT);
        if (lang.isBlank()) return "en";

        int dash = lang.indexOf('-');
        int under = lang.indexOf('_');
        int cut;
        if (dash >= 0 && under >= 0) cut = Math.min(dash, under);
        else cut = Math.max(dash, under);

        if (cut > 0) lang = lang.substring(0, cut);
        if (lang.isBlank()) return "en";
        return lang;
    }

    private static String pickModelUrl(String language, String overrideUrl) {
        if (overrideUrl != null && !overrideUrl.isBlank()) {
            return overrideUrl.trim();
        }

        String langKey = toLangKey(language);

        String file = switch (langKey) {
            case "es" -> "vosk-model-small-es-0.42.zip";
            case "fr" -> "vosk-model-small-fr-0.22.zip";
            case "de" -> "vosk-model-small-de-0.15.zip";
            case "it" -> "vosk-model-small-it-0.22.zip";
            case "e-in" -> "vosk-model-small-en-in-0.4.zip";
            case "ru" -> "vosk-model-small-ru-0.22.zip";
            //case "pt" -> "vosk-model-small-pt-0.3.zip";
            //case "tr" -> "vosk-model-small-tr-0.3.zip";
            case "vn" -> "vosk-model-small-vn-0.4.zip";
            case "nl" -> "vosk-model-small-nl-0.22.zip";
            case "ca" -> "vosk-model-small-ca-0.4.zip";
            case "jp" -> "vosk-model-small-ja-0.22.zip";
            case "ko" -> "vosk-model-small-ko-0.22.zip";
            default -> "vosk-model-small-en-us-0.15.zip";
        };

        return OFFICIAL_BASE + file;
    }
}