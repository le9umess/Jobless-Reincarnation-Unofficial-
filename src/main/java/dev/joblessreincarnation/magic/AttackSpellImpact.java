package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.npc.SylphietteEntity;
import dev.joblessreincarnation.registry.ModContent;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;

/** Accepted server damage is the only path to the new attack debuffs. */
public final class AttackSpellImpact {
    private static final int MAX_SPLASH_TARGETS = 32;

    public static boolean canAffect(LivingEntity caster, LivingEntity target) {
        if (!caster.isAlive() || caster.isRemoved() || caster.isSpectator()
                || !target.isAlive() || target.isRemoved() || target.isSpectator() || target == caster
                || target.level() != caster.level() || target instanceof ArmorStand
                || target instanceof RoxyTutorEntity || target instanceof SylphietteEntity) return false;
        if (!dev.joblessreincarnation.encounter.PaulDuel.mayAffect(caster,target)) return false;
        if (target instanceof FamilyResidentEntity) return dev.joblessreincarnation.encounter.PaulDuel.spellTarget(caster,target);
        if (caster instanceof Player attacker && target instanceof Player victim)
            return attacker.canHarmPlayer(victim) && (!(caster.level() instanceof ServerLevel server) || server.getServer().isPvpAllowed());
        if (caster.isAlliedTo(target) || target.isAlliedTo(caster)) return false;
        return !(target instanceof OwnableEntity pet && pet.getOwnerUUID() != null);
    }

    public static Entity body(Entity entity) { return entity instanceof PartEntity<?> part ? part.getParent() : entity; }

    private static DamageSource damageSource(Projectile source, LivingEntity caster, SpellCatalog spell) {
        return spell == SpellCatalog.FIRE_BALL ? source.damageSources().source(DamageTypes.FIREBALL, source, caster)
                : source.damageSources().thrown(source, caster);
    }

    static boolean damage(Projectile source, Entity hit, float amount, SpellCatalog spell, int stage) {
        if (!(source.getOwner() instanceof LivingEntity caster) || !(body(hit) instanceof LivingEntity target)
                || source.level() != caster.level() || !canAffect(caster, target)) return false;
        float health = target.getHealth(), absorption = target.getAbsorptionAmount();
        float practiceScore = dev.joblessreincarnation.encounter.PaulDuel.score(target);
        boolean accepted = hit.hurt(damageSource(source, caster, spell), amount);
        boolean loss = target.getHealth() < health || target.getAbsorptionAmount() < absorption
                || practiceScore >= 0 && dev.joblessreincarnation.encounter.PaulDuel.score(target) < practiceScore;
        if (accepted && loss) applyDebuff(caster, target, spell, stage);
        return accepted && loss;
    }

    static void applyDebuff(LivingEntity caster, LivingEntity target, SpellCatalog spell, int stage) {
        if (stage != 2 || !canAffect(caster, target)) return;
        if (spell == SpellCatalog.WATER_BOLT)
            target.addEffect(new MobEffectInstance(ModContent.SLUGGISH, MagicConfig.SLUGGISH_TICKS.getAsInt(), 0), caster);
        else if (spell == SpellCatalog.FIRE_BALL) SearingEffect.apply(caster, target);
    }

    static Vec3 centre(HitResult hit) {
        if (hit instanceof BlockHitResult block)
            return hit.getLocation().add(Vec3.atLowerCornerOf(block.getDirection().getNormal()).scale(.01));
        return hit.getLocation();
    }

    private static double distance(Vec3 from, LivingEntity target) {
        AABB box = target.getBoundingBox();
        return from.distanceTo(new Vec3(Math.clamp(from.x, box.minX, box.maxX),
                Math.clamp(from.y, box.minY, box.maxY), Math.clamp(from.z, box.minZ, box.maxZ)));
    }

    private static boolean visible(Projectile source, Vec3 from, LivingEntity target) {
        var level = source.level();
        Vec3 to = target.getBoundingBox().getCenter();
        BlockPos unloaded = BlockGetter.traverseBlocks(from, to, level,
                (world, pos) -> world.hasChunkAt(pos) ? null : pos.immutable(), world -> null);
        return unloaded == null && level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source)).getType() == HitResult.Type.MISS;
    }

    /** Returns the actual radius for the matching visual message. A direct victim is excluded from that pulse. */
    static float splash(Projectile source, HitResult hit, SpellCatalog spell, int stage, float directDamage, Set<UUID> splashed) {
        float radius = AttackSpellRules.splashRadius(spell, stage);
        if (radius <= 0 || !(source.level() instanceof ServerLevel level)
                || !(source.getOwner() instanceof LivingEntity caster) || !caster.isAlive() || caster.level() != level) return 0;
        Vec3 from = centre(hit);
        Entity direct = hit instanceof net.minecraft.world.phys.EntityHitResult entityHit ? body(entityHit.getEntity()) : null;
        var targets = level.getEntitiesOfClass(LivingEntity.class, new AABB(from, from).inflate(radius),
                target -> target != direct && canAffect(caster, target) && distance(from, target) <= radius).stream()
                .sorted(Comparator.comparingDouble((LivingEntity target) -> distance(from, target)).thenComparingInt(Entity::getId))
                .limit(MAX_SPLASH_TARGETS).toList();
        for (LivingEntity target : targets) {
            // Earlier damage/effect hooks can change teams, positions, lives or even the projectile owner.
            if (source.isRemoved() || source.getOwner() != caster || source.level() != level) break;
            if (!canAffect(caster, target) || target == direct || distance(from, target) > radius
                    || splashed.contains(target.getUUID()) || !visible(source, from, target)) continue;
            float amount = (float)(directDamage * MagicConfig.SPLASH_DAMAGE_RATIO.get() * (1 - .5 * distance(from, target) / radius));
            splashed.add(target.getUUID());
            boolean applied = damage(source, target, amount, spell, stage);
            if (applied && spell == SpellCatalog.WATER_BOLT && canAffect(caster, target)) {
                target.clearFire();
                target.removeEffect(ModContent.SEARING);
            }
        }
        return radius;
    }
    private AttackSpellImpact() {}
}
