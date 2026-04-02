package me.s3b4s5.voicecast.assets.store;

import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguage;
import me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguageConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

public final class VoiceCastLanguageStore extends HytaleAssetStore<
        String,
        VoiceCastLanguage,
        IndexedLookupTableAssetMap<String, VoiceCastLanguage>> {

    private static final String PATH = "Entity/VoiceCast/CastableLanguage";

    private VoiceCastLanguageStore(@Nonnull Builder<String, VoiceCastLanguage, IndexedLookupTableAssetMap<String, VoiceCastLanguage>> b) {
        super(b);
    }

    @Nonnull
    public static VoiceCastLanguageStore create() {
        var map = new IndexedLookupTableAssetMap<>(VoiceCastLanguage[]::new);
        var b = HytaleAssetStore.builder(String.class, VoiceCastLanguage.class, map);

        b.setPath(PATH)
                .setCodec(VoiceCastLanguage.CODEC)
                .setKeyFunction(VoiceCastLanguage::getId)
                .setIdProvider(VoiceCastLanguageConfig.class)
                .setIsUnknown(VoiceCastLanguage::isUnknown);

        b.setReplaceOnRemove((String id) -> {
            var cfg = new VoiceCastLanguageConfig();
            cfg.id = (id == null ? "" : id);
            cfg.unknown = true;
            cfg.languageCode = "en_en";
            cfg.values = new String[0];
            return cfg;
        });

        return new VoiceCastLanguageStore(b);
    }

    @Override
    protected void handleRemoveOrUpdate(
            @Nullable Set<String> removedKeys,
            @Nullable Map<String, VoiceCastLanguage> loadedOrUpdated,
            @Nonnull AssetUpdateQuery query
    ) {
        super.handleRemoveOrUpdate(removedKeys, loadedOrUpdated, query);
    }
}