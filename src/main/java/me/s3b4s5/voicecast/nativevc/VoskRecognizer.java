package me.s3b4s5.voicecast.nativevc;

import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import io.github.jaredmdobson.concentus.OpusDecoder;
import me.s3b4s5.voicecast.VoiceCast;
import me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastable;
import me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastableConfig;
import me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguage;
import me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguageConfig;
import me.s3b4s5.voicecast.config.VoiceCastConfig;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VoskRecognizer implements VoiceCastNativeRecognizer, AutoCloseable {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final VoiceCastConfig.Native cfg;
    private final VoiceCastConfig.Native.Vosk vosk;
    private final Path dataDir;

    private final ExecutorService exec;
    private volatile Model model;

    public VoskRecognizer(Path dataDir, VoiceCastConfig.Native cfg) {
        this.dataDir = dataDir;
        this.cfg = cfg;
        this.vosk = cfg.vosk;

        int threads = Math.max(1, this.vosk.workerThreads);
        this.exec = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "VoiceCast-Vosk");
            t.setDaemon(true);
            return t;
        });

        if (isDebug()) {
            LOGGER.atInfo().log("[VoiceCast-Vosk] created | dataDir=%s | modelPath=%s | sampleRate=%d | workers=%d",
                    dataDir, this.vosk.modelPath, this.vosk.sampleRate, threads);
        }
    }

    @Override
    public CompletableFuture<Result> recognize(UUID playerUuid, byte[] opusBytes, String language) {
        if (vosk == null || !vosk.enabled) {
            return CompletableFuture.completedFuture(new Result(null, 0f, null));
        }

        final long startNs = System.nanoTime();

        return CompletableFuture.supplyAsync(() -> {
            try {
                Model m = ensureModelLoaded();
                if (m == null) return new Result(null, 0f, null);

                List<byte[]> packets = unpackLengthPrefixed(opusBytes);
                if (packets.isEmpty()) return new Result(null, 0f, null);

                int srcRate = 48000;
                int channels = 1;
                short[] pcm48 = decodeOpusToPcm(packets, srcRate, channels);
                if (pcm48.length == 0) return new Result(null, 0f, null);

                int targetRate = Math.max(8000, vosk.sampleRate);
                short[] pcm = (srcRate == targetRate) ? pcm48 : downsample48kTo16k(pcm48, srcRate, targetRate);
                if (pcm.length == 0) return new Result(null, 0f, null);

                byte[] audioBytes = shortsToLittleEndianBytes(pcm);

                String json;
                try (Recognizer rec = new Recognizer(m, targetRate)) {
                    rec.acceptWaveForm(audioBytes, audioBytes.length);
                    json = rec.getFinalResult();
                }

                String text = extractJsonText(json);
                if (text == null) text = "";
                text = text.trim();

                if (vosk.maxTextChars > 0 && text.length() > vosk.maxTextChars) {
                    text = text.substring(0, vosk.maxTextChars);
                }

                float conf = extractAverageConfidence(json);
                if (conf <= 0f) conf = 0.75f;

                if (text.isBlank()) {
                    return new Result(null, 0f, "");
                }

                MappedSpell mapped = mapTextToSpell(text, vosk.allowFuzzyMatch);
                if (mapped == null) {
                    return new Result(null, conf, text);
                }

                float finalConf = Math.clamp(mapped.confidence * conf, 0f, 1f);

                if (isDebug()) {
                    long totalMs = (System.nanoTime() - startNs) / 1_000_000L;
                    LOGGER.atInfo().log("[VoiceCast-Vosk] recognize() total=%dms | player=%s spell=%s conf=%.3f text='%s'",
                            totalMs, playerUuid, mapped.spellId, finalConf, text);
                }

                return new Result(mapped.spellId, finalConf, text);

            } catch (UnsatisfiedLinkError ule) {
                VoiceCast.LOGGER.atWarning().withCause(ule).log("[VoiceCast-Vosk] native load failed (UnsatisfiedLinkError).");
                return new Result(null, 0f, null);
            } catch (Throwable t) {
                VoiceCast.LOGGER.atWarning().withCause(t).log("[VoiceCast-Vosk] recognizer failed");
                return new Result(null, 0f, null);
            }
        }, exec);
    }

    private boolean isDebug() {
        return cfg.debugLogPackets;
    }

    private Model ensureModelLoaded() {
        Model m = this.model;
        if (m != null) return m;

        Path p = resolveModelPath();

        if (!Files.exists(p) || !Files.isDirectory(p)) {
            LOGGER.atWarning().log("[VoiceCast-Vosk] modelPath not found/dir: %s", p);
            return null;
        }

        if (!Files.exists(p.resolve("am")) || !Files.exists(p.resolve("conf"))) {
            LOGGER.atWarning().log("[VoiceCast-Vosk] model dir looks wrong (missing am/conf): %s", p);
        }

        LOGGER.atInfo().log("[VoiceCast-Vosk] loading model from %s", p);
        loadModel(p);
        LOGGER.atInfo().log("[VoiceCast-Vosk] model loaded OK");
        return this.model;
    }

    public void loadModel(Path p) {
        try {
            this.model = new Model(p.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Vosk model from: " + p, e);
        }
    }

    private Path resolveModelPath() {
        return VoskModelInstaller.resolveModelDir(this.dataDir, this.cfg);
    }

    @Override
    public void close() {
        try { exec.shutdownNow(); } catch (Throwable ignored) {}
        this.model = null;
    }

    private static List<byte[]> unpackLengthPrefixed(byte[] data) throws IOException {
        ArrayList<byte[]> out = new ArrayList<>();
        if (data == null || data.length == 0) return out;

        ByteArrayInputStream in = new ByteArrayInputStream(data);
        while (in.available() > 0) {
            int len = readVarInt(in);
            if (len <= 0 || len > 4096) break;
            byte[] b = in.readNBytes(len);
            if (b.length != len) break;
            out.add(b);
        }
        return out;
    }

    private static int readVarInt(InputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        int read;
        do {
            read = in.read();
            if (read == -1) return -1;
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) throw new IOException("VarInt too long");
        } while ((read & 0x80) != 0);
        return result;
    }

    private static short[] decodeOpusToPcm(List<byte[]> packets, int sampleRate, int channels) throws Exception {
        OpusDecoder dec = new OpusDecoder(sampleRate, channels);

        int maxFrame = 5760 * channels;
        short[] frame = new short[maxFrame];

        ShortArrayOutput out = new ShortArrayOutput(48000);

        for (byte[] pkt : packets) {
            if (pkt == null || pkt.length == 0) continue;
            int decoded = dec.decode(pkt, 0, pkt.length, frame, 0, 5760, false);
            int total = decoded * channels;
            if (total > 0) out.write(frame, total);
        }

        return out.toArray();
    }

    private static short[] downsample48kTo16k(short[] pcm48, int srcRate, int dstRate) {
        if (srcRate == 48000 && dstRate == 16000) {
            int n = pcm48.length / 3;
            short[] out = new short[n];
            for (int i = 0; i < n; i++) out[i] = pcm48[i * 3];
            return out;
        }

        double ratio = (double) dstRate / (double) srcRate;
        int n = (int) Math.max(1, Math.floor(pcm48.length * ratio));
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            int srcIndex = (int) Math.min(pcm48.length - 1, Math.round(i / ratio));
            out[i] = pcm48[srcIndex];
        }
        return out;
    }

    private static byte[] shortsToLittleEndianBytes(short[] pcm) {
        byte[] out = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            short s = pcm[i];
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
        }
        return out;
    }

    private static final Pattern CONF_PATTERN = Pattern.compile("\"conf\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private static float extractAverageConfidence(String json) {
        if (json == null || json.isBlank()) return 0f;
        Matcher m = CONF_PATTERN.matcher(json);
        float sum = 0f;
        int count = 0;
        while (m.find()) {
            try {
                sum += Float.parseFloat(m.group(1));
                count++;
            } catch (Throwable ignored) {}
        }
        return count > 0 ? (sum / count) : 0f;
    }

    private static String extractJsonText(String json) {
        return extractJsonString(json);
    }

    private static String extractJsonString(String json) {
        if (json == null) return null;

        String needle = "\"" + "text" + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;

        i = json.indexOf(':', i + needle.length());
        if (i < 0) return null;

        do i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i)));

        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++;

        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (esc) { sb.append(c); esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    private record MappedSpell(String spellId, float confidence) {
    }

    @Nullable
    private static MappedSpell mapTextToSpell(String raw, boolean fuzzy) {
        String norm = normalize(raw);
        if (norm.isEmpty()) return null;

        List<CastableAliases> all = snapshotCastablesWithAliases();

        String bestId = null;
        float best = 0f;

        for (CastableAliases ca : all) {
            if (ca.id == null || ca.id.isBlank()) continue;

            for (String a : ca.aliases) {
                String an = normalize(a);
                if (an.isEmpty()) continue;

                if (norm.equals(an)) {
                    return new MappedSpell(ca.id, 0.95f);
                }

                if (fuzzy) {
                    if (norm.startsWith(an) || norm.contains(an)) {
                        float score = 0.75f;
                        if (an.length() >= 6) score += 0.05f;
                        if (score > best) { best = score; bestId = ca.id; }
                    }
                }
            }
        }

        return bestId != null ? new MappedSpell(bestId, best) : null;
    }

    private record CastableAliases(String id, List<String> aliases) {
    }

    private static List<CastableAliases> snapshotCastablesWithAliases() {
        ArrayList<CastableAliases> out = new ArrayList<>();

        IndexedLookupTableAssetMap<String, VoiceCastCastable> castableMap =
                VoiceCastCastable.getAssetStore().getAssetMap();

        var langMap = VoiceCastLanguage.getAssetMap();

        for (var e : castableMap.getAssetMap().entrySet()) {
            VoiceCastCastable v = e.getValue();
            if (!(v instanceof VoiceCastCastableConfig castable)) continue;
            if (castable.isUnknown()) continue;

            String id = castable.getId();
            if (id.isBlank()) continue;

            ArrayList<String> aliases = new ArrayList<>();
            aliases.add(id);

            String[] langIds = castable.languageIds;
            if (langIds != null) {
                for (String langId : langIds) {
                    if (langId == null || langId.isBlank()) continue;

                    var langBase = langMap.getAsset(langId);
                    if (!(langBase instanceof VoiceCastLanguageConfig lang)) continue;
                    addAliases(aliases, lang);
                }
            }

            out.add(new CastableAliases(id, aliases));
        }

        out.sort(Comparator.comparing(a -> a.id));
        return out;
    }

    public static void addAliases(ArrayList<String> aliases, VoiceCastLanguageConfig lang) {
        if (lang.isUnknown()) return;

        String[] values = lang.values;
        if (values == null) return;

        for (String val : values) {
            if (val == null || val.isBlank()) continue;
            aliases.add(val);
        }
    }

    private static String normalize(String s) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.ROOT).trim();
        s = s.replaceAll("[^\\p{L}\\p{Nd} ]+", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static final class ShortArrayOutput {
        private short[] buf;
        private int len;

        ShortArrayOutput(int initial) { buf = new short[Math.max(256, initial)]; }

        void write(short[] b, int count) {
            ensure(len + count);
            System.arraycopy(b, 0, buf, len, count);
            len += count;
        }

        private void ensure(int needed) {
            if (needed <= buf.length) return;
            int n = buf.length;
            while (n < needed) n *= 2;
            buf = Arrays.copyOf(buf, n);
        }

        short[] toArray() { return Arrays.copyOf(buf, len); }
    }
}