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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A passable, unsaved ground drawing. All slowing is resolved by MireFields once per server-level tick, including
 * fields whose own entity tick has stopped. Position and start time completely describe the fixed footprint.
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

    public List<BlockPos> surfaceCells() {
        BlockPos center = blockPosition();
        return CELL_OFFSETS.stream().map(center::offset).toList();
    }

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