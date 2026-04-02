package me.s3b4s5.voicecast.assets.codec.lang;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.validation.Validators;
import me.s3b4s5.voicecast.assets.config.lang.VoiceCastLanguageConfig;

public final class VoiceCastLanguageCodec {

    private VoiceCastLanguageCodec() {}

    public static void appendFields(
            BuilderCodec.Builder<VoiceCastLanguageConfig> b
    ) {
        b.appendInherited(new KeyedCodec<>("LanguageCode", Codec.STRING),
                        (o, v) -> o.languageCode = (v == null ? o.languageCode : v),
                        (o) -> o.languageCode,
                        (o, p) -> o.languageCode = p.languageCode
                )
                .documentation("Language code for this phrase set (e.g. en_en, es_es, es_mx).")
                .addValidator(Validators.nonNull())
                .add();

        b.appendInherited(new KeyedCodec<>("Values", new ArrayCodec<>(Codec.STRING, String[]::new)),
                        (o, v) -> o.values = (v == null ? o.values : v),
                        (o) -> o.values,
                        (o, p) -> o.values = p.values
                )
                .documentation("Accepted phrases/keywords for this castable in the given language.")
                .addValidator(Validators.nonNull())
                .add();

    }
}