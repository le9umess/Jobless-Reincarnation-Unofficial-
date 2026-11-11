package dev.joblessreincarnation.training;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/** A persistent, immovable practice prop. It has no health, AI, shared lesson progress or death loot. */
public final class PracticeDummyEntity extends Entity implements GeoEntity {
    public static final String CONTROLLER = "practice";
    public static final String HIT_TRIGGER = "hit";
    public static final int HIT_ANIMATION_TICKS = 8;
    public static final int VISUAL_INTERVAL_TICKS = 4;
    public static final int DROP_RETRY_TICKS = 20;
    private static final String OWNER_TAG = "JR_PracticeOwner";
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.practice_dummy.idle");
    private static final RawAnimation HIT = RawAnimation.begin().thenPlay("animation.practice_dummy.hit_candidate");
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    @Nullable private UUID ownerId;
    private boolean removalInProgress;
    private long lastDropAttempt = Long.MIN_VALUE;
    private long lastVisualStart = Long.MIN_VALUE;
    private long reactionUntil = Long.MIN_VALUE;

    public PracticeDummyEntity(EntityType<? extends PracticeDummyEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    @Nullable public UUID ownerId() { return ownerId; }

    /** Called before world admission by the placement item; no player object is retained. */
    public void setOwnerId(@Nullable UUID ownerId) {
        this.ownerId = ownerId;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) { }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (ownerId != null) tag.putUUID(OWNER_TAG, ownerId);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        ownerId = tag.hasUUID(OWNER_TAG) ? tag.getUUID(OWNER_TAG) : null;
        removalInProgress = false;
        lastDropAttempt = Long.MIN_VALUE;
        lastVisualStart = Long.MIN_VALUE;
        reactionUntil = Long.MIN_VALUE;
        setNoGravity(true);
        setDeltaMovement(Vec3.ZERO);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (removalInProgress || !SwordPractice.tryHit(this, source, amount)) return false;
        react();
        // A non-LivingEntity never enters SwordItem.hurtEnemy/postHurtEnemy, so practice costs no durability.
        return true;
    }

    private void react() {
        if (!(level() instanceof ServerLevel server)) return;
        long now = server.getGameTime();
        if (SwordPractice.withinInterval(now, lastVisualStart, VISUAL_INTERVAL_TICKS)) return;
        lastVisualStart = now;
        reactionUntil = now + HIT_ANIMATION_TICKS;
        triggerAnim(CONTROLLER, HIT_TRIGGER);
        server.playSound(null, getX(), getY(), getZ(), SoundEvents.ARMOR_STAND_HIT, SoundSource.BLOCKS, 0.5F, 0.9F);
    }

    @Override
    public void tick() {
        setDeltaMovement(Vec3.ZERO);
        super.tick();
        setDeltaMovement(Vec3.ZERO);
        if (!(level() instanceof ServerLevel server) || isRemoved() || removalInProgress
                || !server.getServer().isSameThread() || server.getEntity(getUUID()) != this) return;
        long now = server.getGameTime();
        if (reactionUntil != Long.MIN_VALUE && (now >= reactionUntil || now < lastVisualStart)) {
            reactionUntil = Long.MIN_VALUE;
            stopTriggeredAnim(CONTROLLER, HIT_TRIGGER);
        }
        // Missing adjacent chunks are unknown support, never a reason to force-load or drop the prop.
        if (!chunksReady(server, getBoundingBox())) return;
        if (!hasSupport() && !SwordPractice.withinInterval(now, lastDropAttempt, DROP_RETRY_TICKS)) {
            lastDropAttempt = now;
            dropAfterSupportLoss(server);
        }
    }

    /** True only for a fully loaded dry, solid top face directly beneath this fixed prop. */
    public boolean hasSupport() {
        if (!(level() instanceof ServerLevel server) || isRemoved()
                || !chunksReady(server, getBoundingBox())) return false;
        BlockPos support = supportPosition();
        AABB box = getBoundingBox();
        return Math.abs(getY() - (support.getY() + 1.0)) < 1.0E-5
                && box.minY >= server.getMinBuildHeight() && box.maxY <= server.getMaxBuildHeight()
                && server.getWorldBorder().isWithinBounds(box)
                && validSupport(server, support) && dryBox(server, box);
    }

    private BlockPos supportPosition() {
        return BlockPos.containing(getX(), getY() - 0.01, getZ());
    }

    static boolean validSupport(ServerLevel level, BlockPos support) {
        if (level.isOutsideBuildHeight(support) || !level.getWorldBorder().isWithinBounds(support)) return false;
        BlockState state = level.getBlockState(support);
        return state.getFluidState().isEmpty() && state.isFaceSturdy(level, support, Direction.UP)
                && Block.isFaceFull(state.getCollisionShape(level, support), Direction.UP);
    }

    static boolean dryBox(ServerLevel level, AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX - 1.0E-7), Mth.floor(box.maxY - 1.0E-7), Mth.floor(box.maxZ - 1.0E-7))) {
            if (!level.getFluidState(pos).isEmpty()) return false;
        }
        return true;
    }

    /** Same non-loading margin as NPC collision queries, kept local because their helper is package-private. */
    static boolean chunksReady(ServerLevel level, AABB box) {
        if (!level.getServer().isSameThread() || !Double.isFinite(box.minX) || !Double.isFinite(box.minY)
                || !Double.isFinite(box.minZ) || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY)
                || !Double.isFinite(box.maxZ)) return false;
        int minX = SectionPos.blockToSectionCoord(Mth.floor(box.minX - 1.0E-7) - 1);
        int maxX = SectionPos.blockToSectionCoord(Mth.floor(box.maxX + 1.0E-7) + 1);
        int minZ = SectionPos.blockToSectionCoord(Mth.floor(box.minZ - 1.0E-7) - 1);
        int maxZ = SectionPos.blockToSectionCoord(Mth.floor(box.maxZ + 1.0E-7) + 1);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
                if (holder == null || !(holder.getChunkIfPresent(ChunkStatus.FULL) instanceof LevelChunk)) return false;
            }
        }
        return true;
    }

    private void dropAfterSupportLoss(ServerLevel server) {
        removalInProgress = true;
        ItemEntity drop = new ItemEntity(server, getX(), getY(), getZ(),
                ModContent.PRACTICE_DUMMY_ITEM.get().getDefaultInstance());
        drop.setDefaultPickUpDelay();
        boolean admitted = false;
        try {
            admitted = server.addFreshEntity(drop);
        } catch (RuntimeException failure) {
            // onAddedToLevel may throw after admission. Keep the admitted item and retire this prop exactly once.
            admitted = server.getEntity(drop.getUUID()) == drop && !drop.isRemoved();
            JoblessReincarnation.LOGGER.warn("Could not finish spawning a recovered practice dummy at {}", position(), failure);
        } finally {
            admitted = admitted && !drop.isRemoved() && server.getEntity(drop.getUUID()) == drop;
            if (admitted) {
                discard();
                server.playSound(null, getX(), getY(), getZ(), SoundEvents.ARMOR_STAND_BREAK, SoundSource.BLOCKS, 0.75F, 1.0F);
            }
            else {
                drop.discard();
                removalInProgress = false;
            }
        }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return reclaimInteraction(player, hand);
    }

    @Override
    public InteractionResult interactAt(Player player, Vec3 position, InteractionHand hand) {
        return reclaimInteraction(player, hand);
    }

    private InteractionResult reclaimInteraction(Player actor, InteractionHand hand) {
        if (!level().isClientSide && actor instanceof ServerPlayer player
                && hand == InteractionHand.MAIN_HAND && player.getMainHandItem().isEmpty() && player.isShiftKeyDown()) {
            reclaim(player);
        }
        // Consume both native paths, including invalid/offhand requests; never fall through to a held item.
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    private void reclaim(ServerPlayer player) {
        if (!(level() instanceof ServerLevel server) || removalInProgress || isRemoved()
                || server.getEntity(getUUID()) != this || !SwordPractice.validActor(player, server)
                || !player.canInteractWithEntity(this, 0.0) || !player.hasLineOfSight(this)) return;
        if (!allowsBuilding(player) || !server.mayInteract(player, supportPosition())
                || !server.mayInteract(player, blockPosition())) {
            SwordPractice.feedback(player, "not_allowed");
            return;
        }
        if (!player.getUUID().equals(ownerId) && !(player.isCreative() && player.hasPermissions(2))) {
            SwordPractice.feedback(player, "not_your_dummy");
            return;
        }
        removalInProgress = true;
        try {
            if (!player.isCreative()) {
                ItemStack returned = ModContent.PRACTICE_DUMMY_ITEM.get().getDefaultInstance();
                if (!player.getInventory().add(returned) || !returned.isEmpty()) {
                    SwordPractice.feedback(player, "inventory_full");
                    return;
                }
            }
            // Retire before packets/sounds to make a second native interaction unable to return another item.
            discard();
            server.playSound(null, getX(), getY(), getZ(), SoundEvents.ARMOR_STAND_BREAK, SoundSource.BLOCKS, 0.75F, 1.0F);
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastFullState();
            SwordPractice.feedback(player, "reclaimed");
        } finally {
            if (!isRemoved()) removalInProgress = false;
        }
    }

    static boolean allowsBuilding(ServerPlayer player) {
        GameType mode = player.gameMode.getGameModeForPlayer();
        return player.mayBuild() && (mode == GameType.SURVIVAL || mode == GameType.CREATIVE);
    }

    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeHitByProjectile() { return false; }
    @Override public void push(Entity entity) { }
    @Override public void push(double x, double y, double z) { }
    @Override public void move(MoverType type, Vec3 movement) { }
    @Override public PushReaction getPistonPushReaction() { return PushReaction.IGNORE; }
    @Override public boolean startRiding(Entity vehicle, boolean force) { return false; }
    @Override protected boolean canAddPassenger(Entity passenger) { return false; }
    @Override protected boolean couldAcceptPassenger() { return false; }
    @Override public boolean canUsePortal(boolean allowPassengers) { return false; }
    @Override public boolean canChangeDimensions(Level oldLevel, Level newLevel) { return false; }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new DummyHitAnimationController(this, IDLE, HIT));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animationCache;
    }
}