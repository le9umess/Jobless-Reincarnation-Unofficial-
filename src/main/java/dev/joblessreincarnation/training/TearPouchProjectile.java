package dev.joblessreincarnation.training;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.SchoolSkills;
import dev.joblessreincarnation.magic.SpellBurstOptions;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class TearPouchProjectile extends ThrowableItemProjectile {
    private boolean trained;

    public TearPouchProjectile(EntityType<? extends TearPouchProjectile> type, Level level) { super(type, level); }

    public TearPouchProjectile(ServerPlayer owner, boolean trained) {
        super(ModContent.TEAR_POUCH_PROJECTILE.get(), owner, owner.level());
        this.trained = trained;
    }

    @Override protected Item getDefaultItem() { return ModContent.TEAR_POUCH.get(); }
    @Override public boolean canUsePortal(boolean allowPassengers) { return false; }

    @Override protected boolean canHitEntity(net.minecraft.world.entity.Entity target) {
        // Dummies reject all vanilla projectiles. Only this harmless practice pouch may contact one.
        return target instanceof PracticeDummyEntity dummy ? !dummy.isRemoved() && dummy.hasSupport() : super.canHitEntity(target);
    }

    @Override public void tick() {
        if (!level().isClientSide && tickCount >= 40) { discard(); return; }
        super.tick();
    }

    @Override protected void onHit(HitResult hit) {
        if (!(level() instanceof ServerLevel level) || isRemoved()) return;
        Vec3 puff = hit.getLocation();
        LivingEntity affected = null;
        int kind = SpellBurstOptions.TEAR_ASH;
        if (hit instanceof EntityHitResult contact && getOwner() instanceof ServerPlayer owner) {
            // ThrowableProjectile's 1.21.1 entity result stores the target's feet. Recover the swept contact
            // used by ProjectileUtil, including its 0.3-block margin, so eye-height and leg shots differ.
            var bounds = contact.getEntity().getBoundingBox().inflate(0.3F);
            Vec3 start = position();
            puff = bounds.contains(start) ? start : bounds.clip(start, start.add(getDeltaMovement())).orElse(start);
            if (contact.getEntity() instanceof PracticeDummyEntity dummy && dummy.hasSupport()) {
                SchoolSkills.completeDrill(owner, SchoolForm.TEAR_POUCH);
            } else if (trained && contact.getEntity() instanceof LivingEntity target && canObscure(target, owner, puff)) {
                boolean guarded = target instanceof ServerPlayer p && BattleAura.hasBodyGuard(p);
                int ticks = guarded ? 6 : 16;
                var dust = new MobEffectInstance(MobEffects.BLINDNESS, ticks, 0, false, false, false);
                if (target.canBeAffected(dust) && (target.addEffect(dust, owner) || target.hasEffect(MobEffects.BLINDNESS))) {
                    if (target instanceof Mob mob) {
                        mob.setData(ModAttachments.DUST_IN_EYES_UNTIL, Math.max(mob.getData(ModAttachments.DUST_IN_EYES_UNTIL), level.getGameTime() + ticks));
                        mob.setTarget(null);
                    }
                    affected = target;
                    kind = guarded ? SpellBurstOptions.TEAR_ASH_GUARDED : SpellBurstOptions.TEAR_ASH;
                    puff = target.getEyePosition();
                }
            }
        }
        SchoolCues.send(level, kind, affected, puff);
        discard();
    }

    private boolean canObscure(LivingEntity target, ServerPlayer owner, Vec3 point) {
        if (!(target instanceof Mob || target instanceof ServerPlayer) || !target.isAlive() || target == owner || target.isSpectator() || target instanceof Warden || target.isBlocking()
                || target.isAlliedTo(owner) || target.isInvulnerableTo(damageSources().thrown(this, owner))) return false;
        if (target instanceof ServerPlayer p && (p.getAbilities().invulnerable || !owner.canHarmPlayer(p))) return false;
        Vec3 forward = target.getViewVector(1).multiply(1, 0, 1).normalize();
        Vec3 incoming = getDeltaMovement().multiply(-1, 0, -1).normalize();
        return point.y >= target.getEyeY() - Math.min(.40, target.getBbHeight() * .25) && point.y <= target.getEyeY() + .35
                && forward.dot(incoming) >= .35;
    }

    @SubscribeEvent
    public static void duringObscuration(LivingChangeTargetEvent event) {
        LivingEntity victim = event.getEntity();
        if (event.getNewAboutToBeSetTarget() != null && victim.hasData(ModAttachments.DUST_IN_EYES_UNTIL)) {
            if (victim.level().getGameTime() < victim.getData(ModAttachments.DUST_IN_EYES_UNTIL)) event.setCanceled(true);
            else victim.removeData(ModAttachments.DUST_IN_EYES_UNTIL);
        }
    }
}
