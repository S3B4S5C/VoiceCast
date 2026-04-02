package me.s3b4s5.voicecast.assets.config.cast;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetCodecMapCodec;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import lombok.Getter;

import javax.annotation.Nonnull;

public abstract class VoiceCastCastable
        implements JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, VoiceCastCastable>> {

    @Nonnull
    public static final AssetCodecMapCodec<String, VoiceCastCastable> CODEC =
            new AssetCodecMapCodec<>(
                    Codec.STRING,
                    (t, k) -> t.id = (k == null ? "" : k),
                    (t) -> t.id,
                    (t, data) -> t.data = data,
                    (t) -> t.data,
                    true
            );

    private static AssetStore<String, VoiceCastCastable, IndexedLookupTableAssetMap<String, VoiceCastCastable>> ASSET_STORE;

    @Nonnull
    public static AssetStore<String, VoiceCastCastable, IndexedLookupTableAssetMap<String, VoiceCastCastable>> getAssetStore() {
        if (ASSET_STORE == null) ASSET_STORE = AssetRegistry.getAssetStore(VoiceCastCastable.class);
        return ASSET_STORE;
    }

    public String id = "";
    public AssetExtraInfo.Data data;
    @Getter public boolean unknown;

    /** Reference to a vanilla RootInteraction asset id (string). */
    public String rootInteractionId = "";

    /** References to VoiceCastLanguage assets (string ids). */
    public String[] languageIds = new String[0];
    public String requiredItemId = "";
    public int consumedItem = 0;

    @Nonnull
    public String getId() {
        return id == null ? "" : id;
    }

}