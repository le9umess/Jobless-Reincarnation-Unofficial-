package dev.joblessreincarnation.magic;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How a mire looks and sounds: the timing curves MireRenderer draws with, the entity's client-side particles and quiet
 * bubbling, and the server's contact feedback. Common code: it only calls Level#addParticle, Level#playLocalSound and
 * ServerLevel methods, so the entity can call it on the logical client without touching client classes.
 *
 * The phases follow MireEntity exactly. The wet edge band shows the whole footprint from the first tick and never
 * moves; inside it the mud spreads out from the centre while the field forms, which slows nothing. While it slows, the
 * mud is dark and wet under a moving film of water, with small glints, and bubbles now and then. When slowing stops the
 * film and glints are gone within three ticks, and the mud dries into pale packed mud and fades out without shrinking.
 */
public final class MireVisuals {
    /** Width of the wet edge band, which lies inside the outer cells, so decoration never widens the footprint. */
    public static final float RIM = 0.14F;
    /** Farthest drawn vertex from the centre: the outer corners of the cells at (±1, ±2) and (±2, ±1). */
    public static final float REACH = Mth.sqrt(1.5F * 1.5F + 2.5F * 2.5F);
    /** How far behind the spreading front the mud takes to become solid, in blocks. */
    private static final float SOFTNESS = 0.8F;
    private static final float RIM_IN = 2.0F;
    private static final float FILM_IN = 3.0F;
    private static final float FILM_OUT = 3.0F;
    private static final float ACTIVE_END = MireEntity.LIFETIME_TICKS - MireEntity.FADE_TICKS;
    private static final ParticleOptions MUD = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MUD.defaultBlockState());
    private static final ParticleOptions DRIED = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_MUD.defaultBlockState());

    private static float smooth(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** 0 to 1: how solid the mud is at this distance from the centre. Everything is solid once the field has formed. */
    public static float fill(float age, float distance) {
        float front = smooth(age / MireEntity.FORM_TICKS) * (REACH + SOFTNESS);
        return Mth.clamp((front - distance) / SOFTNESS, 0.0F, 1.0F);
    }

    /** 0 to 1 over the last ticks, as the mud dries out. */
    public static float dry(float age) {
        return Mth.clamp((age - ACTIVE_END) / MireEntity.FADE_TICKS, 0.0F, 1.0F);
    }

    /** 1 until slowing stops, then down to 0 at the end of the lifetime. */
    public static float fade(float age) {
        return age >= MireEntity.LIFETIME_TICKS ? 0.0F : 1.0F - smooth(dry(age));
    }

    /** The edge band: shown almost at once, gone with the mud. */
    public static float rim(float age) {
        return Mth.clamp(age / RIM_IN, 0.0F, 1.0F) * fade(age);
    }

    /** The wet film: in just before slowing starts, out within a few ticks of it stopping. */
    public static float wet(float age) {
        float in = Mth.clamp((age - (MireEntity.FORM_TICKS - FILM_IN)) / FILM_IN, 0.0F, 1.0F);
        float out = Mth.clamp(1.0F - (age - ACTIVE_END) / FILM_OUT, 0.0F, 1.0F);
        return in * out;
    }

    /**
     * Mud thrown up along the spreading front with a little water at the centre, then slow bubbles that pop quietly,
     * mud flicked up by slowed mobs that keep moving, and a dry crumble once slowing stops.
     */
    static void clientTick(MireEntity field) {
        Level level = field.level();
        long elapsed = level.getGameTime() - field.startGameTime();
        if (elapsed < 0 || elapsed >= MireEntity.LIFETIME_TICKS) return;
        RandomSource random = level.random;
        Vec3 base = field.position();
        List<BlockPos> cells = MireEntity.CELL_OFFSETS;
        if (elapsed < MireEntity.FORM_TICKS) {
            float front = smooth((elapsed + 0.5F) / MireEntity.FORM_TICKS) * (REACH + SOFTNESS) - SOFTNESS * 0.5F;
            for (int i = 0; i < 3; i++) {
                BlockPos cell = cells.get(random.nextInt(cells.size()));
                if (Math.abs(Mth.sqrt(cell.getX() * cell.getX() + cell.getZ() * cell.getZ()) - front) > 0.75F) continue;
                Vec3 point = pointIn(base, cell, random);
                level.addParticle(MUD, point.x, point.y + 0.05, point.z, 0.0, 0.15, 0.0);
            }
            if (elapsed < 3) {
                for (int i = 0; i < 2; i++) {
                    double angle = random.nextDouble() * Math.PI * 2.0, reach = random.nextDouble() * 0.9;
                    level.addParticle(ParticleTypes.SPLASH, base.x + Math.cos(angle) * reach, base.y + 0.05,
                            base.z + Math.sin(angle) * reach, 0.0, 0.0, 0.0);
                }
            }
            return;
        }
        if (elapsed < ACTIVE_END) {
            // Slow bubbles, more often near the deeper centre; one pop in five is heard, very quietly.
            if (random.nextFloat() < 0.2F) {
                BlockPos cell = cells.get(random.nextInt(cells.size()));
                if (random.nextFloat() * 3.0F > Mth.sqrt(cell.getX() * cell.getX() + cell.getZ() * cell.getZ())) {
                    Vec3 point = pointIn(base, cell, random);
                    level.addParticle(ParticleTypes.BUBBLE_POP, point.x, point.y + 0.04, point.z, 0.0, 0.01, 0.0);
                    if (random.nextFloat() < 0.2F) {
                        level.playLocalSound(point.x, point.y, point.z, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS,
                                0.08F + random.nextFloat() * 0.04F, 0.6F + random.nextFloat() * 0.2F, false);
                    }
                }
            }
            if (elapsed % 4 == 0) stepFeedback(field, base, random);
            return;
        }
        if (elapsed == (long) ACTIVE_END) {
            for (int i = 0; i < 6; i++) {
                Vec3 point = pointIn(base, cells.get(random.nextInt(cells.size())), random);
                level.addParticle(DRIED, point.x, point.y + 0.05, point.z, 0.0, 0.05, 0.0);
            }
            level.playLocalSound(base.x, base.y, base.z, SoundEvents.PACKED_MUD_PLACE, SoundSource.PLAYERS, 0.35F, 0.9F, false);
        }
    }

    /**
     * Mobs the server is slowing carry its speed modifier, which is synced to every client, so the client can show which
     * ones are stuck without guessing at the server's rules. A mob standing where two mires overlap is shown by one.
     */
    private static void stepFeedback(MireEntity field, Vec3 base, RandomSource random) {
        Level level = field.level();
        double reach = MireEntity.HALF_EXTENT;
        AABB area = new AABB(base.x - reach, base.y - 0.25, base.z - reach, base.x + reach, base.y + 0.5, base.z + reach);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area, mob -> slowed(mob) && covers(field, mob))) {
            if (Math.abs(mob.getX() - mob.xo) + Math.abs(mob.getZ() - mob.zo) < 0.01) continue;
            boolean first = level.getEntitiesOfClass(MireEntity.class, mob.getBoundingBox().inflate(reach + 1.0),
                    other -> other.getId() < field.getId() && other.activeAt(level.getGameTime()) && covers(other, mob)).isEmpty();
            if (!first) continue;
            double spread = mob.getBbWidth() * 0.4;
            for (int i = 0; i < 2; i++) {
                level.addParticle(MUD, mob.getX() + (random.nextDouble() * 2.0 - 1.0) * spread, base.y + 0.05,
                        mob.getZ() + (random.nextDouble() * 2.0 - 1.0) * spread, 0.0, 0.1, 0.0);
            }
        }
    }

    private static boolean slowed(Mob mob) {
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        return speed != null && speed.hasModifier(MireFields.SLOW_ID);
    }

    /** Feet over one of the field's cells and near its surface; the server's own test decides the actual slowing. */
    private static boolean covers(MireEntity field, Mob mob) {
        if (Math.abs(mob.getY() - field.getY()) > 0.2) return false;
        BlockPos centre = field.blockPosition();
        BlockPos feet = BlockPos.containing(mob.getX(), field.getY(), mob.getZ());
        return MireEntity.CELL_OFFSETS.contains(new BlockPos(feet.getX() - centre.getX(), 0, feet.getZ() - centre.getZ()));
    }

    /** A random point on a cell's surface, kept off the edge band. */
    private static Vec3 pointIn(Vec3 base, BlockPos cell, RandomSource random) {
        double inner = 0.5 - RIM;
        return new Vec3(base.x + cell.getX() + (random.nextDouble() * 2.0 - 1.0) * inner, base.y,
                base.z + cell.getZ() + (random.nextDouble() * 2.0 - 1.0) * inner);
    }

    /** The release sound, heard by everyone nearby once the paid field is placed; the spreading itself is client-side. */
    static void cast(ServerLevel level, Vec3 base) {
        level.playSound(null, base.x, base.y, base.z, SoundEvents.MUD_PLACE, SoundSource.PLAYERS, 0.6F, 0.85F);
    }

    /**
     * Contact feedback for a target the server has just started slowing: mud and a little water splashed at its feet,
     * a squelch and a sticky drag. MireFields calls this once per target on entry, rate-limited and deduplicated across
     * overlapping sources, so it is never repeated every tick.
     */
    static void affect(ServerLevel level, LivingEntity target) {
        double x = target.getX(), y = target.getY(), z = target.getZ(), spread = target.getBbWidth() * 0.35;
        RandomSource random = level.random;
        level.sendParticles(MUD, x, y + 0.05, z, 10, spread, 0.02, spread, 0.15);
        level.sendParticles(ParticleTypes.SPLASH, x, y + 0.05, z, 4, spread, 0.0, spread, 0.0);
        level.playSound(null, x, y, z, SoundEvents.MUD_STEP, SoundSource.PLAYERS, 0.8F, 0.75F + random.nextFloat() * 0.15F);
        level.playSound(null, x, y, z, SoundEvents.HONEY_BLOCK_SLIDE, SoundSource.PLAYERS, 0.35F, 0.8F + random.nextFloat() * 0.1F);
    }

    private MireVisuals() {}
}
