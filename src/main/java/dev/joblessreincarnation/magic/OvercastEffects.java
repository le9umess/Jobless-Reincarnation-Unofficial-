package dev.joblessreincarnation.magic;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** What everyone nearby sees and hears when someone overdraws: a pale flash, wisps leaving the caster and a dull crack. Presentation only. */
public final class OvercastEffects {
    private static final double SEEN_WITHIN = 48.0;

    public static void play(ServerPlayer caster) {
        Vec3 at = caster.position().add(0.0, caster.getBbHeight() * 0.55, 0.0);
        SpellBurstOptions burst = new SpellBurstOptions(SpellBurstOptions.OVERCAST, caster.getId());
        // Past the particle setting, so the lowest one still shows the flash; the wisps obey it on the client.
        for (ServerPlayer viewer : caster.serverLevel().players()) {
            if (viewer.distanceToSqr(at) <= SEEN_WITHIN * SEEN_WITHIN) caster.serverLevel().sendParticles(viewer, burst, true, at.x, at.y, at.z, 0, 0.0, 0.0, 0.0, 1.0);
        }
        caster.serverLevel().playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 0.9F, 0.55F);
    }

    private OvercastEffects() {}
}
