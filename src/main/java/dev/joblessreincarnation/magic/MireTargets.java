package dev.joblessreincarnation.magic;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

public final class MireTargets {
    /** Smaller than a carpet, so a covered floor never controls an entity standing on the covering. */
    public static final double FOOT_TOLERANCE = 0.03;

    public static boolean eligible(ServerPlayer owner, LivingEntity target) {
        return ControlSpellTargets.canAffect(owner, target) && !target.isPassenger()
                && !target.isFallFlying() && !target.isInWaterOrBubble() && !target.isInLava();
    }

    /** Feet, not a tall body intersection, determine contact; the mask is exactly the mask drawn by the client. */
    public static boolean touches(MireEntity field, LivingEntity target) {
        return target.level() == field.level() && target.onGround()
                && Math.abs(target.getBoundingBox().minY - field.surfaceY(target.getX(), target.getZ())) <= FOOT_TOLERANCE;
    }

    private MireTargets() {}
}
