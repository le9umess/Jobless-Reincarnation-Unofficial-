package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.training.SchoolCombatState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

/**
 * Where a player's sword reaches from. Normally where the player stands, with vanilla's own reach and sight checks.
 * During a Longsword of Light cut it is the point its dash has reached on the server's own clock: Epic Fight moves a player
 * by an animation only on their own client, so online the server saw the dash a round trip late and the cut missed
 * (GPT 11-07: 180 ms one way). The path is the clip's own movement from where the cut began, stopped where a wall would
 * stop the player; hits, reach and sight for that cut all use it.
 */
public final class SwordReach {
    /** The path covers the clip's first this many seconds, one point every {@link SchoolCombatState#CUT_PATH_STEP}. */
    private static final float PATH_SECONDS = 0.30F;
    /** Steps between points are at most this long, so a thin wall between two points still stops the dash. */
    private static final double STEP = 0.25;

    /** The dash of the cut about to start, as offsets from where the player stands now; level, like the client's own. */
    public static Vec3[] dashPath(ServerPlayerPatch patch, ServerPlayer player) {
        var clip = SchoolAnimations.LIGHT_CUT.get();
        int count = Math.round(PATH_SECONDS / SchoolCombatState.CUT_PATH_STEP) + 1;
        Vec3[] path = new Vec3[count];
        AABB box = player.getBoundingBox();
        Vec3 reached = Vec3.ZERO;
        boolean stopped = false;
        path[0] = Vec3.ZERO;
        for (int i = 1; i < count; i++) {
            Vec3 wanted = clip.getExpectedMovement(patch, i * SchoolCombatState.CUT_PATH_STEP);
            wanted = new Vec3(wanted.x, 0, wanted.z);
            if (!stopped) {
                int steps = Math.max(1, (int) Math.ceil(wanted.distanceTo(reached) / STEP));
                Vec3 from = reached;
                for (int s = 1; s <= steps; s++) {
                    Vec3 next = from.lerp(wanted, (double) s / steps);
                    if (!free(player, box, next)) {
                        stopped = true;
                        break;
                    }
                    reached = next;
                }
            }
            path[i] = reached;
        }
        return path;
    }

    /** Clear of blocks there, or after the step up the player would climb anyway. */
    private static boolean free(ServerPlayer player, AABB box, Vec3 offset) {
        AABB moved = box.move(offset);
        return player.level().noCollision(player, moved) || player.level().noCollision(player, moved.move(0, player.maxUpStep(), 0));
    } // 6D657373

    /** The point this player's Light Blade cut has reached, or null when no such cut is under way. */
    public static @Nullable Vec3 lightCutPoint(ServerPlayer player) {
        if (!player.hasData(ModAttachments.SCHOOL_COMBAT)) return null;
        var patch = EpicFightCapabilities.getEntityPatch(player, ServerPlayerPatch.class);
        if (patch == null) return null;
        var playing = patch.getAnimator().getPlayerFor(null);
        if (playing == null || playing.getRealAnimation() != SchoolAnimations.LIGHT_CUT) return null;
        return player.getData(ModAttachments.SCHOOL_COMBAT).lightCutAt(playing.getElapsedTime());
    }

    /** Within reach and in sight: vanilla's checks, measured from the dash during a Light Blade cut. */
    public static boolean reaches(ServerPlayer player, Entity target) {
        Vec3 at = lightCutPoint(player);
        if (at == null) return player.canInteractWithEntity(target, 0) && player.hasLineOfSight(target);
        if (target.level() != player.level()) return false;
        Vec3 eye = at.add(0, player.getEyeHeight(), 0);
        double range = player.entityInteractionRange();
        if (target.getBoundingBox().distanceToSqr(eye) >= range * range) return false;
        Vec3 sight = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        return player.level().clip(new ClipContext(eye, sight, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType()
                == HitResult.Type.MISS;
    }

    private SwordReach() {}
}
