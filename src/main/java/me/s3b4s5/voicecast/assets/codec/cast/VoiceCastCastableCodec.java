package me.s3b4s5.voicecast.assets.codec.cast;

import com.hypixel.hytale.assetstore.codec.ContainedAssetCodec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import me.s3b4s5.voicecast.assets.config.cast.VoiceCastCastableConfig;
import me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguage;

public final class VoiceCastCastableCodec {

    private VoiceCastCastableCodec() {}

    public static final Codec<String> ROOT_INTERACTION_REF =
            RootInteraction.CHILD_ASSET_CODEC;

    public static final Codec<String> LANGUAGE_REF =
            new ContainedAssetCodec<>(VoiceCastLanguage.class, VoiceCastLanguage.CODEC);

    public static final Codec<String> ITEM_REF =
            new ContainedAssetCodec<>(Item.class, Item.CODEC);

    public static void appendFields(
            BuilderCodec.Builder<VoiceCastCastableConfig> b
    ) {
        b.appendInherited(new KeyedCodec<>("RootInteraction", ROOT_INTERACTION_REF),
                        (o, v) -> o.rootInteractionId = (v == null ? "" : v),
                        (o) -> o.rootInteractionId,
                        (o, p) -> o.rootInteractionId = p.rootInteractionId
                )
                .documentation("Reference to a vanilla RootInteraction asset id.")
                .addValidator(Validators.nonNull())
                .add();

        b.appendInherited(new KeyedCodec<>("Languages", new ArrayCodec<>(LANGUAGE_REF, String[]::new)),
                        (o, v) -> o.languageIds = (v == null ? o.languageIds : v),
                        (o) -> o.languageIds,
                        (o, p) -> o.languageIds = p.languageIds
                )
                .documentation("List of references to VoiceCastLanguage assets (phrase sets per language).")
                .addValidator(Validators.nonNull())
                .addValidator(Validators.nonEmptyArray())
                .add();

        b.appendInherited(new KeyedCodec<>("RequiredItem", ITEM_REF),
                        (o, v) -> o.requiredItemId = (v == null ? "" : v),
                        (o) -> o.requiredItemId,
                        (o, p) -> o.requiredItemId = p.requiredItemId
                )
                .documentation("Optional required item id (vanilla Item asset). If empty, no item is required.")
                .add();

        b.appendInherited(new KeyedCodec<>("ConsumedItem", Codec.INTEGER),
                        (o, v) -> o.consumedItem = (v == null ? o.consumedItem : v),
                        (o) -> o.consumedItem,
                        (o, p) -> o.consumedItem = p.consumedItem
                )
                .documentation("0 = do not consume the RequiredItem. 1 = consume one RequiredItem on cast. Default: 0.")
                .addValidator(Validators.greaterThanOrEqual(0))
                .add();

    }
}