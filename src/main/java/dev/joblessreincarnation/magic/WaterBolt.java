package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingTrials;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class WaterBolt extends ThrowableItemProjectile {
    private static final EntityDataAccessor<Integer> FORM_STAGE = SynchedEntityData.defineId(WaterBolt.class, EntityDataSerializers.INT);
    public static final int LIFETIME_TICKS = 30;
    private static final EntityDataAccessor<Float> CHARGE = SynchedEntityData.defineId(WaterBolt.class, EntityDataSerializers.FLOAT);
    private float damage = WaterControl.damage(0.0F);
    private Vec3 trainingOrigin;
    private boolean resolved;

    public WaterBolt(EntityType<? extends WaterBolt> type, Level level) { super(type, level); }
    public WaterBolt(Level level, LivingEntity caster) { super(ModContent.WATER_BOLT.get(), caster, level); }

    // Legacy item data remains readable; the client now renders a dedicated water shape.
    @Override
    protected Item getDefaultItem() { return Items.HEART_OF_THE_SEA; }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CHARGE, 0.0F);
        builder.define(FORM_STAGE, 0);
    }

    public int formStage() { return entityData.get(FORM_STAGE); }
    public void setFormStage(int stage) { entityData.set(FORM_STAGE, Math.clamp(stage, 0, 2)); }

    public float charge() { return entityData.get(CHARGE); }
    public float getDamage() { return damage * AttackSpellRules.damageMultiplier(formStage()); }

    /** Mark only normal survival casts, using the release position rather than the later owner position. */
    public void markTrainingShot() { trainingOrigin = position(); }

    public void setCharge(float charge) {
        float normalized = WaterControl.normalize(charge);
        entityData.set(CHARGE, normalized);
        damage = WaterControl.damage(normalized);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("Charge", charge());
        tag.putInt("FormStage", formStage());
        tag.putFloat("Damage", damage);
        tag.putInt("Age", tickCount);
        if (trainingOrigin != null) {
            tag.putBoolean("TrainingShot", true);
            tag.putDouble("TrainingX", trainingOrigin.x);
            tag.putDouble("TrainingY", trainingOrigin.y);
            tag.putDouble("TrainingZ", trainingOrigin.z);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setFormStage(tag.getInt("FormStage"));
        setCharge(tag.getFloat("Charge"));
        float savedDamage = tag.getFloat("Damage");
        if (tag.contains("Damage", Tag.TAG_ANY_NUMERIC) && Float.isFinite(savedDamage)) {
            damage = Math.clamp(savedDamage, 0.0F, 2000.0F);
        }
        tickCount = Math.clamp(tag.getInt("Age"), 0, LIFETIME_TICKS);
        trainingOrigin = null;
        if (tag.getBoolean("TrainingShot") && tag.contains("TrainingX", Tag.TAG_ANY_NUMERIC)
                && tag.contains("TrainingY", Tag.TAG_ANY_NUMERIC) && tag.contains("TrainingZ", Tag.TAG_ANY_NUMERIC)) {
            double x = tag.getDouble("TrainingX");
            double y = tag.getDouble("TrainingY");
            double z = tag.getDouble("TrainingZ");
            if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) trainingOrigin = new Vec3(x, y, z);
        }
    }

    @Override
    protected double getDefaultGravity() { return 0.0; }

    @Override
    public boolean canUsePortal(boolean allowPassengers) { return false; }

    @Override
    public void tick() {
        if (resolved || isRemoved()) return;
        super.tick();
        if (isRemoved()) return;
        if (level().isClientSide) {
            if (tickCount >= WaterTrail.FIRST_TICK) WaterTrail.emit(level(), position().add(0.0, getBbHeight() * 0.5, 0.0), getDeltaMovement(), charge(), tickCount, formStage());
        } else if (tickCount >= LIFETIME_TICKS) {
            discard();
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        if (!level().isClientSide && trainingOrigin != null) {
            Vec3 origin = trainingOrigin;
            trainingOrigin = null;
            TrainingTrials.onTargetHit(this, hit, origin);
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (level().isClientSide) return;
        Entity target = hit.getEntity();
        Entity caster = getOwner();
        if (target instanceof LivingEntity living) {
            // Keep the original direct-hit rules; collateral damage has its own stricter target selection.
            if (caster instanceof Player attacker && living instanceof Player victim && !attacker.canHarmPlayer(victim)) return;
            float health = living.getHealth(), absorption = living.getAbsorptionAmount();
            boolean accepted = living.hurt(damageSources().thrown(this, caster), getDamage());
            boolean loss = living.getHealth() < health || living.getAbsorptionAmount() < absorption;
            living.clearFire();
            if (accepted && loss) {
                living.removeEffect(ModContent.SEARING);
                if (caster instanceof LivingEntity owner) AttackSpellImpact.applyDebuff(owner, living, SpellCatalog.WATER_BOLT, formStage());
            }
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        if (resolved || isRemoved()) return;
        if (level() instanceof ServerLevel server) {
            resolved = true;
            super.onHit(hit);
            float radius = AttackSpellImpact.splash(this, hit, SpellCatalog.WATER_BOLT, formStage(), getDamage(), new java.util.HashSet<>());
            // Count 0 retains the water charge; radius in the option describes the actual damage footprint.
            AttackSpellVisuals.send(server, new SpellBurstOptions(SpellBurstOptions.WATER_IMPACT, getOwner() == null ? -1 : getOwner().getId(), formStage(), radius), hit.getLocation().x, hit.getLocation().y, hit.getLocation().z,
                    0, charge(), 0.0, 0.0, 1.0);
            server.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.35F, 1.6F);
            discard();
        }
    }
}
