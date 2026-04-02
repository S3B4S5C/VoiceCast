package me.s3b4s5.voicecast.nativevc;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.stream.StreamType;
import com.hypixel.hytale.protocol.packets.voice.VoiceData;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PacketWatcher;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.io.stream.StreamManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastableConfig;
import me.s3b4s5.voicecast.commands.VoiceCastInteractionExecutor;
import me.s3b4s5.voicecast.config.VoiceCastConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class VoiceCastNativeService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final VoiceCastConfig.Native cfg;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VoiceCast-Native");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean running;

    private final ConcurrentHashMap<UUID, SlidingWindowLimiter> limiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UtteranceBuffer> buffers = new ConcurrentHashMap<>();

    private volatile VoiceCastNativeRecognizer recognizer = new NoopRecognizer();
    private volatile PacketFilter inboundHook;

    public VoiceCastNativeService(@Nonnull VoiceCastConfig.Native cfg) {
        this.cfg = cfg;
    }

    public void setRecognizer(@Nonnull VoiceCastNativeRecognizer recognizer) {
        this.recognizer = recognizer;
    }

    public void start() {
        if (running) return;
        running = true;

        boolean hooked = false;

        if (cfg.useHytaleVoiceStream && isVoiceModulePresent()) {
            try {
                StreamManager.getInstance().registerHandler(StreamType.Voice, ph -> new VoiceCastVoiceStreamHandler(ph, this));
                hooked = true;
                LOGGER.atInfo().log("[VoiceCast-Native] started (StreamType.Voice handler)");
            } catch (Throwable t) {
                LOGGER.atWarning().withCause(t).log("[VoiceCast-Native] failed to register StreamType.Voice handler, falling back");
            }
        }

        if (!hooked) {
            this.inboundHook = PacketAdapters.registerInbound((PacketWatcher) (packetHandler, packet) -> {
                if (!running) return;
                if (!(packetHandler instanceof GamePacketHandler)) return;
                if (!(packet instanceof VoiceData vd)) return;
                PlayerIdentity id = PlayerIdentity.from(packetHandler);
                if (id == null) return;
                onVoiceData(id.uuid, id.username, vd);
            });
            LOGGER.atInfo().log("[VoiceCast-Native] started (PacketAdapters inbound hook)");
        }

        scheduler.scheduleAtFixedRate(this::tickSilenceClose, 50, 50, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;

        buffers.clear();
        limiters.clear();

        try { scheduler.shutdownNow(); } catch (Throwable ignored) {}

        PacketFilter hook = this.inboundHook;
        this.inboundHook = null;
        if (hook != null) {
            try {
                PacketAdapters.deregisterInbound(hook);
            } catch (Throwable t) {
                LOGGER.atWarning().withCause(t).log("[VoiceCast-Native] failed to deregister inbound hook");
            }
        }

        LOGGER.atInfo().log("[VoiceCast-Native] stopped");
    }

    static boolean isVoiceModulePresent() {
        try {
            Class.forName("com.hypixel.hytale.server.core.modules.voice.VoiceModule");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    void onVoiceData(@Nonnull UUID uuid, @Nonnull String username, @Nonnull VoiceData voiceData) {
        if (!running) return;

        if (cfg.debugLogPackets) {
            LOGGER.atInfo().log("[VoiceCast-Native] VoiceData from %s/%s seq=%d size=%d ts=%d",
                    username, uuid,
                    (int) voiceData.sequenceNumber,
                    voiceData.opusData.length,
                    voiceData.timestamp
            );
        }

        if (voiceData.opusData.length == 0) return;

        UtteranceBuffer buf = buffers.computeIfAbsent(uuid, u -> new UtteranceBuffer(u, cfg.maxUtteranceMs));
        long now = System.currentTimeMillis();
        buf.push(voiceData.opusData, now);

        if (buf.isExpired(now)) {
            flushUtterance(uuid);
        }
    }

    private void tickSilenceClose() {
        if (!running) return;

        long now = System.currentTimeMillis();
        int silenceMs = Math.max(20, cfg.utteranceSilenceMs);

        for (Map.Entry<UUID, UtteranceBuffer> e : buffers.entrySet()) {
            UtteranceBuffer b = e.getValue();
            if (b == null) continue;

            long last = b.getLastPacketMs();
            if (last > 0 && (now - last) >= silenceMs) {
                flushUtterance(e.getKey());
            }
        }
    }

    private void flushUtterance(@Nonnull UUID uuid) {
        UtteranceBuffer b = buffers.get(uuid);
        if (b == null) return;

        Utterance utt = b.drain();
        if (utt == null || utt.opusBytes.length == 0) return;

        if (cfg.debugLogPackets) {
            LOGGER.atInfo().log("[VoiceCast-Native] flush utterance uuid=%s bytes=%d", uuid, utt.opusBytes.length);
        }

        SlidingWindowLimiter limiter = limiters.computeIfAbsent(uuid,
                _ -> new SlidingWindowLimiter(cfg.maxCastsPerWindow, cfg.castWindowMs));

        CompletableFuture<VoiceCastNativeRecognizer.Result> fut;
        try {
            fut = recognizer.recognize(uuid, utt.opusBytes, cfg.language);
        } catch (Throwable t) {
            LOGGER.atWarning().withCause(t).log("[VoiceCast-Native] recognizer threw");
            return;
        }

        fut.whenComplete((res, err) -> {
            if (err != null) {
                LOGGER.atWarning().withCause(err).log("[VoiceCast-Native] recognizer failed");
                return;
            }
            if (res == null) return;

            if (cfg.debugLogPackets) {
                LOGGER.atInfo().log("[VoiceCast-Native] STT raw='%s' conf=%.3f spell=%s",
                        (res.rawText() == null ? "" : res.rawText()),
                        res.confidence(),
                        (res.spellId() == null ? "" : res.spellId()));
            }

            if (res.confidence() < cfg.minConfidence) return;
            if (res.spellId() == null || res.spellId().isBlank()) return;

            if (!limiter.tryConsume()) {
                LOGGER.atInfo().log("[VoiceCast-Native] rate limited for %s spell=%s", uuid, res.spellId());
                return;
            }

            VoiceCastCastableConfig castable = me.s3b4s5.voicecast.web.services.SpellRegistry.get(res.spellId());
            if (castable == null) {
                LOGGER.atInfo().log("[VoiceCast-Native] spell not found: %s", res.spellId());
                return;
            }

            String rootId = castable.rootInteractionId;
            if (rootId == null || rootId.isBlank()) {
                LOGGER.atWarning().log("[VoiceCast-Native] spell has no rootInteraction: %s", res.spellId());
                return;
            }

            boolean queued = VoiceCastInteractionExecutor.executeForPlayerUuid(
                    uuid.toString(),
                    rootId,
                    com.hypixel.hytale.protocol.InteractionType.Ability3,
                    castable.requiredItemId,
                    castable.consumedItem
            );

            if (cfg.debugLogPackets) {
                LOGGER.atInfo().log("[VoiceCast-Native] cast %s -> %s queued=%s", res.spellId(), rootId, queued);
            }
        });
    }

    private record PlayerIdentity(UUID uuid, String username) {

        @Nullable
            static PlayerIdentity from(@Nonnull PacketHandler handler) {
                if (handler instanceof GamePacketHandler gph) {
                    PlayerRef pr = gph.getPlayerRef();
                    return new PlayerIdentity(pr.getUuid(), pr.getUsername());
                }
                return null;
            }
        }

    private static final class UtteranceBuffer {
        final UUID uuid;
        final int maxUtteranceMs;

        private long startMs = 0;
        private long lastPacketMs = 0;

        private final ByteArrayOutputStreamEx out = new ByteArrayOutputStreamEx(4096);

        UtteranceBuffer(UUID uuid, int maxUtteranceMs) {
            this.uuid = uuid;
            this.maxUtteranceMs = Math.max(200, maxUtteranceMs);
        }

        synchronized void push(byte[] opusBytes, long now) {
            if (opusBytes == null || opusBytes.length == 0) return;
            if (startMs == 0) startMs = now;
            lastPacketMs = now;
            out.writeVarInt(opusBytes.length);
            out.write(opusBytes);
        }

        synchronized long getLastPacketMs() { return lastPacketMs; }

        synchronized boolean isExpired(long now) {
            return startMs > 0 && (now - startMs) >= maxUtteranceMs;
        }

        @Nullable
        synchronized Utterance drain() {
            byte[] data = out.toByteArrayAndReset();
            if (data.length == 0) {
                startMs = 0;
                lastPacketMs = 0;
                return null;
            }
            Utterance utt = new Utterance(uuid, data, startMs, lastPacketMs);
            startMs = 0;
            lastPacketMs = 0;
            return utt;
        }
    }

    private record Utterance(UUID uuid, byte[] opusBytes, long startMs, long endMs) {
    }

    private static final class SlidingWindowLimiter {
        private final int max;
        private final long windowMs;
        private final ConcurrentLinkedDeque<Long> times = new ConcurrentLinkedDeque<>();

        SlidingWindowLimiter(int max, long windowMs) {
            this.max = Math.max(1, max);
            this.windowMs = Math.max(50, windowMs);
        }

        boolean tryConsume() {
            long now = System.currentTimeMillis();
            long cutoff = now - windowMs;

            while (true) {
                Long t = times.peekFirst();
                if (t == null || t >= cutoff) break;
                times.pollFirst();
            }

            if (times.size() >= max) return false;
            times.addLast(now);
            return true;
        }
    }

    private static final class ByteArrayOutputStreamEx {
        private byte[] buf;
        private int len;

        ByteArrayOutputStreamEx(int initial) {
            buf = new byte[Math.max(256, initial)];
        }

        void write(byte[] b) {
            ensure(len + b.length);
            System.arraycopy(b, 0, buf, len, b.length);
            len += b.length;
        }

        void writeByte(int v) {
            ensure(len + 1);
            buf[len++] = (byte) (v & 0xFF);
        }

        void writeVarInt(int value) {
            int v = value;
            while ((v & ~0x7F) != 0) {
                writeByte((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            writeByte(v);
        }

        private void ensure(int needed) {
            if (needed <= buf.length) return;
            int n = buf.length;
            while (n < needed) n = n * 2;
            byte[] nb = new byte[n];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }

        byte[] toByteArrayAndReset() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            len = 0;
            return out;
        }
    }

    private static final class NoopRecognizer implements VoiceCastNativeRecognizer {
        @Override
        public CompletableFuture<Result> recognize(UUID playerUuid, byte[] opusBytes, String language) {
            return CompletableFuture.completedFuture(new Result(null, 0.0f, null));
        }
    }
}