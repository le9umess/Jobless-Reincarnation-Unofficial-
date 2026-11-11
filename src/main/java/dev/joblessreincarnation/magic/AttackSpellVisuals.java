package dev.joblessreincarnation.magic;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Essential attack shapes survive particle preferences; decorative child particles still obey them. */
public final class AttackSpellVisuals {
    public static <T extends ParticleOptions> void send(ServerLevel level, T options, double x, double y, double z,
            int count, double dx, double dy, double dz, double speed) {
        Vec3 point = new Vec3(x, y, z);
        // Match the normal particle delivery radius; force must not widen it to vanilla's 512 blocks.
        for (ServerPlayer viewer : level.players()) {
            if (viewer.blockPosition().closerToCenterThan(point, 32.0))
                level.sendParticles(viewer, options, true, x, y, z, count, dx, dy, dz, speed);
        }
    }
    private AttackSpellVisuals() {}
}
