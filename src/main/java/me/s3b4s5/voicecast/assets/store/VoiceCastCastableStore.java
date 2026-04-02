package me.s3b4s5.voicecast.assets.store;

import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastable;
import me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastableConfig;
import me.s3b4s5.voicecast.web.services.SpellRegistry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

public final class VoiceCastCastableStore extends HytaleAssetStore<
        String,
        VoiceCastCastable,
        IndexedLookupTableAssetMap<String, VoiceCastCastable>> {

    private static final String PATH = "Entity/VoiceCast/Castables";

    private VoiceCastCastableStore(@Nonnull Builder<String, VoiceCastCastable, IndexedLookupTableAssetMap<String, VoiceCastCastable>> b) {
        super(b);
    }

    @Nonnull
    public static VoiceCastCastableStore create() {
        var map = new IndexedLookupTableAssetMap<>(VoiceCastCastable[]::new);
        var b = HytaleAssetStore.builder(String.class, VoiceCastCastable.class, map);

        b.setPath(PATH)
                .setCodec(VoiceCastCastable.CODEC)
                .setKeyFunction(VoiceCastCastable::getId)
                .setIdProvider(VoiceCastCastableConfig.class)
                .setIsUnknown(VoiceCastCastable::isUnknown);

        b.setReplaceOnRemove((String id) -> {
            var cfg = new VoiceCastCastableConfig();
            cfg.id = (id == null ? "" : id);
            cfg.unknown = true;
            cfg.rootInteractionId = "";
            cfg.languageIds = new String[0];
            return cfg;
        });

        return new VoiceCastCastableStore(b);
    }

    @Override
    protected void handleRemoveOrUpdate(
            @Nullable Set<String> removedKeys,
            @Nullable Map<String, VoiceCastCastable> loadedOrUpdated,
            @Nonnull AssetUpdateQuery query
    ) {
        super.handleRemoveOrUpdate(removedKeys, loadedOrUpdated, query);

        int loaded = 0;
        if (loadedOrUpdated != null) {
            for (VoiceCastCastable v : loadedOrUpdated.values()) {
                if (v instanceof VoiceCastCastableConfig cfg && !cfg.isUnknown()) {
                    SpellRegistry.register(cfg);
                    loaded++;
                }
            }
        }

        int removed = 0;
        if (removedKeys != null) {
            for (String id : removedKeys) {
                SpellRegistry.unregister(id);
                removed++;
            }
        }

        me.s3b4s5.voicecast.VoiceCast.LOGGER.atInfo().log(
                "[VoiceCastCastableStore] loaded=%d removed=%d",
                loaded, removed
        );
    }
}