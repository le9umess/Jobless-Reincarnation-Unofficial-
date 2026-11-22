package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.IcicleFieldEntity;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The low cold air of Icicle Field on this client (OFF7-03 budget): pale flakes drifting knee-high over the frost the
 * field can see, spreading outward while it forms and slowly circling while it holds, none once it fades; never behind
 * a wall or inside a block (GPT 11-07 review). While the field forms, a few small wisps at the caster's staff tip too,
 * since the caster stands in their own cold: only for a player with the training staff in their main hand, or for the
 * teacher's demonstration. Follows this player's particle setting: fewer on decreased, no cold air and no wisps on minimal
 * (the ground frost stays readable either way).
 */
public final class IcicleColdAir {
    public static void tick(IcicleFieldEntity field) {
        var level = field.level();
        if (level.getGameTime() >= field.fadeGameTime()) return;
        float age = field.ageTicks(0);
        boolean forming = age < IcicleFieldEntity.FORM_TICKS;
        ParticleStatus setting = Minecraft.getInstance().options.particles().get();
        int count = switch (setting) {
            case ALL -> forming ? 6 : 3;
            case DECREASED -> forming ? 3 : 2;
            case MINIMAL -> 0;
        };
        var random = level.random;
        if (count > 0) {
            IcicleFieldLook.Layout layout = IcicleFieldLook.of(field);
            float reach = forming ? Math.max(0.2F, age / IcicleFieldEntity.FORM_TICKS) : 1;
            int stamps = layout.stamps.size();
            for (int i = 0; i < count && stamps > 0; i++) {
                int pick = random.nextInt(stamps);
                IcicleFieldLook.Stamp stamp = layout.stamps.get(pick);
                if (!layout.seen[pick] || stamp.edge() > reach) continue;
                double x = stamp.x() + (random.nextDouble() - 0.5) * 0.8, z = stamp.z() + (random.nextDouble() - 0.5) * 0.8;
                double y = layout.ground[pick] + 0.1 + random.nextDouble() * 0.65;
                BlockPos at = BlockPos.containing(x, y, z);
                if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) continue;
                double angle = Math.atan2(z - field.getZ(), x - field.getX());
                double vx = forming ? Math.cos(angle) * 0.06 : -Math.sin(angle) * 0.03;
                double vz = forming ? Math.sin(angle) * 0.06 : Math.cos(angle) * 0.03;
                level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, vx, -0.004, vz);
            }
        }
        int every = setting == ParticleStatus.ALL ? 2 : setting == ParticleStatus.DECREASED ? 4 : 0;
        if (!forming || every == 0 || field.tickCount % every != 0 || !(level.getEntity(field.sourceId()) instanceof LivingEntity caster)) return;
        boolean staff = caster instanceof Player player ? player.getMainHandItem().is(ModContent.TRAINING_STAFF.get()) : field.demonstration();
        if (!staff) return;
        // The staff tip, roughly: in front of the chest, a little to the side of the main hand.
        // The side the staff is drawn on, as this client draws the caster: Epic Fight's own model (whenever Epic Fight
        // draws it, by its own overrideRender: third person in its combat mode, and first person too when its animated
        // first-person model is on) holds it in the right hand; the vanilla model and vanilla first-person hands (with
        // Punchy, which turns Epic Fight's first-person model off) hold it in the main arm's hand.
        var patch = caster instanceof Player drawn ? yesman.epicfight.world.capabilities.EpicFightCapabilities.getPlayerPatch(drawn) : null;
        float yaw = caster.getYRot() * Mth.DEG_TO_RAD, side = dev.joblessreincarnation.magic.StaffSide.of(caster.getMainArm(), patch != null && patch.overrideRender());
        var look = caster.getLookAngle();
        double tx = caster.getX() + look.x * 0.55 - Mth.cos(yaw) * 0.32 * side;
        double ty = caster.getEyeY() - 0.35 + look.y * 0.4;
        double tz = caster.getZ() + look.z * 0.55 - Mth.sin(yaw) * 0.32 * side;
        level.addParticle(ParticleTypes.SNOWFLAKE, tx, ty, tz, (random.nextDouble() - 0.5) * 0.02, 0.01, (random.nextDouble() - 0.5) * 0.02);
    }

    private IcicleColdAir() {}
}
