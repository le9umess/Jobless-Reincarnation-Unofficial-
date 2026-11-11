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
 * A member of the Greyrat household who lives in the family home: persistent, non-combat, and staying near the spot
 * where it first landed. The home, protection and conversation pause follow {@link RoxyTutorEntity} with the same
 * safety checks and its own save key. Paul also demonstrates the introductory sword lesson; the other residents
 * retain idle and walk. Home radii, conversation pauses and the independent demonstration pause are game adaptations.
 */
public final class FamilyResidentEntity extends PathfinderMob implements GeoEntity {
    /** The three residents. Each has its own entity type; the id is also the model and language key. */
    public enum Member {
        PAUL("paul_greyrat", 5),
        ZENITH("zenith_greyrat", 4),
        LILIA("lilia_greyrat", 4);

        private final String id;
        private final int homeRadius;

        Member(String id, int homeRadius) {
            this.id = id;
            this.homeRadius = homeRadius;
        }

        public String id() { return id; }
        public int homeRadius() { return homeRadius; }
    }

    public static final int CONVERSATION_TICKS = 40;
    /** Covers Paul's one-shot sword clip and its transition back to idle. Runtime only. */
    public static final int SWORD_DEMO_PAUSE_TICKS = 40;
    public static final int RETURN_RETRY_TICKS = 40;
    private static final String ANCHOR_TAG = "JR_HomeAnchor";
    /** Tallest thing that still counts as floor: a carpet. */
    private static final double FLOOR_COVER = 1.0 / 16.0;
    /** Step while strolling: enough for a carpet, too low for a chair seat or a bench. */
    private static final float STROLL_STEP = 2.0F / 16.0F;
    private final Member member;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    @Nullable private GlobalPos homeAnchor;
    @Nullable private UUID conversationTarget;
    private long conversationUntil;
    private long swordDemoStartedAt;
    private long swordDemoLastObservedAt;
    private long swordDemoUntil;
    @Nullable private UUID swordDemoTarget;
    private final NpcGreetingState greeting = new NpcGreetingState();
    private boolean strolling;
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DUEL_STANCE = net.minecraft.network.syncher.SynchedEntityData.defineId(FamilyResidentEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<java.util.Optional<UUID>> DUEL_USER = net.minecraft.network.syncher.SynchedEntityData.defineId(FamilyResidentEntity.class, net.minecraft.network.syncher.EntityDataSerializers.OPTIONAL_UUID);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DUEL_MAGIC = net.minecraft.network.syncher.SynchedEntityData.defineId(FamilyResidentEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    @Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder); builder.define(DUEL_STANCE, 0); builder.define(DUEL_USER, java.util.Optional.empty()); builder.define(DUEL_MAGIC, false);
    }
    public void setDuelUser(@Nullable UUID player, boolean magic) {
        if (!level().isClientSide) { entityData.set(DUEL_USER, java.util.Optional.ofNullable(player)); entityData.set(DUEL_MAGIC, magic); }
    }
    public boolean duelOwner(Player player) { return entityData.get(DUEL_USER).filter(player.getUUID()::equals).isPresent(); }
    public boolean blocksDuelMagic(Player player) { return duelOwner(player) && !entityData.get(DUEL_MAGIC); }
    public int duelStance() { return entityData.get(DUEL_STANCE); }
    public void setDuelStance(int stance) { if (!level().isClientSide) entityData.set(DUEL_STANCE, stance); }
    public void prepareForDuel() {
        cancelGreetingForTeaching(); clearSwordDemonstration(); conversationTarget=null; conversationUntil=0;
        dev.joblessreincarnation.compat.epic.NpcEpicGestures.stop(this, dev.joblessreincarnation.compat.epic.NpcGestureVisual.Kind.SWORD_DEMO);
        stopWalking();
    }

    public FamilyResidentEntity(EntityType<? extends FamilyResidentEntity> type, Level level, Member member) {
        super(type, level);
        this.member = member;
        setCustomName(Component.translatable("entity.jobless_reincarnation." + member.id()));
        setCustomNameVisible(true);
        setPersistenceRequired();
        setCanPickUpLoot(false);
        setInvulnerable(true);
        xpReward = 0;
        if (getNavigation() instanceof GroundPathNavigation ground) {
            // Residents never open doors themselves; a door a player leaves open is passable. Where they actually go
            // is bounded by the home circle and the paths in it, not promised for every rebuilt house.
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

    public Member member() { return member; }
    public int homeRadius() { return member.homeRadius(); }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new ResidentPauseGoal());
        goalSelector.addGoal(2, new ReturnHomeGoal());
        goalSelector.addGoal(3, new HomeStrollGoal());
        goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(5, new RandomLookAroundGoal(this));
    }

    /** Server state only; clients never need another resident's home. */
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

    /** Never waits for a chunk, so it is also safe while a natural home is still loading; see {@link NpcLoadedChunks}. */
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
            dev.joblessreincarnation.encounter.PaulDuel.constrainMovement(this);
            ensureAnchor();
            // Expire a demonstration even when its initiating conversation has already ended.
            isDemonstratingSword();
            refreshGreeting();
            if (conversationPlayer() == null) {
                conversationTarget = null;
                conversationUntil = 0L;
            }
            // A chunk can unload after a path was accepted. Do not walk into missing terrain.
            Path path = getNavigation().getPath();
            if (path != null && !pathLoaded(path)) stopWalking();
        }
        super.tick();
        if (!level().isClientSide) dev.joblessreincarnation.encounter.PaulDuel.tick(this);
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
        clearSwordDemonstration();
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
        boolean occupied = isDemonstratingSword() || isGreeting();
        conversationTarget = player.getUUID();
        conversationUntil = level().getGameTime() + CONVERSATION_TICKS;
        stopWalking();
        // Keep the ordinary private reply/pause, without stealing a gesture's original target.
        if (!occupied) faceConversationPlayer(player);
        return true;
    }

    /** Called only for the accepted ordinary-dialogue branch. Server-only, never a client pose flag. */
    public boolean tryBeginGreeting(ServerPlayer player) {
        if (!validConversationPlayer(player) || player.isFakePlayer() || isDemonstratingSword()) return false;
        refreshGreeting();
        if (!greeting.start(level().getGameTime(), NpcGreetings.holdTicks(member), player.getUUID())) return false;
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

    /** Original actor only. Losing them ends tracking, not the bounded movement pause. */
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

    /** Server-only, independent of the initiating player's continued presence or line of sight. */
    public boolean isDemonstratingSword() {
        if (level().isClientSide || member != Member.PAUL || swordDemoUntil <= swordDemoStartedAt) return false;
        long now = level().getGameTime();
        if (now < swordDemoStartedAt || now < swordDemoLastObservedAt
            || now >= swordDemoUntil || !isAlive() || isRemoved()) {
            // A backwards clock expires this runtime pause instead of postponing its end indefinitely.
            clearSwordDemonstration();
            return false;
        }
        swordDemoLastObservedAt = now;
        return true;
    }

    /** The same movement gate is used by the pause, home-return and stroll goals. */
    public boolean isMovementPaused() { return dev.joblessreincarnation.encounter.PaulDuel.occupied(this) || isConversing() || isDemonstratingSword() || isGreeting(); }

    /** Only NpcAnimations calls this, after it has accepted and triggered a new sword demonstration. */
    void beginSwordDemonstration() {
        cancelGreetingForTeaching();
        swordDemoTarget = conversationTarget;
        swordDemoStartedAt = level().getGameTime();
        swordDemoLastObservedAt = swordDemoStartedAt;
        swordDemoUntil = swordDemoStartedAt + SWORD_DEMO_PAUSE_TICKS;
        stopWalking();
    }

    private void clearSwordDemonstration() {
        swordDemoTarget = null;
        swordDemoStartedAt = 0L;
        swordDemoLastObservedAt = 0L;
        swordDemoUntil = 0L;
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
     * A stroll stays on the floor, so it only ever steps onto a carpet. In water, after the stroll and when walking
     * home the normal step applies, including any step height a modpack gives this entity.
     */
    @Override public float maxUpStep() {
        float step = super.maxUpStep();
        return strolling && !isInWater() ? Math.min(step, STROLL_STEP) : step;
    }

    @Override public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }
    // Keep Entity.shouldBeSaved: normal chunk saves and dimension transfer semantics remain intact.
    @Override public boolean canBeLeashed() { return false; }
    @Override public boolean canAttack(LivingEntity target) {
        var bout=dev.joblessreincarnation.encounter.PaulDuel.bout(this);
        return bout!=null && bout.active() && bout.player==target;
    }
    @Override public boolean canAttackType(EntityType<?> type) { return type==EntityType.PLAYER && dev.joblessreincarnation.encounter.PaulDuel.occupied(this); }
    @Override public boolean canBeSeenAsEnemy() { return duelStance()>0; }
    @Override public boolean isInvulnerableTo(DamageSource source) {
        if (dev.joblessreincarnation.encounter.PaulDuel.acceptsDamage(this,source)) return false;
        return !source.is(DamageTypes.GENERIC_KILL) || super.isInvulnerableTo(source);
    }
    @Override public boolean hurt(DamageSource source, float amount) {
        dev.joblessreincarnation.encounter.PaulDuel.incoming(this,source);
        try { return super.hurt(source,amount); }
        finally { dev.joblessreincarnation.encounter.PaulDuel.incoming(this,null); }
    }
    @Override protected void dropAllDeathLoot(ServerLevel level, DamageSource source) { }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        NpcAnimations.registerFamily(controllers, this, member.id());
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    private final class ResidentPauseGoal extends Goal {
        ResidentPauseGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return isMovementPaused(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { if (!dev.joblessreincarnation.encounter.PaulDuel.occupied(FamilyResidentEntity.this)) stopWalking(); }
        @Override public void tick() {
            if (dev.joblessreincarnation.encounter.PaulDuel.occupied(FamilyResidentEntity.this)) return;
            stopWalking();
            ServerPlayer player;
            if (isDemonstratingSword()) {
                player = level() instanceof ServerLevel server && swordDemoTarget != null
                        && server.getEntity(swordDemoTarget) instanceof ServerPlayer target && validConversationPlayer(target) ? target : null;
            } else if (isGreeting()) player = greetingPlayer();
            else player = conversationPlayer();
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
            if (member == Member.PAUL && (returnPath == null || !returnPath.canReach()))
                returnPath = dev.joblessreincarnation.encounter.PaulDuelArena.route(FamilyResidentEntity.this, home.pos());
            return returnPath != null && returnPath.canReach() && pathLoaded(returnPath);
        }
        @Override public void start() { getNavigation().moveTo(returnPath, 1.0); }
        @Override public boolean canContinueToUse() {
            return home != null && home.equals(homeAnchor) && !isMovementPaused() && !isPassenger()
                && !getNavigation().isDone() && pathLoaded(getNavigation().getPath());
        }
        @Override public void stop() { stopWalking(); nextAttempt = level().getGameTime() + RETURN_RETRY_TICKS; }
    }

    /** Open floor: nothing taller than a carpet where the feet go. */
    private boolean floorLevel(BlockPos pos) {
        VoxelShape shape = level().getBlockState(pos).getCollisionShape(level(), pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= FLOOR_COVER;
    }

    /** A spot to stroll to: open floor on firm ground with head room, at the anchor's own height. */
    private boolean strollSpot(BlockPos pos) {
        BlockPos below = pos.below(), above = pos.above();
        return floorLevel(pos) && level().getBlockState(below).isFaceSturdy(level(), below, Direction.UP)
            && level().getBlockState(above).getCollisionShape(level(), above).isEmpty();
    }

    /**
     * Every step after the start stays on open floor at the anchor's height, so a stroll never cuts across a chair or a
     * counter. The start is left out: someone pushed onto a chair can still walk off it.
     */
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
     * Short walks inside the home circle, on open floor at the anchor's height: an earlier draft that allowed targets a
     * block higher had Lilia climb the kitchen counter and Zenith a chair. With the floor-only path Zenith still rose
     * onto a chair seat for a moment while cutting a corner past it, so the step is lowered for the walk as well.
     * Where they end up is still decided by the circle and the actual paths. Walking home is not limited this way.
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
