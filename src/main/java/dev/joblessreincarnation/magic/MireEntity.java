package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A passable, unsaved ground drawing. All slowing is resolved by MireFields once per server-level tick, including
 * fields whose own entity tick has stopped. Both sides derive the surface from the same block footprint.
 */
public final class MireEntity extends Entity {
    public static final double RADIUS = 2.5;
    public static final double HALF_EXTENT = RADIUS;
    public static final int LIFETIME_TICKS = 120;
    public static final int FORM_TICKS = 10;
    public static final int FADE_TICKS = 10;
    public static final List<BlockPos> CELL_OFFSETS = createOffsets();
    private static final EntityDataAccessor<Long> START_GAME_TIME =
            SynchedEntityData.defineId(MireEntity.class, EntityDataSerializers.LONG);
    @Nullable private ServerPlayer owner;
    @Nullable private UUID ownerId;
    private boolean effectReported;
    private boolean ended;
    @Nullable private MireGround.Surface surface;
    private long surfaceTick = Long.MIN_VALUE;

    public MireEntity(EntityType<? extends MireEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    public MireEntity(ServerLevel level, ServerPlayer owner, Vec3 base) {
        this(ModContent.MIRE.get(), level);
        this.owner = owner;
        this.ownerId = owner.getUUID();
        setPos(base.x, base.y, base.z);
        entityData.set(START_GAME_TIME, level.getGameTime());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(START_GAME_TIME, 0L);
    }

    @Nullable public ServerPlayer owner() { return owner; }
    @Nullable public UUID ownerId() { return ownerId; }
    boolean ended() { return ended; }
    void markEnded() { ended = true; }
    public long startGameTime() { return entityData.get(START_GAME_TIME); }

    /** Smooth shared age for rendering, including clients that start tracking an already formed field. */
    public float ageTicks(float partialTick) {
        return Mth.clamp(level().getGameTime() - startGameTime() + partialTick, 0.0F, LIFETIME_TICKS);
    }

    public boolean activeAt(long gameTime) {
        long elapsed = gameTime - startGameTime();
        return elapsed >= FORM_TICKS && elapsed < LIFETIME_TICKS - FADE_TICKS;
    }

    /** Re-read immediately at admission and before each server contact sweep, including multiple sweeps in one tick. */
    @Nullable
    MireGround.Surface refreshSurface() {
        surface = MireGround.sample(level(), position());
        surfaceTick = level().getGameTime();
        return surface;
    }

    @Nullable
    public MireGround.Surface surface() {
        if (surfaceTick != level().getGameTime()) refreshSurface();
        return surface;
    }

    /** Absolute standing height in the current footprint; an absent cell has no contact surface. */
    public double surfaceY(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || x < getX() - HALF_EXTENT || x >= getX() + HALF_EXTENT
                || z < getZ() - HALF_EXTENT || z >= getZ() + HALF_EXTENT) return Double.NaN;
        int dx = Mth.floor(x) - getBlockX(), dz = Mth.floor(z) - getBlockZ();
        if (dx * dx + dz * dz > RADIUS * RADIUS) return Double.NaN;
        MireGround.Surface shape = surface();
        return shape == null ? Double.NaN : getY() + shape.offsetAt(dx, dz);
    }

    public List<BlockPos> surfaceCells() {
        BlockPos center = blockPosition();
        MireGround.Surface shape = surface();
        if (shape == null) return List.of();
        return CELL_OFFSETS.stream().map(cell -> center.offset(cell.getX(), shape.offsetAt(cell.getX(), cell.getZ()), cell.getZ())).toList();
    }

    /** The entity remains passable; ensure its raised or lowered patches can still enter the camera frustum. */
    @Override public AABB getBoundingBoxForCulling() { return getBoundingBox().inflate(0.0, 1.0, 0.0); }

    /** One successful contact notification per paid field, irrespective of how many overlapping sources exist. */
    boolean reportFirstEffect() {
        if (effectReported) return false;
        effectReported = true;
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            MireVisuals.clientTick(this);
            return;
        }
        if (!(level() instanceof ServerLevel server) || ended || !MireFields.isRegistered(this)
                || owner == null || owner.isRemoved() || !owner.isAlive() || owner.serverLevel() != server
                || server.getGameTime() < startGameTime()
                || server.getGameTime() - startGameTime() >= LIFETIME_TICKS) discard();
    }

    private static List<BlockPos> createOffsets() {
        List<BlockPos> cells = new ArrayList<>(21);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            if (x * x + z * z <= RADIUS * RADIUS) cells.add(new BlockPos(x, 0, z));
        }
        return List.copyOf(cells);
    }

    @Override protected void addAdditionalSaveData(CompoundTag tag) {}
    @Override protected void readAdditionalSaveData(CompoundTag tag) { discard(); }
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean canUsePortal(boolean allowPassengers) { return false; }
}
