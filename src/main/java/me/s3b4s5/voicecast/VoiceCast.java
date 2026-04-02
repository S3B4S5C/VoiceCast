package me.s3b4s5.voicecast;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;
import lombok.Getter;
import me.s3b4s5.voicecast.assets.store.VoiceCastCastableStore;
import me.s3b4s5.voicecast.assets.store.VoiceCastLanguageStore;
import me.s3b4s5.voicecast.commands.VoiceCastCommand;
import me.s3b4s5.voicecast.config.VoiceCastConfig;
import me.s3b4s5.voicecast.nativevc.VoiceCastNativeService;
import me.s3b4s5.voicecast.nativevc.VoskModelInstaller;
import me.s3b4s5.voicecast.nativevc.VoskRecognizer;
import me.s3b4s5.voicecast.web.VoiceCastWebServer;
import me.s3b4s5.voicecast.web.handlers.ApiHandlers;
import me.s3b4s5.voicecast.web.services.SessionService;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.nio.file.Path;

public class VoiceCast extends JavaPlugin {

    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final JavaPluginInit init;

    private Config<VoiceCastConfig> configFile;
    private volatile VoiceCastConfig cfg;
    @Getter
    private volatile String publicBaseUrl;

    private volatile VoiceCastWebServer web;
    @Getter
    private volatile SessionService sessions;

    private volatile VoiceCastNativeService nativeService;
    private volatile VoskRecognizer voskRecognizer;
    @Getter
    private volatile boolean nativeStarted = false;

    public VoiceCast(@Nonnull JavaPluginInit init) {
        super(init);
        this.init = init;
        LOGGER.atInfo().log("VoiceCast constructed");
    }

    public VoiceCastWebServer getWebServer() { return web; }

    @Override
    protected void setup() {
        getCommandRegistry().registerCommand(new VoiceCastCommand(this));

        me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguage.CODEC
                .register(
                        me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguageConfig.ASSET_TYPE_ID,
                        me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguageConfig.class,
                        me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguageConfig.ABSTRACT_CODEC
                );

        me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastable.CODEC
                .register(
                        me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastableConfig.ASSET_TYPE_ID,
                        me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastableConfig.class,
                        me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastableConfig.ABSTRACT_CODEC
                );

        getAssetRegistry().register(VoiceCastLanguageStore.create());
        getAssetRegistry().register(VoiceCastCastableStore.create());

        try {
            Path dataDir = init.getDataDirectory();
            this.configFile = new Config<>(dataDir, "VoiceCast", VoiceCastConfig.CODEC);

            this.configFile.load()
                    .thenCompose(loaded -> {
                        this.cfg = loaded;
                        return this.configFile.save();
                    })
                    .thenRun(() -> startFromConfig(dataDir))
                    .exceptionally(err -> {
                        LOGGER.atSevere().withCause(err).log("VoiceCast failed to load/save config");
                        return null;
                    });

        } catch (Throwable t) {
            LOGGER.atSevere().withCause(t).log("VoiceCast setup failed");
        }
    }

    private void startFromConfig(Path dataDir) {
        VoiceCastConfig cfgLocal = this.cfg;
        if (cfgLocal == null && this.configFile != null) {
            try { cfgLocal = this.configFile.get(); } catch (Throwable ignored) {}
        }
        if (cfgLocal == null) {
            LOGGER.atSevere().log("VoiceCast config missing after load()");
            return;
        }

        LOGGER.atInfo().log("[VoiceCast] Mode=%s (web=%s, native=%s)",
                cfgLocal.mode, cfgLocal.mode.useWeb(), cfgLocal.mode.useNative());

        if (cfgLocal.mode.useWeb() && cfgLocal.web.enabled) {
            startWeb(cfgLocal, dataDir);
        } else {
            LOGGER.atInfo().log("[VoiceCast] web disabled by mode/config");
        }

        if (cfgLocal.mode.useNative() && cfgLocal.native_.enabled) {
            startNative(cfgLocal, dataDir);
        } else {
            LOGGER.atInfo().log("[VoiceCast] native disabled by mode/config");
        }
    }

    private void startWeb(VoiceCastConfig cfgLocal, Path dataDir) {
        this.sessions = new SessionService(120, 12 * 60 * 60);
        this.publicBaseUrl = cfgLocal.web.publicBaseUrl;

        ApiHandlers api = new ApiHandlers(this.sessions, this.publicBaseUrl);

        this.web = new VoiceCastWebServer(api);
        InetSocketAddress bound = this.web.start(cfgLocal.web.bindHost, cfgLocal.web.port);

        LOGGER.atInfo().log(
                "[VoiceCast] web started bind=%s:%d | publicBaseUrl=%s | config=%s",
                bound.getHostString(), bound.getPort(),
                cfgLocal.web.publicBaseUrl,
                dataDir.resolve("VoiceCast.json")
        );
    }

    private void startNative(VoiceCastConfig cfgLocal, Path dataDir) {
        LOGGER.atInfo().log(
                "[VoiceCast] native starting | useHytaleVoiceStream=%s requireHold=%s minConf=%.2f maxUtteranceMs=%d silenceMs=%d rate=%d/%dms",
                cfgLocal.native_.useHytaleVoiceStream,
                (cfgLocal.native_.requireHoldInteractionType == null ? "" : cfgLocal.native_.requireHoldInteractionType),
                cfgLocal.native_.minConfidence,
                cfgLocal.native_.maxUtteranceMs,
                cfgLocal.native_.utteranceSilenceMs,
                cfgLocal.native_.maxCastsPerWindow,
                cfgLocal.native_.castWindowMs
        );

        this.nativeService = new VoiceCastNativeService(cfgLocal.native_);

        this.nativeService.start();
        this.nativeStarted = true;

        if (!cfgLocal.native_.vosk.enabled) {
            LOGGER.atInfo().log("[VoiceCast] native recognizer = NOOP (Vosk disabled)");
            return;
        }

        LOGGER.atInfo().log(
                "[VoiceCast] Vosk enabled | autoDownload=%s lang=%s modelPath=%s modelUrl=%s",
                cfgLocal.native_.vosk.autoDownload,
                cfgLocal.native_.language,
                cfgLocal.native_.vosk.modelPath,
                (cfgLocal.native_.vosk.modelUrl == null ? "" : cfgLocal.native_.vosk.modelUrl)
        );

        VoskModelInstaller.ensureReadyAsync(dataDir, cfgLocal.native_)
                .thenRun(() -> {
                    if (!nativeStarted || nativeService == null) return;

                    this.voskRecognizer = new VoskRecognizer(dataDir, cfgLocal.native_);
                    this.nativeService.setRecognizer(this.voskRecognizer);

                    LOGGER.atInfo().log("[VoiceCast] Vosk model ready -> recognizer enabled (path=%s)",
                            VoskModelInstaller.resolveModelDir(dataDir, cfgLocal.native_));
                })
                .exceptionally(err -> {
                    LOGGER.atWarning().withCause(err).log("[VoiceCast] Vosk model setup failed (keeping NOOP)");
                    return null;
                });

        LOGGER.atInfo().log("[VoiceCast] native started | config=%s", dataDir.resolve("VoiceCast.json"));
    }

    @Override
    protected void shutdown() {
        VoiceCastWebServer w = this.web;
        this.web = null;
        if (w != null) {
            try { w.stop(); }
            catch (Throwable t) {
                LOGGER.atWarning().withCause(t).log("VoiceCast failed to stop web server cleanly");
            }
        }

        VoiceCastNativeService ns = this.nativeService;
        this.nativeService = null;
        if (ns != null) {
            try { ns.stop(); }
            catch (Throwable t) {
                LOGGER.atWarning().withCause(t).log("VoiceCast failed to stop native cleanly");
            }
        }
        this.nativeStarted = false;

        VoskRecognizer vr = this.voskRecognizer;
        this.voskRecognizer = null;
        if (vr != null) {
            try { vr.close(); }
            catch (Throwable t) {
                LOGGER.atWarning().withCause(t).log("VoiceCast failed to close VoskRecognizer cleanly");
            }
        }

        this.sessions = null;
        LOGGER.atInfo().log("VoiceCast shutdown - web/native stopped");
    }
}