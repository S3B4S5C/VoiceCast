package me.s3b4s5.voicecast.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.common.util.StringUtil;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.s3b4s5.voicecast.VoiceCast;

import java.util.UUID;

public final class VoiceCastInteractionExecutor {

    private VoiceCastInteractionExecutor() {}

    public static boolean executeForPlayerUuid(
            String playerUuidStr,
            String rootInteractionId,
            InteractionType type,
            String requiredItemId,
            int consumedItem
    ) {
        if (playerUuidStr == null || playerUuidStr.isBlank()) {
            VoiceCast.LOGGER.atWarning().log("executor: missing playerUuid");
            return false;
        }
        if (rootInteractionId == null || rootInteractionId.isBlank()) {
            VoiceCast.LOGGER.atWarning().log("executor: missing rootInteractionId player=" + playerUuidStr);
            return false;
        }

        final UUID playerUuid;
        try {
            playerUuid = UUID.fromString(playerUuidStr);
        } catch (IllegalArgumentException e) {
            VoiceCast.LOGGER.atWarning().log("executor: invalid uuid format player=" + playerUuidStr);
            return false;
        }

        PlayerRef player = Universe.get().getPlayer(playerUuid);
        if (player == null) {
            VoiceCast.LOGGER.atInfo().log("executor: player not online player=" + playerUuidStr);
            return false;
        }

        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            VoiceCast.LOGGER.atWarning().log("executor: invalid player ref player=" + playerUuidStr);
            return false;
        }

        if (player.getWorldUuid() == null) {
            VoiceCast.LOGGER.atWarning().log("executor: player has no worldUuid player=" + playerUuidStr);
            return false;
        }

        World world = Universe.get().getWorld(player.getWorldUuid());
        if (world == null) {
            VoiceCast.LOGGER.atWarning().log("executor: world not found player=" + playerUuidStr + " worldUuid=" + player.getWorldUuid());
            return false;
        }

        final String reqItem = (requiredItemId == null ? "" : requiredItemId.trim());
        final int consume = Math.max(0, consumedItem);

        world.execute(() -> {
            try {
                PlayerRef p2 = Universe.get().getPlayer(playerUuid);
                if (p2 == null) {
                    VoiceCast.LOGGER.atInfo().log("executor(world): player offline before run player=" + playerUuidStr);
                    return;
                }

                Ref<EntityStore> ref2 = p2.getReference();
                if (ref2 == null || !ref2.isValid()) {
                    VoiceCast.LOGGER.atWarning().log("executor(world): invalid ref player=" + playerUuidStr);
                    return;
                }

                RootInteraction root = RootInteraction.getAssetMap().getAsset(rootInteractionId);
                if (root == null) {
                    VoiceCast.LOGGER.atWarning().log("executor(world): missing RootInteraction id=" + rootInteractionId);
                    return;
                }

                Store<EntityStore> store = ref2.getStore();
                Player playerComp = store.getComponent(ref2, Player.getComponentType());
                if (playerComp == null) {
                    VoiceCast.LOGGER.atWarning().log("executor(world): missing Player component player=" + playerUuidStr);
                    return;
                }

                Inventory inv = playerComp.getInventory();
                ItemStack inHand = inv.getItemInHand();

                if (!reqItem.isEmpty()) {
                    if (ItemStack.isEmpty(inHand)) {
                        VoiceCast.LOGGER.atInfo().log(
                                "executor(world): blocked (missing required item in hand) player=" +
                                        playerUuidStr + " requiredItem=" + reqItem
                        );
                        return;
                    } else {
                        inHand.getItem();
                    }

                    String handId = inHand.getItem().getId();
                    boolean matches = matchesItemId(reqItem, handId);

                    if (!matches) {
                        VoiceCast.LOGGER.atInfo().log(
                                "executor(world): blocked (wrong item in hand) player=" +
                                        playerUuidStr + " requiredItem=" + reqItem + " inHand=" + handId
                        );
                        return;
                    }
                }

                if (consume > 0) {
                    if (ItemStack.isEmpty(inHand) || inHand.getQuantity() < consume) {
                        VoiceCast.LOGGER.atInfo().log(
                                "executor(world): blocked (not enough items to consume) player=" +
                                        playerUuidStr + " need=" + consume + " have=" + (ItemStack.isEmpty(inHand) ? 0 : inHand.getQuantity())
                        );
                        return;
                    }

                    byte active = inv.getActiveHotbarSlot();
                    if (active == -1) {
                        VoiceCast.LOGGER.atWarning().log(
                                "executor(world): cannot consume (no active hotbar slot) player=" + playerUuidStr
                        );
                        return;
                    }

                    if (inv.getHotbar() == null) return;

                    inv.getHotbar().removeItemStackFromSlot(active, consume);

                    VoiceCast.LOGGER.atInfo().log(
                            "executor(world): consumed player=" + playerUuidStr +
                                    " item=" + (ItemStack.isEmpty(inHand) ? "?" : inHand.getItem().getId()) +
                                    " amount=" + consume +
                                    " slot=" + active
                    );
                }

                RootInteractionRunner.runOnPlayer(ref2, rootInteractionId, type);

            } catch (Throwable t) {
                VoiceCast.LOGGER.atSevere().withCause(t).log("executor(world): exception");
            }
        });

        return true;
    }

    private static boolean matchesItemId(String pattern, String itemId) {
        if (pattern == null || pattern.isEmpty() || itemId == null || itemId.isEmpty()) return false;

        return StringUtil.isGlobMatching(pattern, itemId);
    }
}