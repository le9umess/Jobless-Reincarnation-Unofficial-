package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.FireTrainingTrials;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.event.EventHooks;

/** A bounded server-owned sweep, including the precise water surface before any impact effects. */
public final class FireBallProjectile extends Projectile {
    private static final EntityDataAccessor<Integer> FORM_STAGE = SynchedEntityData.defineId(FireBallProjectile.class, EntityDataSerializers.INT);
    public static final int LIFETIME_TICKS = 30;
    private static final double MAX_STEP_SQUARED = 16.0;
    private static final double EPSILON = 1.0E-8;
    private float damage = 5;
    private int burnSeconds = 3;
    private boolean resolved;
    private Vec3 trainingOrigin;
    private LivingEntity releaseCaster;
    private Level releaseLevel;
    private BlockHitResult trainingImpact;

    public FireBallProjectile(EntityType<? extends FireBallProjectile> type, Level level) { super(type, level); }
    public FireBallProjectile(Level level, LivingEntity caster) {
        this(ModContent.FIRE_BALL.get(), level);
        setOwner(caster);
        releaseCaster = caster;
        releaseLevel = level;
        setPos(caster.getX(), caster.getEyeY() - 0.1, caster.getZ());
        setPower(MagicConfig.FIRE_BALL_DAMAGE.get().floatValue(), MagicConfig.FIRE_BALL_BURN_SECONDS.getAsInt());
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(FORM_STAGE, 0); }
    public int formStage() { return entityData.get(FORM_STAGE); }
    public void setFormStage(int stage) { entityData.set(FORM_STAGE, Math.clamp(stage, 0, 2)); }
    @Override public boolean canUsePortal(boolean allowPassengers) { return false; }
    /** Package-private: only a successfully settled FireMagic release grants this runtime receipt. */
    void markTrainingShot(Vec3 origin) {
        if (!level().isClientSide && !resolved && !isRemoved() && trainingOrigin == null && finite(origin)
                && releaseCaster != null && getOwner() == releaseCaster
                && level() == releaseLevel && releaseCaster.level() == releaseLevel)
            trainingOrigin = origin;
    }
    /** A fabricated hit, saved projectile or second call cannot consume a training receipt. */
    public Vec3 consumeTrainingOrigin(BlockHitResult hit) {
        if (hit == null || !resolved || isRemoved() || hit != trainingImpact || releaseCaster == null
                || getOwner() != releaseCaster || level() != releaseLevel || releaseCaster.level() != releaseLevel) return null;
        Vec3 origin = trainingOrigin;
        trainingOrigin = null;
        trainingImpact = null;
        return origin;
    }
    public float damage() { return damage * AttackSpellRules.damageMultiplier(formStage()); }
    public int burnSeconds() { return burnSeconds; }
    public void setPower(float value, int seconds) {
        damage = Float.isFinite(value) ? Math.clamp(value, 0, 1000) : 0;
        burnSeconds = Math.clamp(seconds, 0, 30);
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("FormStage", formStage());
        tag.putFloat("Damage", damage); tag.putInt("BurnSeconds", burnSeconds); tag.putInt("Age", tickCount);
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setFormStage(tag.getInt("FormStage"));
        trainingOrigin = null;
        releaseCaster = null;
        releaseLevel = null;
        trainingImpact = null; // Training eligibility is deliberately not persisted or accepted from NBT.
        setPower(tag.contains("Damage", Tag.TAG_ANY_NUMERIC) ? tag.getFloat("Damage") : 5,
                tag.contains("BurnSeconds", Tag.TAG_ANY_NUMERIC) ? tag.getInt("BurnSeconds") : 3);
        tickCount = Math.clamp(tag.getInt("Age"), 0, LIFETIME_TICKS);
    }
    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
    private boolean loaded(Vec3 from, Vec3 to) {
        AABB box = new AABB(from, to).inflate(1.0);
        int minX = (int)Math.floor(box.minX) >> 4, maxX = (int)Math.floor(box.maxX) >> 4;
        int minZ = (int)Math.floor(box.minZ) >> 4, maxZ = (int)Math.floor(box.maxZ) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            if (!level().hasChunk(x, z)) return false;
        return true;
    }
    private boolean inWaterAt(Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        var fluid = level().getFluidState(pos);
        if (!fluid.is(FluidTags.WATER)) return false;
        Vec3 local = point.subtract(Vec3.atLowerCornerOf(pos));
        return fluid.getShape(level(), pos).toAabbs().stream().anyMatch(box -> box.contains(local));
    }
    private BlockHitResult waterHit(Vec3 from, Vec3 to) {
        return level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, this) {
            @Override public VoxelShape getBlockShape(BlockState state, BlockGetter world, BlockPos pos) { return Shapes.empty(); }
        });
    }
    @Override public void tick() {
        if (resolved || isRemoved()) return;
        Vec3 from = position(), motion = getDeltaMovement(), to = from.add(motion);
        if (!finite(from) || !finite(motion) || !finite(to) || motion.lengthSqr() > MAX_STEP_SQUARED
                || tickCount >= LIFETIME_TICKS || !level().isClientSide && !loaded(from, to)) {
            discard(); return;
        }
        super.tick(); // Projectile lifecycle only; unlike ThrowableProjectile, this does not perform a second sweep.
        if (isRemoved()) return;
        if (level() instanceof ServerLevel) {
            if (inWaterAt(from)) { finish(from, true); return; }
            BlockHitResult solid = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            BlockHitResult water = waterHit(from, to);
            HitResult hit = solid.getType() == HitResult.Type.MISS ? null : solid;
            boolean quenched = false;
            double nearest = hit == null ? Double.POSITIVE_INFINITY : from.distanceToSqr(hit.getLocation());
            if (water.getType() != HitResult.Type.MISS && from.distanceToSqr(water.getLocation()) <= nearest + EPSILON) {
                hit = water; nearest = from.distanceToSqr(water.getLocation()); quenched = true;
            }
            for (Entity candidate : level().getEntities(this, getBoundingBox().expandTowards(motion).inflate(1.0), this::canHitEntity)) {
                AABB bounds = candidate.getBoundingBox().inflate(0.3);
                var crossing = bounds.clip(from, to);
                Vec3 point = bounds.contains(from) ? from : crossing.orElse(null);
                if (point == null) continue;
                double distance = from.distanceToSqr(point);
                // Water or a solid wins equal-distance boundary hits; entity origins are never used as impact locations.
                if (distance + EPSILON < nearest) {
                    hit = new EntityHitResult(candidate, point); nearest = distance; quenched = false;
                }
            }
            if (hit != null) {
                resolved = true; // Consume before invoking external damage/impact hooks.
                boolean hitWasTarget = hit instanceof BlockHitResult blockHit
                        && level().getBlockState(blockHit.getBlockPos()).is(Blocks.TARGET);
                float radius = 0;
                if (!quenched && !EventHooks.onProjectileImpact(this, hit) && !isRemoved()) {
                    if (hit instanceof EntityHitResult entityHit) damageTarget(entityHit.getEntity());
                    else if (hitWasTarget && hit instanceof BlockHitResult blockHit) {
                        trainingImpact = blockHit;
                        try { FireTrainingTrials.onTargetHit(this, blockHit); }
                        finally { trainingImpact = null; trainingOrigin = null; }
                    }
                    radius = AttackSpellImpact.splash(this, hit, SpellCatalog.FIRE_BALL, formStage(), damage(), new java.util.HashSet<>());
                }
                finish(hit.getLocation(), quenched, radius);
                return;
            }
        }
        setPos(to);
        updateRotation();
        setDeltaMovement(motion.scale(0.99));
        if (level().isClientSide && tickCount >= FireTrail.FIRST_TICK) FireTrail.emit(level(), to, motion, tickCount, formStage());
    }
    private void damageTarget(Entity entity) {
        Entity owner = getOwner();
        if (!(entity instanceof LivingEntity target) || entity instanceof ArmorStand || !(owner instanceof LivingEntity caster)
                || !caster.isAlive() || caster.isRemoved() || caster.level() != level() || caster.isSpectator()
                || target.level() != level() || !target.isAlive() || target.isRemoved() || target.isSpectator()) return;
        if (caster instanceof Player attacker && target instanceof Player victim && !attacker.canHarmPlayer(victim)) return;
        float health = target.getHealth(), absorption = target.getAbsorptionAmount();
        boolean accepted = target.hurt(damageSources().source(DamageTypes.FIREBALL, this, caster), damage());
        boolean actuallyDamaged = accepted && (target.getHealth() < health || target.getAbsorptionAmount() < absorption);
        if (actuallyDamaged) AttackSpellImpact.applyDebuff(caster, target, SpellCatalog.FIRE_BALL, formStage());
        if (formStage() < 2 && actuallyDamaged && target.isAlive() && !target.isRemoved() && target.level() == level()
                && !target.fireImmune() && !target.hasEffect(MobEffects.FIRE_RESISTANCE)
                && !target.isInWaterRainOrBubble() && burnSeconds > 0) target.igniteForSeconds(burnSeconds);
    }
    private void finish(Vec3 point, boolean water) { finish(point, water, 0); }
    private void finish(Vec3 point, boolean water, float radius) {
        resolved = true;
        setPos(point);
        if (level() instanceof ServerLevel server) {
            AttackSpellVisuals.send(server, new SpellBurstOptions(water ? SpellBurstOptions.FIRE_QUENCHED : SpellBurstOptions.FIRE_IMPACT, getOwner() == null ? -1 : getOwner().getId(), formStage(), radius), point.x, point.y, point.z, 1, 0, 0, 0, 0);
            server.playSound(null, point.x, point.y, point.z, water ? SoundEvents.FIRE_EXTINGUISH : SoundEvents.FIRECHARGE_USE,
                    SoundSource.PLAYERS, water ? 0.35F : 0.25F, water ? 1.5F : 1.6F);
        }
        discard();
    }
    // Deliberately no block callback, entityInside callback or explosion. No blocks/items are mutated.
}


