package dev.joblessreincarnation.npc;

import java.util.EnumSet;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Sylphiette as a child, waiting under the big tree on a hill in Buena Village where Rudeus first met her
 * (novel index_split_010 P102-106, P348-360). Persistent, non-combat and staying near the spot where she first
 * landed. The home, protection, floor-only stroll and conversation pause are the Greyrat family's
 * ({@link FamilyResidentEntity}), kept as a separate class so the released residents are untouched. Her first tree
 * lesson adds a transient attempt gesture while leaving the persistent resident state unchanged. Meeting her while
 * Roxy still teaches at the Greyrat home, her home radius and the pause are game adaptations.
 */
public final class SylphietteEntity extends PathfinderMob implements GeoEntity {
    /** Entity, model and language id. */
    public static final String ID = "sylphiette";
    public static final int HOME_RADIUS = 3;
    public static final int CONVERSATION_TICKS = 40;
    /** Her first-lesson attempt, a one-shot gesture on the gesture controller; timing in SylphietteLessonVisuals. */
    public static final String ATTEMPT = "attempt";
    public static final int RETURN_RETRY_TICKS = 40;
    private static final String ANCHOR_TAG = "JR_HomeAnchor";
    /** Tallest thing that still counts as floor: a carpet. */
    private static final double FLOOR_COVER = 1.0 / 16.0;
    /** Step while strolling: enough for a carpet, too low for a root, a slab or a step of the hill. */
    private static final float STROLL_STEP = 2.0F / 16.0F;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    @Nullable private GlobalPos homeAnchor;
    @Nullable private UUID conversationTarget;
    private long conversationUntil;
    /** Whose first lesson she is watching or trying, and until when; transient, like the lesson itself. */
    @Nullable private UUID lessonTarget;
    private long lessonUntil;
    private final NpcGreetingState greeting = new NpcGreetingState();
    private boolean strolling;

    public SylphietteEntity(EntityType<? extends SylphietteEntity> type, Level level) {
        super(type, level);
        setCustomName(Component.translatable("entity.jobless_reincarnation." + ID));
        setCustomNameVisible(true);
        setPersistenceRequired();
        setCanPickUpLoot(false);
        setInvulnerable(true);
        xpReward = 0;
        if (getNavigation() instanceof GroundPathNavigation ground) {
            // She never opens doors herself; a door a player leaves open is passable.
            ground.setCanOpenDoors(false);
            ground.setCanPassDoors(true);
        }
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.2)
            .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    public int homeRadius() { return HOME_RADIUS; }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new ConversationGoal());
        goalSelector.addGoal(2, new ReturnHomeGoal());
        goalSelector.addGoal(3, new HomeStrollGoal());
        goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(5, new RandomLookAroundGoal(this));
    }

    /** Server state only; clients never need her home. */
    @Nullable public GlobalPos getHomeAnchor() { return homeAnchor; }

    /** Anchors at the current spot when it is a legal standing place; used on landing and by the admin command. */
    public boolean reanchor() {
        if (!(level() instanceof ServerLevel server) || !server.getServer().isSameThread() || !isAddedToLevel() || !isAlive() || !legalCurrentPosition()) return false;
        homeAnchor = GlobalPos.of(level().dimension(), blockPosition().immutable());
        restrictTo(homeAnchor.pos(), homeRadius());
        stopWalking();
        return true;
    }

    private boolean legalCurrentPosition() {
        Vec3 pos = position();
        if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) return false;
        AABB box = getBoundingBox();
        if (box.minY < level().getMinBuildHeight() || box.maxY > level().getMaxBuildHeight() || !validAnchorPosition(blockPosition()) || !level().getWorldBorder().isWithinBounds(box)
            || !loadedBox(box.inflate(0.1)) || !level().noCollision(this, box)) return false;
        // Require actual support, including slabs and carpets, rather than a serialized onGround bit.
        return level().getBlockCollisions(this, box.move(0.0, -0.05, 0.0)).iterator().hasNext();
    }

    private boolean validAnchorPosition(BlockPos pos) {
        return !level().isOutsideBuildHeight(pos) && level().getWorldBorder().isWithinBounds(pos);
    }

    /** Never waits for a chunk, so it is also safe while a natural meeting spot is still loading; see {@link NpcLoadedChunks}. */
    private boolean loadedBox(AABB box) { return NpcLoadedChunks.ready(level(), box); }

    private void ensureAnchor() {
        if (homeAnchor != null && (!homeAnchor.dimension().equals(level().dimension())
            || !validAnchorPosition(homeAnchor.pos()))) {
            homeAnchor = null;
            clearRestriction();
            stopWalking();
        }
        if (homeAnchor == null) reanchor();
        else restrictTo(homeAnchor.pos(), homeRadius());
    }

    @Override public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!level().isClientSide) ensureAnchor();
    }

    @Override public void tick() {
        if (!level().isClientSide && isAddedToLevel()) {
            ensureAnchor();
            refreshGreeting();
            if (conversationPlayer() == null) {
                conversationTarget = null;
                conversationUntil = 0L;
            }
            if (lessonPlayer() == null) {
                lessonTarget = null;
                lessonUntil = 0L;
            }
            // A chunk can unload after a path was accepted. Do not walk into missing terrain.
            Path path = getNavigation().getPath();
            if (path != null && !pathLoaded(path)) stopWalking();
        }
        super.tick();
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (homeAnchor != null) {
            GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, homeAnchor).result().ifPresent(value -> tag.put(ANCHOR_TAG, value));
        }
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        homeAnchor = tag.contains(ANCHOR_TAG, Tag.TAG_COMPOUND)
            ? GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.getCompound(ANCHOR_TAG)).result().orElse(null) : null;
        if (homeAnchor != null && (!homeAnchor.dimension().equals(level().dimension())
            || !validAnchorPosition(homeAnchor.pos()))) homeAnchor = null;
        conversationTarget = null;
        conversationUntil = 0L;
        lessonTarget = null;
        lessonUntil = 0L;
        if (isAddedToLevel()) cancelGreetingForTeaching();
        greeting.reset();
        clearRestriction();
        setPersistenceRequired();
        setCanPickUpLoot(false);
        setInvulnerable(true);
    }

    /** Called only after the interaction handler accepts a private reply. */
    public boolean beginConversation(ServerPlayer player) {
        if (!validConversationPlayer(player)) return false;
        boolean occupied = isGreeting() || lessonPlayer() != null || SylphietteFirstLesson.hasActiveSession(this);
        conversationTarget = player.getUUID();
        conversationUntil = level().getGameTime() + CONVERSATION_TICKS;
        stopWalking();
        if (!occupied) faceConversationPlayer(player);
        return true;
    }

    /** Only the normal conversation branch may request this; a pending lesson reserves the presentation. */
    public boolean tryBeginGreeting(ServerPlayer player) {
        if (!validConversationPlayer(player) || player.isFakePlayer() || lessonPlayer() != null
                || SylphietteFirstLesson.hasActiveSession(this)) return false;
        refreshGreeting();
        if (!greeting.start(level().getGameTime(), NpcGreetings.SYLPHIETTE_HOLD_TICKS, player.getUUID())) return false;
        stopWalking();
        setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
        NpcGreetings.play(this);
        return true;
    }

    private void refreshGreeting() {
        if (level().isClientSide) return;
        if (!isAlive() || isRemoved()) {
            boolean stop = greeting.active() && !isRemoved();
            greeting.reset();
            if (stop) NpcGreetings.stop(this);
            return;
        }
        if (greeting.advance(level().getGameTime())) NpcGreetings.stop(this);
        if (greeting.active()) setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
    }

    public boolean isGreeting() {
        if (level().isClientSide) return false;
        refreshGreeting();
        return greeting.active();
    }

    @Nullable public ServerPlayer greetingPlayer() {
        if (!isGreeting() || !(level() instanceof ServerLevel server) || greeting.target() == null) return null;
        if (server.getEntity(greeting.target()) instanceof ServerPlayer player && validConversationPlayer(player)) return player;
        greeting.forgetTarget();
        return null;
    }

    public long greetingUntil() { return isGreeting() ? greeting.until() : 0L; }

    public void cancelGreetingForTeaching() {
        if (level().isClientSide || !greeting.active()) return;
        greeting.cancel();
        NpcGreetings.stop(this);
    }

    /** A newly accepted lesson may replace a greeting, without adding a ten-second movement lock. */
    public void beginLessonConversation(ServerPlayer player) {
        if (!validConversationPlayer(player)) return;
        cancelGreetingForTeaching();
        conversationTarget = player.getUUID();
        conversationUntil = level().getGameTime() + CONVERSATION_TICKS;
        stopWalking();
        faceConversationPlayer(player);
    }

    public boolean isMovementPaused() { return isConversing() || lessonPlayer() != null || isGreeting(); }

    @Override public void remove(RemovalReason reason) {
        greeting.reset();
        super.remove(reason);
    }

    @Nullable public ServerPlayer conversationPlayer() {
        if (!(level() instanceof ServerLevel server) || conversationTarget == null
            || level().getGameTime() >= conversationUntil) return null;
        return server.getEntity(conversationTarget) instanceof ServerPlayer player && validConversationPlayer(player) ? player : null;
    }

    public boolean isConversing() { return conversationPlayer() != null; }

    /**
     * Server side, from SylphietteFirstLesson: while the player who showed her watches, she stands still and faces
     * them until the given game time, from wherever within the lesson's range they stand, so her attempt plays toward
     * them and not mid-stroll. Presentation only: the lesson's own session decides everything else.
     */
    public void holdForLesson(ServerPlayer player, long untilGameTime) {
        if (!(level() instanceof ServerLevel server) || player.level() != server) return;
        cancelGreetingForTeaching();
        lessonTarget = player.getUUID();
        lessonUntil = untilGameTime;
        stopWalking();
    }

    /** Lets go early, when the lesson ends before the hold would have run out. */
    public void releaseLesson(ServerPlayer player) {
        if (player.getUUID().equals(lessonTarget)) {
            lessonTarget = null;
            lessonUntil = 0L;
        }
    }

    @Nullable private ServerPlayer lessonPlayer() {
        if (!(level() instanceof ServerLevel server) || lessonTarget == null || level().getGameTime() >= lessonUntil) return null;
        return server.getEntity(lessonTarget) instanceof ServerPlayer player && player.isAlive() && !player.isRemoved()
            && !player.isSpectator() && player.level() == server ? player : null;
    }

    private boolean validConversationPlayer(ServerPlayer player) {
        return level() instanceof ServerLevel server && server.getServer().isSameThread()
            && isAlive() && !isRemoved() && server.getEntity(getUUID()) == this
            && player.level() == server && player.isAlive() && !player.isRemoved() && !player.isSpectator()
            && server.getEntity(player.getUUID()) == player && player.canInteractWithEntity(this, 0.0)
            && player.hasLineOfSight(this);
    }

    private void faceConversationPlayer(ServerPlayer player) {
        getLookControl().setLookAt(player, 30.0F, 30.0F);
        double dx = player.getX() - getX(), dz = player.getZ() - getZ();
        if (dx * dx + dz * dz > 1.0E-5) {
            float targetYaw = (float)(Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            float yaw = Mth.rotLerp(0.35F, getYRot(), targetYaw);
            setYRot(yaw);
            setYBodyRot(yaw);
        }
    }

    private void stopWalking() {
        getNavigation().stop();
        getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0);
        setZza(0.0F);
        setXxa(0.0F);
    }

    private boolean insideHome(Vec3 point) {
        int radius = homeRadius();
        return homeAnchor != null && homeAnchor.dimension().equals(level().dimension())
            && Vec3.atBottomCenterOf(homeAnchor.pos()).distanceToSqr(point) <= radius * radius;
    }

    private boolean pathLoaded(@Nullable Path path) {
        if (path == null) return false;
        for (int index = path.getNextNodeIndex(); index < path.getNodeCount(); index++) {
            Vec3 pos = path.getEntityPosAtNode(this, index);
            if (!loadedBox(getBoundingBox().move(pos.subtract(position())).inflate(0.1))) return false;
        }
        return true;
    }

    private boolean boundedPath(@Nullable Path path) {
        if (path == null || !path.canReach() || !pathLoaded(path)) return false;
        for (int index = 0; index < path.getNodeCount(); index++) {
            if (!insideHome(path.getEntityPosAtNode(this, index))) return false;
        }
        return true;
    }

    /**
     * A stroll stays on the flat ground, so it only ever steps onto a carpet. In water, after the stroll and when
     * walking home the normal step applies, including any step height a modpack gives this entity.
     */
    @Override public float maxUpStep() {
        float step = super.maxUpStep();
        return strolling && !isInWater() ? Math.min(step, STROLL_STEP) : step;
    }

    @Override public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }
    // Keep Entity.shouldBeSaved: normal chunk saves and dimension transfer semantics remain intact.
    @Override public boolean canBeLeashed() { return false; }
    @Override public boolean canAttack(LivingEntity target) { return false; }
    @Override public boolean canAttackType(EntityType<?> type) { return false; }
    @Override public boolean canBeSeenAsEnemy() { return false; }
    @Override public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypes.GENERIC_KILL) || super.isInvulnerableTo(source);
    }
    @Override protected void dropAllDeathLoot(ServerLevel level, DamageSource source) { }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        NpcAnimations.registerSylphiette(controllers, this);
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    private final class ConversationGoal extends Goal {
        ConversationGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return isMovementPaused(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { stopWalking(); }
        @Override public void tick() {
            stopWalking();
            // A lesson owns the NPC's gaze for its full attempt, even if another player talks to her meanwhile.
            ServerPlayer player = lessonPlayer();
            if (player == null) player = SylphietteFirstLesson.observingPlayer(SylphietteEntity.this);
            if (player == null) player = isGreeting() ? greetingPlayer() : conversationPlayer();
            if (player != null) faceConversationPlayer(player);
        }
    }

    private final class ReturnHomeGoal extends Goal {
        @Nullable private GlobalPos home;
        @Nullable private Path returnPath;
        private long nextAttempt;
        ReturnHomeGoal() { setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() {
            if (homeAnchor == null || isMovementPaused() || insideHome(position()) || isPassenger()) return false;
            long now = level().getGameTime();
            if (now < nextAttempt) return false;
            nextAttempt = now + RETURN_RETRY_TICKS;
            stopWalking();
            home = homeAnchor;
            if (!level().hasChunkAt(home.pos())) return false;
            returnPath = getNavigation().createPath(home.pos(), 0);
            return returnPath != null && returnPath.canReach() && pathLoaded(returnPath);
        }
        @Override public void start() { getNavigation().moveTo(returnPath, 1.0); }
        @Override public boolean canContinueToUse() {
            return home != null && home.equals(homeAnchor) && !isMovementPaused() && !isPassenger()
                && !getNavigation().isDone() && pathLoaded(getNavigation().getPath());
        }
        @Override public void stop() { stopWalking(); nextAttempt = level().getGameTime() + RETURN_RETRY_TICKS; }
    }

    /** Open ground: nothing taller than a carpet where the feet go. */
    private boolean floorLevel(BlockPos pos) {
        VoxelShape shape = level().getBlockState(pos).getCollisionShape(level(), pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= FLOOR_COVER;
    }

    /** A spot to stroll to: open ground on a firm block with head room, at the anchor's own height. */
    private boolean strollSpot(BlockPos pos) {
        BlockPos below = pos.below(), above = pos.above();
        return floorLevel(pos) && level().getBlockState(below).isFaceSturdy(level(), below, Direction.UP)
            && level().getBlockState(above).getCollisionShape(level(), above).isEmpty();
    }

    /** Every step after the start stays on open ground at the anchor's height. The start is left out. */
    private boolean floorPath(@Nullable Path path) {
        if (path == null || homeAnchor == null) return false;
        int height = homeAnchor.pos().getY();
        for (int index = 1; index < path.getNodeCount(); index++) {
            BlockPos node = path.getNode(index).asBlockPos();
            if (node.getY() != height || !floorLevel(node)) return false;
        }
        return true;
    }

    /**
     * Short walks inside the home circle, on the flat ground at the anchor's height, with the step lowered for the
     * walk, the same rules that keep the Greyrat family off their furniture. On the hill this keeps her on the level
     * ground under the tree. Walking home is not limited this way.
     */
    private final class HomeStrollGoal extends Goal {
        @Nullable private GlobalPos home;
        @Nullable private Path strollPath;
        private long nextAttempt;
        HomeStrollGoal() { setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() {
            if (homeAnchor == null || isMovementPaused() || !insideHome(position()) || isPassenger()) return false;
            long now = level().getGameTime();
            if (now < nextAttempt) return false;
            nextAttempt = now + 120 + getRandom().nextInt(100);
            home = homeAnchor;
            int radius = homeRadius();
            for (int attempt = 0; attempt < 5; attempt++) {
                BlockPos candidate = home.pos().offset(getRandom().nextInt(2 * radius + 1) - radius, 0,
                    getRandom().nextInt(2 * radius + 1) - radius);
                if (!validAnchorPosition(candidate) || !level().hasChunkAt(candidate)
                    || !insideHome(Vec3.atBottomCenterOf(candidate)) || !strollSpot(candidate)) continue;
                Path path = getNavigation().createPath(candidate, 0);
                if (path != null && boundedPath(path) && floorPath(path)) {
                    strollPath = path;
                    return true;
                }
            }
            return false;
        }
        @Override public void start() {
            strolling = true;
            getNavigation().moveTo(strollPath, 0.6);
        }
        @Override public boolean canContinueToUse() {
            return home != null && home.equals(homeAnchor) && !isMovementPaused() && !isPassenger() && insideHome(position())
                && !getNavigation().isDone() && boundedPath(getNavigation().getPath()) && floorPath(getNavigation().getPath());
        }
        @Override public void stop() {
            strolling = false;
            stopWalking();
        }
    }
}
