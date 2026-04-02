package me.s3b4s5.voicecast.web.handlers;

import com.sun.net.httpserver.HttpExchange;
import me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastableConfig;
import me.s3b4s5.voicecast.commands.VoiceCastInteractionExecutor;
import me.s3b4s5.voicecast.nativevc.VoskRecognizer;
import me.s3b4s5.voicecast.web.http.HttpUtil;
import me.s3b4s5.voicecast.web.services.SessionService;
import me.s3b4s5.voicecast.web.services.SpellRegistry;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

public final class ApiHandlers {

    private final SessionService sessions;
    private final String publicBaseUrl;

    public ApiHandlers(SessionService sessions, String publicBaseUrl) {
        this.sessions = sessions;
        this.publicBaseUrl = publicBaseUrl;
    }

    public void health(HttpExchange ex) throws IOException {
        HttpUtil.text(ex, 200, "ok");
    }

    public void info(HttpExchange ex) throws IOException {
        HttpUtil.json(ex, 200, Map.of(
                "ok", true,
                "time", Instant.now().toString(),
                "publicBaseUrl", publicBaseUrl
        ));
    }

    public void connect(HttpExchange ex) throws IOException {
        if (HttpUtil.isNotPost(ex)) {
            HttpUtil.methodNotAllowed(ex);
            return;
        }

        try {
            Map<String, Object> body = HttpUtil.readJsonObject(ex);
            String code = HttpUtil.getString(body, "code");

            if (code == null || code.isBlank()) {
                HttpUtil.json(ex, 400, Map.of("ok", false, "error", "missing_code"));
                return;
            }

            SessionService.ConnectResult r = sessions.connectWithCode(code.trim());
            if (!r.ok()) {
                HttpUtil.json(ex, 400, Map.of("ok", false, "error", r.error()));
                return;
            }

            HttpUtil.json(ex, 200, Map.of(
                    "ok", true,
                    "token", r.token(),
                    "playerUuid", r.playerUuid(),
                    "expiresInSeconds", r.expiresInSeconds()
            ));
        } catch (Throwable t) {
            HttpUtil.json(ex, 500, Map.of("ok", false, "error", "internal_error"));
        }
    }

    public void disconnect(HttpExchange ex) throws IOException {
        if (HttpUtil.isNotPost(ex)) {
            HttpUtil.methodNotAllowed(ex);
            return;
        }

        try {
            String token = HttpUtil.getBearerToken(ex);
            if (token == null) {
                HttpUtil.json(ex, 401, Map.of("ok", false, "error", "missing_token"));
                return;
            }

            sessions.disconnect(token);
            HttpUtil.json(ex, 200, Map.of("ok", true));
        } catch (Throwable t) {
            HttpUtil.json(ex, 500, Map.of("ok", false, "error", "internal_error"));
        }
    }

    public void spells(HttpExchange ex) throws IOException {
        try {
            var map = me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastable.getAssetStore().getAssetMap();
            var out = new ArrayList<Map<String, Object>>();

            for (var e : map.getAssetMap().entrySet()) {
                var v = e.getValue();
                if (!(v instanceof me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastableConfig castable)) continue;
                if (castable.isUnknown()) continue;

                String id = castable.getId();
                if (id.isBlank()) continue;

                var aliases = new ArrayList<String>();
                aliases.add(id);

                String[] langIds = castable.languageIds;
                if (langIds != null) {
                    for (String langId : langIds) {
                        if (langId == null || langId.isBlank()) continue;

                        var langBase = me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguage.getAssetMap().getAsset(langId);
                        if (!(langBase instanceof me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguageConfig lang)) continue;
                        VoskRecognizer.addAliases(aliases, lang);
                    }
                }

                out.add(Map.of(
                        "id", id,
                        "rootInteraction", castable.rootInteractionId,
                        "requiredItem", castable.requiredItemId,
                        "consumedItem", castable.consumedItem,
                        "aliases", aliases
                ));
            }

            out.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));
            HttpUtil.json(ex, 200, Map.of("ok", true, "spells", out));
        } catch (Throwable t) {
            HttpUtil.json(ex, 500, Map.of("ok", false, "error", "internal_error"));
        }
    }

    public void cast(HttpExchange ex) throws IOException {
        if (HttpUtil.isNotPost(ex)) {
            HttpUtil.methodNotAllowed(ex);
            return;
        }

        try {
            String token = HttpUtil.getBearerToken(ex);
            if (token == null) {
                HttpUtil.json(ex, 401, Map.of("ok", false, "error", "missing_token"));
                return;
            }

            SessionService.Session s = sessions.requireSession(token);
            if (s == null) {
                HttpUtil.json(ex, 401, Map.of("ok", false, "error", "invalid_or_expired_token"));
                return;
            }

            Map<String, Object> body = HttpUtil.readJsonObject(ex);
            String spellId = HttpUtil.getString(body, "spellId");
            double confidence = HttpUtil.getDouble(body, "confidence", 1.0);
            String raw = HttpUtil.getString(body, "raw");

            if (spellId == null || spellId.isBlank()) {
                HttpUtil.json(ex, 400, Map.of("ok", false, "error", "missing_spellId"));
                return;
            }

            VoiceCastCastableConfig castable = SpellRegistry.get(spellId);
            if (castable == null) {
                HttpUtil.json(ex, 404, Map.of("ok", false, "error", "spell_not_found"));
                return;
            }

            if (!sessions.tryConsumeCast(s, 10, 5000)) {
                HttpUtil.json(ex, 429, Map.of("ok", false, "error", "rate_limited"));
                return;
            }

            String rootId = castable.rootInteractionId;
            if (rootId == null || rootId.isBlank()) {
                HttpUtil.json(ex, 500, Map.of("ok", false, "error", "spell_missing_root_interaction"));
                return;
            }

            boolean queued = VoiceCastInteractionExecutor.executeForPlayerUuid(
                    s.playerUuid(),
                    rootId,
                    com.hypixel.hytale.protocol.InteractionType.Ability3,
                    castable.requiredItemId,
                    castable.consumedItem
            );

            if (!queued) {
                HttpUtil.json(ex, 409, Map.of("ok", false, "error", "cast_failed_player_offline_or_invalid"));
                return;
            }

            if (raw == null) return;

            HttpUtil.json(ex, 200, Map.of(
                    "ok", true,
                    "spellId", spellId,
                    "rootInteraction", rootId,
                    "confidence", Double.parseDouble(String.format(Locale.US, "%.3f", confidence)),
                    "raw", raw
            ));
        } catch (Throwable t) {
            HttpUtil.json(ex, 500, Map.of("ok", false, "error", "internal_error"));
        }
    }
}