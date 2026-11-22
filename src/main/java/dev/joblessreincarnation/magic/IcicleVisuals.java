package dev.joblessreincarnation.magic;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * What Icicle Field looks and sounds like (OFF7-03: pale cold blue-greys, no glow, no flash). The server sends the
 * one-off cues here; the ground frost of the field itself is drawn on each client by {@code IcicleFieldRenderer}, and its
 * low cold air comes from {@link #clientTick}, following each player's own particle setting. Sounds are vanilla ones.
 * No gameplay or private progress is computed in these calls.
 */
public final class IcicleVisuals {
    /** The cast: a breath of cold settling over the spot, a soft crunch of frost and a faint crystal chime. */
    public static void cast(ServerLevel level, Vec3 base) {
        level.sendParticles(ParticleTypes.SNOWFLAKE, base.x, base.y + 0.3, base.z, 12, 0.7, 0.12, 0.7, 0.015);
        level.playSound(null, base.x, base.y, base.z, SoundEvents.POWDER_SNOW_PLACE, SoundSource.PLAYERS, 0.8F, 0.7F);
        level.playSound(null, base.x, base.y, base.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.45F, 1.7F);
    }

    /** A chilled target, about once a second while it stays in the cold: frost flakes settling on it. */
    public static void affect(ServerLevel level, Entity target) {
        double w = target.getBbWidth() * 0.5, h = target.getBbHeight();
        level.sendParticles(ParticleTypes.SNOWFLAKE, target.getX(), target.getY() + h * 0.6, target.getZ(), 5, w, h * 0.3, w, 0.005);
    }

    /** Frost Nova: the soaked target freezes over; shards of ice burst off it with a hard crack. */
    public static void nova(ServerLevel level, Entity target) {
        double w = target.getBbWidth() * 0.5, h = target.getBbHeight();
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()),
                target.getX(), target.getY() + h * 0.5, target.getZ(), 26, w, h * 0.35, w, 0.15);
        level.sendParticles(ParticleTypes.SNOWFLAKE, target.getX(), target.getY() + h * 0.5, target.getZ(), 18, w + 0.3, h * 0.4, w + 0.3, 0.06);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.6F, 1.5F);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 0.8F, 0.9F);
    }

    /** The cold lifting: a soft crumble as the frost lets go. */
    public static void end(ServerLevel level, Vec3 base) {
        level.playSound(null, base.x, base.y, base.z, SoundEvents.POWDER_SNOW_BREAK, SoundSource.PLAYERS, 0.35F, 1.3F);
    }

    /** On each client: the field's low cold air and the caster's staff-tip wisps; nothing on the server. */
    public static void clientTick(IcicleFieldEntity field) {
        dev.joblessreincarnation.client.IcicleColdAir.tick(field);
    }

    private IcicleVisuals() {}
}
