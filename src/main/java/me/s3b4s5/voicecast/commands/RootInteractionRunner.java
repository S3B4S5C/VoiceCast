package me.s3b4s5.voicecast.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class RootInteractionRunner {

    private RootInteractionRunner() {}

    public static void runOnPlayer(
            Ref<EntityStore> playerRef,
            String rootInteractionId,
            InteractionType type
    ) {
        if (playerRef == null || !playerRef.isValid()) return;
        if (rootInteractionId == null || rootInteractionId.isBlank()) return;

        Store<EntityStore> store = playerRef.getStore();

        InteractionManager im = store.getComponent(playerRef, InteractionModule.get().getInteractionManagerComponent());
        if (im == null) return;

        RootInteraction root = RootInteraction.getAssetMap().getAsset(rootInteractionId);
        if (root == null) return;

        InteractionContext ctx = InteractionContext.forInteraction(im, playerRef, type, store);
        InteractionChain chain = im.initChain(type, ctx, root, true);

        im.queueExecuteChain(chain);
    }
}