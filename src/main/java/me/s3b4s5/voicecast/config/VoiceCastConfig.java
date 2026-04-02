package me.s3b4s5.voicecast.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;

import javax.annotation.Nonnull;

public class VoiceCastConfig {

    public enum Mode {
        Web,
        Native,
        Both;

        public boolean useWeb() { return this == Web || this == Both; }
        public boolean useNative() { return this == Native || this == Both; }
    }

    @Nonnull public Mode mode = Mode.Native;

    @Nonnull public Web web = new Web();
    @Nonnull public Native native_ = new Native();

    public static class Web {
        public boolean enabled = false;
        public String bindHost = "0.0.0.0";
        public int port = 3010;
        public String publicBaseUrl = "http://127.0.0.1:3010";
    }

    public static class Native {
        public boolean enabled = true;
        public boolean useHytaleVoiceStream = true;
        public String requireHoldInteractionType = "";
        public int maxCastsPerWindow = 10;
        public int castWindowMs = 5000;

        public String language = "en";

        public float minConfidence = 0.50f;
        public int maxUtteranceMs = 4500;
        public int utteranceSilenceMs = 650;

        public boolean debugLogPackets = false;
        @Nonnull public Vosk vosk = new Vosk();

        public static class Vosk {
            public boolean enabled = true;

            public boolean autoDownload = true;

            public String modelUrl = "";

            public String modelPath = "vosk/model";

            public int sampleRate = 16000;
            public int workerThreads = 2;

            public boolean allowFuzzyMatch = true;
            public int maxTextChars = 160;

            public int connectTimeoutMs = 20000;
            public int downloadTimeoutSeconds = 1200;
        }
    }

    public static final BuilderCodec<Web> WEB_CODEC = BuilderCodec.builder(Web.class, Web::new)
            .documentation("Web server settings for VoiceCast (embedded HTTP server).")

            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (o, v) -> o.enabled = (v != null ? v : false),
                    o -> o.enabled)
            .addValidator(Validators.nonNull())
            .add()

            .append(new KeyedCodec<>("BindHost", Codec.STRING),
                    (o, v) -> o.bindHost = (v != null && !v.isBlank() ? v : "0.0.0.0"),
                    o -> o.bindHost)
            .addValidator(Validators.nonNull())
            .add()

            .append(new KeyedCodec<>("Port", Codec.INTEGER),
                    (o, v) -> o.port = (v != null ? v : 3010),
                    o -> o.port)
            .addValidator(Validators.nonNull())
            .addValidator(Validators.greaterThan(1))
            .addValidator(Validators.lessThan(65535))
            .add()

            .append(new KeyedCodec<>("PublicBaseUrl", Codec.STRING),
                    (o, v) -> o.publicBaseUrl = (v != null && !v.isBlank() ? v : "http://127.0.0.1:3010"),
                    o -> o.publicBaseUrl)
            .addValidator(Validators.nonNull())
            .add()

            .build();

    public static final BuilderCodec<Native.Vosk> NATIVE_VOSK_CODEC =
            BuilderCodec.builder(Native.Vosk.class, Native.Vosk::new)
                    .documentation("Vosk STT settings (offline).")

                    .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                            (o, v) -> o.enabled = (v != null ? v : true),
                            o -> o.enabled)
                    .addValidator(Validators.nonNull())
                    .add()

                    .append(new KeyedCodec<>("AutoDownload", Codec.BOOLEAN),
                            (o, v) -> o.autoDownload = (v != null ? v : true),
                            o -> o.autoDownload)
                    .addValidator(Validators.nonNull())
                    .add()

                    .append(new KeyedCodec<>("ModelUrl", Codec.STRING),
                            (o, v) -> o.modelUrl = (v != null ? v : ""),
                            o -> o.modelUrl)
                    .addValidator(Validators.nonNull())
                    .add()

                    .append(new KeyedCodec<>("ModelPath", Codec.STRING),
                            (o, v) -> o.modelPath = (v != null && !v.isBlank() ? v : "vosk/model"),
                            o -> o.modelPath)
                    .addValidator(Validators.nonNull())
                    .add()

                    .append(new KeyedCodec<>("SampleRate", Codec.INTEGER),
                            (o, v) -> o.sampleRate = (v != null ? v : 16000),
                            o -> o.sampleRate)
                    .addValidator(Validators.nonNull())
                    .addValidator(Validators.greaterThan(1000))
                    .add()

                    .append(new KeyedCodec<>("WorkerThreads", Codec.INTEGER),
                            (o, v) -> o.workerThreads = (v != null ? v : 2),
                            o -> o.workerThreads)
                    .addValidator(Validators.nonNull())
                    .addValidator(Validators.greaterThan(0))
                    .add()

                    .append(new KeyedCodec<>("AllowFuzzyMatch", Codec.BOOLEAN),
                            (o, v) -> o.allowFuzzyMatch = (v != null ? v : true),
                            o -> o.allowFuzzyMatch)
                    .addValidator(Validators.nonNull())
                    .add()

                    .append(new KeyedCodec<>("MaxTextChars", Codec.INTEGER),
                            (o, v) -> o.maxTextChars = (v != null ? v : 160),
                            o -> o.maxTextChars)
                    .addValidator(Validators.nonNull())
                    .addValidator(Validators.greaterThan(10))
                    .add()

                    .append(new KeyedCodec<>("ConnectTimeoutMs", Codec.INTEGER),
                            (o, v) -> o.connectTimeoutMs = (v != null ? v : 20000),
                            o -> o.connectTimeoutMs)
                    .addValidator(Validators.nonNull())
                    .addValidator(Validators.greaterThan(0))
                    .add()

                    .append(new KeyedCodec<>("DownloadTimeoutSeconds", Codec.INTEGER),
                            (o, v) -> o.downloadTimeoutSeconds = (v != null ? v : 1200),
                            o -> o.downloadTimeoutSeconds)
                    .addValidator(Validators.nonNull())
                    .addValidator(Validators.greaterThan(10))
                    .add()

                    .build();

    public static final BuilderCodec<Native> NATIVE_CODEC = BuilderCodec.builder(Native.class, Native::new)
            .documentation("Native voice settings (Vosk).")

            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (o, v) -> o.enabled = (v != null ? v : true),
                    o -> o.enabled)
            .addValidator(Validators.nonNull())
            .add()

            .append(new KeyedCodec<>("UseHytaleVoiceStream", Codec.BOOLEAN),
                    (o, v) -> o.useHytaleVoiceStream = (v != null ? v : true),
                    o -> o.useHytaleVoiceStream)
            .addValidator(Validators.nonNull())
            .add()

            .append(new KeyedCodec<>("RequireHoldInteractionType", Codec.STRING),
                    (o, v) -> o.requireHoldInteractionType = (v != null ? v : ""),
                    o -> o.requireHoldInteractionType)
            .addValidator(Validators.nonNull())
            .add()

            .append(new KeyedCodec<>("MaxCastsPerWindow", Codec.INTEGER),
                    (o, v) -> o.maxCastsPerWindow = (v != null ? v : 10),
                    o -> o.maxCastsPerWindow)
            .addValidator(Validators.nonNull())
            .addValidator(Validators.greaterThan(0))
            .add()

            .append(new KeyedCodec<>("CastWindowMs", Codec.INTEGER),
                    (o, v) -> o.castWindowMs = (v != null ? v : 5000),
                    o -> o.castWindowMs)
            .addValidator(Validators.nonNull())
            .addValidator(Validators.greaterThan(50))
            .add()

            .append(new KeyedCodec<>("Language", Codec.STRING),
                    (o, v) -> o.language = (v != null && !v.isBlank() ? v : "en"),
                    o -> o.language)
            .addValidator(Validators.nonNull())
            .add()

            .append(new KeyedCodec<>("MinConfidence", Codec.FLOAT),
                    (o, v) -> o.minConfidence = (v != null ? v : 0.50f),
                    o -> o.minConfidence)
            .addValidator(Validators.nonNull())
            .addValidator(Validators.greaterThanOrEqual(0.0f))
            .addValidator(Validators.lessThan(1.1f))
            .add()

            .append(new KeyedCodec<>("MaxUtteranceMs", Codec.INTEGER),
                    (o, v) -> o.maxUtteranceMs = (v != null ? v : 4500),
                    o -> o.maxUtteranceMs)
            .addValidator(Validators.nonNull())
            .addValidator(Validators.greaterThan(100))
            .add()

            .append(new KeyedCodec<>("UtteranceSilenceMs", Codec.INTEGER),
                    (o, v) -> o.utteranceSilenceMs = (v != null ? v : 650),
                    o -> o.utteranceSilenceMs)
            .addValidator(Validators.nonNull())
            .addValidator(Validators.greaterThan(20))
            .add()

            .append(new KeyedCodec<>("DebugLogPackets", Codec.BOOLEAN),
                    (o, v) -> o.debugLogPackets = (v != null ? v : false),
                    o -> o.debugLogPackets)
            .addValidator(Validators.nonNull())
            .add()

            .append(new KeyedCodec<>("Vosk", NATIVE_VOSK_CODEC),
                    (o, v) -> o.vosk = (v != null ? v : new Native.Vosk()),
                    o -> o.vosk)
            .addValidator(Validators.nonNull())
            .add()

            .build();

    public static final BuilderCodec<VoiceCastConfig> CODEC = BuilderCodec.builder(VoiceCastConfig.class, VoiceCastConfig::new)
            .documentation("VoiceCast configuration root.")

            .append(new KeyedCodec<>("Mode", Codec.STRING),
                    (o, v) -> {
                        String s = (v == null ? "Native" : v.trim());
                        try { o.mode = Mode.valueOf(s); }
                        catch (Throwable ignored) { o.mode = Mode.Native; }
                    },
                    o -> o.mode.name())
            .addValidator(Validators.nonNull())
            .add()

            .append(new KeyedCodec<>("Web", WEB_CODEC),
                    (o, v) -> o.web = (v != null ? v : new Web()),
                    o -> o.web)
            .addValidator(Validators.nonNull())
            .add()

            .append(new KeyedCodec<>("Native", NATIVE_CODEC),
                    (o, v) -> o.native_ = (v != null ? v : new Native()),
                    o -> o.native_)
            .addValidator(Validators.nonNull())
            .add()

            .build();
}