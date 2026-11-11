package dev.joblessreincarnation.client.epic;

import dev.joblessreincarnation.compat.epic.EpicCastAnimations;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/** Reconcile only the current public entry/hold state after entity and equipment initialization.
 * Release remains a one-shot server event; no historical release is stored or replayed. */
public final class EpicWaterPoseSync {
    public static void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if (client.level == null) return;
        for (var player : client.level.players()) {
            dev.joblessreincarnation.client.TomeReading.syncPose(player);
            if (player == client.player) continue; // Local input/server events already own this player.
            var patch = EpicFightCapabilities.getPlayerPatch(player);
            if (patch == null) continue;
            var layer = patch.getClientAnimator().getCompositeLayer(Layer.Priority.HIGH);
            var current = layer.animationPlayer.getRealAnimation();
            boolean owned = EpicCastAnimations.isEntryOrHold(current);
            int phase = player.getData(ModAttachments.EPIC_WATER_PHASE);
            boolean valid = player.isAlive() && player.getMainHandItem().is(ModContent.TRAINING_STAFF.get());
            var desired = EpicCastAnimations.fromPhase(phase);
            if (!valid || desired == null) {
                if (!layer.isOff() && owned) patch.getClientAnimator().stopPlaying(current);
                continue;
            }
            if (patch.getEntityState().inaction()) continue; // Do not restore a pose during a dodge/hit/action.
            if (!layer.isOff() && EpicCastAnimations.same(current, desired)) continue;
            // Let unrelated equipment/combat actions finish. Never steal another animation layer.
            if (!layer.isOff() && !owned) continue;
            patch.getClientAnimator().playAnimation(desired, 0.0F);
        }
    }
    private EpicWaterPoseSync() {}
}
