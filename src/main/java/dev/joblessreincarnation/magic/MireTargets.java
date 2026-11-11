package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.npc.SylphietteEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/** Narrow default support, with hard exclusions taking precedence over data-pack additions to the target tag. */
public final class MireTargets {
    public static final TagKey<EntityType<?>> TARGETS = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "mire_targets"));
    /** Smaller than a carpet, so a covered floor never controls an entity standing on the covering. */
    public static final double FOOT_TOLERANCE = 0.03;

    public static boolean eligible(ServerPlayer owner, LivingEntity target) {
        if (!dev.joblessreincarnation.encounter.PaulDuel.mayAffect(owner,target)) return false;
        if (dev.joblessreincarnation.encounter.PaulDuel.spellTarget(owner,target)) return !target.isPassenger() && !target.isVehicle()
                && !target.isNoGravity() && !target.noPhysics && !target.isInWaterOrBubble() && !target.isInLava();
        if (!(target instanceof Mob mob) || !(target instanceof Enemy)
                || target instanceof Player || target instanceof FamilyResidentEntity
                || target instanceof RoxyTutorEntity || target instanceof SylphietteEntity
                || target instanceof FlyingMob || target instanceof WaterAnimal
                || target instanceof EnderDragon || target instanceof WitherBoss
                || !(mob.getNavigation() instanceof GroundPathNavigation)
                || !target.getType().is(TARGETS) || target.level() != owner.level()
                || !target.isAlive() || target.isRemoved() || target.isSpectator() || target.isInvulnerable()
                || target.isPassenger() || target.isVehicle() || target.isNoGravity() || target.noPhysics
                || target.isFallFlying() || target.isInWaterOrBubble() || target.isInLava()
                || target instanceof OwnableEntity owned && owned.getOwnerUUID() != null) return false;
        return !owner.isAlliedTo(target) && !target.isAlliedTo(owner);
    }

    /** Feet, not a tall body intersection, determine contact; the mask is exactly the mask drawn by the client. */
    public static boolean touches(MireEntity field, LivingEntity target) {
        if (target.level() != field.level() || !target.onGround()
                || Math.abs(target.getBoundingBox().minY - field.getY()) > FOOT_TOLERANCE) return false;
        double x = target.getX(), z = target.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || x < field.getX() - MireEntity.HALF_EXTENT || x >= field.getX() + MireEntity.HALF_EXTENT
                || z < field.getZ() - MireEntity.HALF_EXTENT || z >= field.getZ() + MireEntity.HALF_EXTENT) return false;
        BlockPos center = field.blockPosition();
        BlockPos feet = BlockPos.containing(x, field.getY(), z);
        return MireEntity.CELL_OFFSETS.contains(new BlockPos(feet.getX() - center.getX(), 0,
                feet.getZ() - center.getZ()));
    }

    private MireTargets() {}
}
