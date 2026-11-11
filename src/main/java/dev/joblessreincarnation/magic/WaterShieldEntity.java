package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A short, passable water screen standing on the ground in front of its caster. The server owns it and decides every
 * blocked arrow; it never saves to the world or to a player. Clients only draw it from the synced start time and yaw.
 */
public final class WaterShieldEntity extends Entity {
    public static final int LIFETIME_TICKS = 100;
    public static final double WIDTH = 3.0;
    public static final double HEIGHT = 2.25;
    public static final double THICKNESS = 0.20;
    /** Arrow half-size added on every side, so an arrow grazing an edge still counts. */
    private static final double MARGIN = 0.25;
    /** Furthest an eligible arrow can be and still reach the screen within one tick. */
    static final double REACH = 4.5;
    /** Entity event sent to clients when an arrow is stopped; the server sends the splash itself. */
    public static final byte ARROW_BLOCKED = 1;
    private static final int RIPPLE_TICKS = 10;
    private static final EntityDataAccessor<Long> START_GAME_TIME = SynchedEntityData.defineId(WaterShieldEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Float> SHIELD_YAW = SynchedEntityData.defineId(WaterShieldEntity.class, EntityDataSerializers.FLOAT);
    @Nullable private UUID ownerId;
    /** Server only, never saved: the caster itself, so a logout, death or dimension change ends the screen. */
    @Nullable private ServerPlayer owner;
    /** Client only: game time of the last stopped arrow, for the ripple. */
    private long lastBlockedGameTime = Long.MIN_VALUE;

    public WaterShieldEntity(EntityType<? extends WaterShieldEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    public WaterShieldEntity(ServerLevel level, ServerPlayer owner, Vec3 base, float yaw) {
        this(ModContent.WATER_SHIELD.get(), level);
        this.owner = owner;
        ownerId = owner.getUUID();
        setPos(base.x, base.y, base.z);
        entityData.set(START_GAME_TIME, level.getGameTime());
        entityData.set(SHIELD_YAW, Mth.wrapDegrees(yaw));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(START_GAME_TIME, 0L);
        builder.define(SHIELD_YAW, 0.0F);
    }

    public long startGameTime() { return entityData.get(START_GAME_TIME); }
    public int lifetime() { return LIFETIME_TICKS; }
    public float shieldYaw() { return entityData.get(SHIELD_YAW); }
    public double shieldWidth() { return WIDTH; }
    public double shieldHeight() { return HEIGHT; }
    @Nullable public UUID ownerId() { return ownerId; }

    /** Whole ticks since the screen formed, clamped to its lifetime. */
    public int ageAt(long gameTime) {
        return (int) Math.clamp(gameTime - startGameTime(), 0L, (long) LIFETIME_TICKS);
    }

    /** Smooth age for drawing; uses the synced start, so a late-tracking client sees the same phase. */
    public float visualAge(float partialTick) {
        return Mth.clamp(level().getGameTime() - startGameTime() + partialTick, 0.0F, LIFETIME_TICKS);
    }

    /** 1 right after an arrow is stopped, falling to 0 over a short ripple. */
    public float ripple(float partialTick) {
        if (lastBlockedGameTime == Long.MIN_VALUE) return 0.0F;
        float since = level().getGameTime() - lastBlockedGameTime + partialTick;
        return since < 0.0F || since >= RIPPLE_TICKS ? 0.0F : 1.0F - since / RIPPLE_TICKS;
    }

    /** Unit vector the screen faces: the caster's horizontal look direction. */
    public Vec3 facing() {
        double yaw = Math.toRadians(shieldYaw());
        return new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
    }

    /** Unit vector along the screen's width. */
    public Vec3 across() {
        double yaw = Math.toRadians(shieldYaw());
        return new Vec3(Math.cos(yaw), 0.0, Math.sin(yaw));
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            WaterShieldVisuals.clientTick(this);
            return;
        }
        if (!(level() instanceof ServerLevel server) || owner == null || ageAt(server.getGameTime()) >= LIFETIME_TICKS
                || owner.isRemoved() || !owner.isAlive() || owner.serverLevel() != server) {
            discard();
            return;
        }
        // Check both the path an arrow has just flown and the one it is about to fly, so a fast arrow cannot skip
        // over the thin screen between two ticks, whichever of the two entities ticks first.
        AABB reach = getBoundingBox().inflate(REACH);
        for (AbstractArrow arrow : server.getEntitiesOfClass(AbstractArrow.class, reach, this::stops)) {
            if (!isFlying(arrow)) continue;
            Vec3 now = arrow.position();
            // Until its first tick a new arrow's previous position is still zero, and the line from there is no path it has flown.
            Vec3 point = arrow.tickCount == 0 ? null : crossing(new Vec3(arrow.xo, arrow.yo, arrow.zo), now);
            if (point == null) point = crossing(now, now.add(arrow.getDeltaMovement()));
            if (point != null) block(server, arrow, point);
        }
    }

    /** Arrows this screen stops: ordinary (also tipped) and spectral arrows that its own caster did not shoot. */
    boolean stops(AbstractArrow arrow) {
        if (!arrow.isAlive() || !(arrow instanceof Arrow || arrow instanceof SpectralArrow)) return false;
        return ownerId == null || !(arrow.getOwner() instanceof Entity shooter && ownerId.equals(shooter.getUUID()));
    }

    /** An arrow stuck in a block stays where it is; only one still flying, or just fired, can be stopped. */
    static boolean isFlying(AbstractArrow arrow) {
        return arrow.tickCount <= 1 || arrow.position().distanceToSqr(arrow.xo, arrow.yo, arrow.zo) > 1.0E-4;
    }

    /**
     * Where the straight path {@code from -> to} passes through this screen, or null if it misses. The screen is a
     * vertical WIDTH x HEIGHT rectangle, THICKNESS deep, standing on its base point and facing {@link #facing()}.
     */
    @Nullable
    public Vec3 crossing(Vec3 from, Vec3 to) {
        Vec3 center = position().add(0.0, HEIGHT * 0.5, 0.0);
        Vec3 normal = facing();
        double start = from.subtract(center).dot(normal);
        double end = to.subtract(center).dot(normal);
        double half = THICKNESS * 0.5 + MARGIN;
        if (start > half && end > half || start < -half && end < -half) return null;
        // Meet the screen's middle plane, or, if the path only grazes the slab, its closest end.
        double along = Math.abs(start - end) < 1.0E-9 ? 0.0 : Mth.clamp(start / (start - end), 0.0, 1.0);
        Vec3 point = from.add(to.subtract(from).scale(along));
        Vec3 offset = point.subtract(center);
        if (Math.abs(offset.dot(across())) > WIDTH * 0.5 + MARGIN || Math.abs(offset.y) > HEIGHT * 0.5 + MARGIN) return null;
        return point;
    }

    /** Only the Paul encounter calls this after a native, in-window sword contact. Other melee rules are unchanged. */
    public boolean blockPracticeStrike(ServerPlayer challenger, Vec3 from, Vec3 to) {
        if (!(level() instanceof ServerLevel server) || owner != challenger || !isAlive() || isRemoved()
                || owner.serverLevel()!=server || !owner.isAlive() || ageAt(server.getGameTime())>=LIFETIME_TICKS) return false;
        Vec3 point=crossing(from,to); if(point==null)return false;
        server.sendParticles(ParticleTypes.SPLASH,point.x,point.y,point.z,8,.12,.12,.12,.08);
        server.playSound(null,point.x,point.y,point.z,SoundEvents.GENERIC_SPLASH,SoundSource.PLAYERS,.4F,1.4F);
        server.broadcastEntityEvent(this,ARROW_BLOCKED);return true;
    }

    /** Absorbs the arrow: no bounce, no damage; a splash where it met the water. */
    void block(ServerLevel server, AbstractArrow arrow, Vec3 point) {
        if (!stops(arrow)) return;
        arrow.discard();
        // The impact fallback may run before our next lifecycle tick; never invent an achievement
        // for an expired screen or a caster who has already left, died or changed dimension.
        if (isAlive() && level() == server && ageAt(server.getGameTime()) < LIFETIME_TICKS
                && owner != null && owner.serverLevel() == server) {
            StoryAdvancements.onWaterShieldBlocked(owner);
        }
        server.sendParticles(ParticleTypes.SPLASH, point.x, point.y, point.z, 12, 0.15, 0.15, 0.15, 0.10);
        server.playSound(null, point.x, point.y, point.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 0.40F, 1.40F);
        server.broadcastEntityEvent(this, ARROW_BLOCKED);
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == ARROW_BLOCKED) {
            lastBlockedGameTime = level().getGameTime();
        } else {
            super.handleEntityEvent(id);
        }
    }

    @Override protected void addAdditionalSaveData(CompoundTag tag) {}
    @Override protected void readAdditionalSaveData(CompoundTag tag) { discard(); }
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean canUsePortal(boolean allowPassengers) { return false; }
}
