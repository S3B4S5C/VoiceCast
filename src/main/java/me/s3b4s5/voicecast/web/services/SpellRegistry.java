package me.s3b4s5.voicecast.web.services;

import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastable;
import me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastableConfig;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;

public final class SpellRegistry {

    private static final ConcurrentHashMap<String, VoiceCastCastableConfig> CACHE = new ConcurrentHashMap<>();

    private SpellRegistry() {}

    public static void register(@Nullable VoiceCastCastableConfig cfg) {
        if (cfg == null) return;
        String id = cfg.getId();
        if (id.isBlank() || cfg.isUnknown()) return;
        CACHE.put(id, cfg);
    }

    public static void unregister(@Nullable String id) {
        if (id == null || id.isBlank()) return;
        CACHE.remove(id);
    }

    @Nullable
    public static VoiceCastCastableConfig get(@Nullable String id) {
        if (id == null || id.isBlank()) return null;

        VoiceCastCastableConfig cached = CACHE.get(id);
        if (cached != null) return cached.isUnknown() ? null : cached;

        VoiceCastCastableConfig cfg = getFromAssetMap(id);
        if (cfg == null || cfg.isUnknown()) return null;

        CACHE.put(id, cfg);
        return cfg;
    }

    @Nullable
    private static VoiceCastCastableConfig getFromAssetMap(String id) {
        try {
            IndexedLookupTableAssetMap<String, VoiceCastCastable> map =
                    VoiceCastCastable.getAssetStore().getAssetMap();

            int idx = map.getIndex(id);
            if (idx == Integer.MIN_VALUE) return null;

            VoiceCastCastable v = map.getAsset(idx);
            if (v instanceof VoiceCastCastableConfig cfg) return cfg;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}