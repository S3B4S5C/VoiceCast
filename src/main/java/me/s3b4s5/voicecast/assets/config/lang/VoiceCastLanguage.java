package me.s3b4s5.voicecast.assets.config.lang;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetCodecMapCodec;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import lombok.Getter;

import javax.annotation.Nonnull;

public abstract class VoiceCastLanguage
        implements JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, VoiceCastLanguage>> {

    @Nonnull
    public static final AssetCodecMapCodec<String, VoiceCastLanguage> CODEC =
            new AssetCodecMapCodec<>(
                    Codec.STRING,
                    (t, k) -> t.id = (k == null ? "" : k),
                    (t) -> t.id,
                    (t, data) -> t.data = data,
                    (t) -> t.data,
                    true
            );

    private static AssetStore<String, VoiceCastLanguage, IndexedLookupTableAssetMap<String, VoiceCastLanguage>> ASSET_STORE;

    @Nonnull
    public static AssetStore<String, VoiceCastLanguage, IndexedLookupTableAssetMap<String, VoiceCastLanguage>> getAssetStore() {
        if (ASSET_STORE == null) ASSET_STORE = AssetRegistry.getAssetStore(VoiceCastLanguage.class);
        return ASSET_STORE;
    }

    @Nonnull
    public static IndexedLookupTableAssetMap<String, VoiceCastLanguage> getAssetMap() {
        return getAssetStore().getAssetMap();
    }

    public String id = "";
    public AssetExtraInfo.Data data;

    @Getter
    public boolean unknown;

    public String languageCode = "en_en";
    public String[] values = new String[0];

    @Nonnull
    public String getId() {
        return id == null ? "" : id;
    }
}