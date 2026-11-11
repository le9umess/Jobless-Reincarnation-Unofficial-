package dev.joblessreincarnation.npc;

import java.util.EnumSet;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/** A persistent, non-combat tutor. Its small home area and protection are game adaptations. */
public final class RoxyTutorEntity extends PathfinderMob implements GeoEntity {
    public static final int HOME_RADIUS = 8;
    public static final int CONVERSATION_TICKS = 40;
    public static final int RETURN_RETRY_TICKS = 40;
    private static final String ANCHOR_TAG = "JR_TutorAnchor";
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    @Nullable private GlobalPos tutorAnchor;
    @Nullable private UUID conversationTarget;
    private long conversationUntil;
    private final NpcGreetingState greeting = new NpcGreetingState();
    /** How often she looks round for someone arriving, and how long someone must have been away to be greeted again. */
    public static final int GREETING_SCAN_TICKS = 10, GREETING_AWAY_TICKS = 600;
    /** When each player was last within reach of her. Server memory only, never saved; forgotten once they have been away a while. */
    private final java.util.Map<UUID, Long> lastNear = new java.util.HashMap<>();

    public RoxyTutorEntity(EntityType<? extends RoxyTutorEntity> type, Level level) {
        super(type, level);
        setCustomName(Component.translatable("entity.jobless_reincarnation.roxy_tutor"));
        setCustomNameVisible(true);
        setPersistenceRequired();
        setCanPickUpLoot(false);
        setInvulnerable(true);
        xpReward = 0;
        if (getNavigation() instanceof GroundPathNavigation ground) {
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

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new ConversationGoal());
        goalSelector.addGoal(2, new ReturnHomeGoal());
        goalSelector.addGoal(3, new HomeStrollGoal());
        goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    /** Server state only; the client never needs another player's teaching or home data. */
    @Nullable public GlobalPos getTutorAnchor() { return tutorAnchor; }

    /** Used by the administrator's relocation command; never anchors the constructor's origin. */
    public boolean reanchor() {
        if (!(level() instanceof ServerLevel server) || !server.getServer().isSameThread() || !isAddedToLevel() || !isAlive() || !legalCurrentPosition()) return false;
        tutorAnchor = GlobalPos.of(level().dimension(), blockPosition().immutable());
        restrictTo(tutorAnchor.pos(), HOME_RADIUS);
        stopWalking();
        return true;
    }

    private boolean legalCurrentPosition() {
        Vec3 pos = position();
        if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) return false;
        AABB box = getBoundingBox();
        if (box.minY < level().getMinBuildHeight() || box.maxY > level().getMaxBuildHeight() || !validAnchorPosition(blockPosition()) || !level().getWorldBorder().isWithinBounds(box)
            || !loadedBox(box.inflate(0.1)) || !level().noCollision(this, box)) return false;
        // Require actual support, including slabs, rather than waiting for a serialized onGround bit.
        return level().getBlockCollisions(this, box.move(0.0, -0.05, 0.0)).iterator().hasNext();
    }

    private boolean validAnchorPosition(BlockPos pos) {
        return !level().isOutsideBuildHeight(pos) && level().getWorldBorder().isWithinBounds(pos);
    }

    /** Never waits for a chunk, so it is also safe while a natural home is still loading; see {@link NpcLoadedChunks}. */
    private boolean loadedBox(AABB box) { return NpcLoadedChunks.ready(level(), box); }

    private void ensureAnchor() {
        if (tutorAnchor != null && (!tutorAnchor.dimension().equals(level().dimension())
            || !validAnchorPosition(tutorAnchor.pos()))) {
            tutorAnchor = null;
            clearRestriction();
            stopWalking();
        }
        if (tutorAnchor == null) reanchor();
        else restrictTo(tutorAnchor.pos(), HOME_RADIUS);
    }

    @Override public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!level().isClientSide) ensureAnchor();
    }

    @Override public void tick() {
        if (!level().isClientSide && isAddedToLevel()) {
            ensureAnchor();
            if (conversationPlayer() == null) {
                conversationTarget = null;
                conversationUntil = 0L;
            }
            refreshGreeting();
            if (tickCount % GREETING_SCAN_TICKS == 0) greetArrivals();
            // A chunk can unload after a path was accepted. Do not walk into missing terrain.
            Path path = getNavigation().getPath();
            if (path != null && !pathLoaded(path)) stopWalking();
        }
        super.tick();
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (tutorAnchor != null) {
            GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, tutorAnchor).result().ifPresent(value -> tag.put(ANCHOR_TAG, value));
        }
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        tutorAnchor = tag.contains(ANCHOR_TAG, Tag.TAG_COMPOUND)
            ? GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.getCompound(ANCHOR_TAG)).result().orElse(null) : null;
        if (tutorAnchor != null && (!tutorAnchor.dimension().equals(level().dimension())
            || !validAnchorPosition(tutorAnchor.pos()))) tutorAnchor = null;
        conversationTarget = null;
        conversationUntil = 0L;
        if (isAddedToLevel()) stopGreeting();
        greeting.reset();
        clearRestriction();
        setPersistenceRequired();
        setCanPickUpLoot(false);
        setInvulnerable(true);
    }

    /** Called only after the interaction handler accepts a private teaching reply. */
    public boolean beginConversation(ServerPlayer player) {
        if (!validConversationPlayer(player)) return false;
        conversationTarget = player.getUUID();
        conversationUntil = level().getGameTime() + CONVERSATION_TICKS;
        stopWalking();
        // While she is greeting someone she keeps facing them; she turns to this listener once a lecture has taken over.
        if (!isGreeting()) faceConversationPlayer(player);
        return true;
    }

    @Nullable public ServerPlayer conversationPlayer() {
        if (!(level() instanceof ServerLevel server) || conversationTarget == null
            || level().getGameTime() >= conversationUntil) return null;
        return server.getEntity(conversationTarget) instanceof ServerPlayer player && validConversationPlayer(player) ? player : null;
    }

    public boolean isConversing() { return conversationPlayer() != null; }

    /**
     * A first hello: a nod and a small wave of the hand that is not holding the staff, shown to everyone nearby through the
     * public gesture. Server-only. Never over a lecture that is still playing, and once per shared greeting cooldown.
     */
    public boolean tryBeginGreeting(ServerPlayer player) {
        if (!validConversationPlayer(player) || player.isFakePlayer()) return false;
        var gesture = getData(dev.joblessreincarnation.registry.ModAttachments.NPC_GESTURE_VISUAL);
        if (gesture.live() && gesture.kind() != dev.joblessreincarnation.compat.epic.NpcGestureVisual.Kind.GREETING) return false;
        refreshGreeting();
        if (!greeting.start(level().getGameTime(), NpcGreetings.ROXY_HOLD_TICKS, player.getUUID())) return false;
        stopWalking();
        setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
        dev.joblessreincarnation.compat.epic.NpcEpicGestures.greeting(this);
        return true;
    }

    private void refreshGreeting() {
        if (level().isClientSide) return;
        if (!isAlive() || isRemoved()) {
            boolean stop = greeting.active() && !isRemoved();
            greeting.reset();
            if (stop) stopGreeting();
            return;
        }
        if (greeting.advance(level().getGameTime())) stopGreeting();
        if (greeting.active()) setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
    }

    /**
     * Someone who comes within reach and in sight after being away is greeted once: a real player, not a spectator or a
     * fake player. Whoever is still nearby just stays noted; leaving for half a minute makes the next arrival a new hello.
     * Nothing here happens while she is talking with someone or a lecture is playing; the shared greeting cooldown also holds.
     */
    private void greetArrivals() {
        if (!(level() instanceof ServerLevel server) || !isAlive() || isRemoved()) return;
        long now = level().getGameTime();
        lastNear.values().removeIf(seen -> now < seen || now - seen > GREETING_AWAY_TICKS);
        ServerPlayer arrival = null;
        for (ServerPlayer player : server.players()) {
            if (player.isFakePlayer() || player.distanceToSqr(this) > 36.0 || !validConversationPlayer(player)) continue;
            Long seen = lastNear.put(player.getUUID(), now);
            if (seen == null && arrival == null) arrival = player;
        }
        if (arrival == null || isConversing() || isGreeting()) return;
        var gesture = getData(dev.joblessreincarnation.registry.ModAttachments.NPC_GESTURE_VISUAL);
        if (gesture.live()) return;
        tryBeginGreeting(arrival);
    }

    /** A lecture that really started replaces the greeting; the shared cooldown is not refunded. */
    public void cancelGreetingForLecture() {
        if (level().isClientSide || !greeting.active()) return;
        greeting.cancel();
        stopGreeting();
    }

    private void stopGreeting() {
        dev.joblessreincarnation.compat.epic.NpcEpicGestures.stop(this, dev.joblessreincarnation.compat.epic.NpcGestureVisual.Kind.GREETING);
    }

    public boolean isGreeting() {
        if (level().isClientSide) return false;
        refreshGreeting();
        return greeting.active();
    }

    /** The person being greeted, while the greeting lasts and they are still a valid listener. */
    @Nullable public ServerPlayer greetingPlayer() {
        if (!isGreeting() || !(level() instanceof ServerLevel server) || greeting.target() == null) return null;
        if (server.getEntity(greeting.target()) instanceof ServerPlayer player && validConversationPlayer(player)) return player;
        greeting.forgetTarget();
        return null;
    }

    @Override public void remove(RemovalReason reason) {
        greeting.reset();
        super.remove(reason);
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
        return tutorAnchor != null && tutorAnchor.dimension().equals(level().dimension())
            && Vec3.atBottomCenterOf(tutorAnchor.pos()).distanceToSqr(point) <= HOME_RADIUS * HOME_RADIUS;
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
        NpcAnimations.registerTutor(controllers, this, "roxy_tutor");
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    private final class ConversationGoal extends Goal {
        ConversationGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() { return isConversing() || isGreeting(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { stopWalking(); }
        @Override public void tick() {
            stopWalking();
            // The person being greeted keeps her gaze until the greeting ends or a lecture replaces it.
            ServerPlayer player = isGreeting() ? greetingPlayer() : conversationPlayer();
            if (player != null) faceConversationPlayer(player);
        }
    }

    private final class ReturnHomeGoal extends Goal {
        @Nullable private GlobalPos home;
        @Nullable private Path returnPath;
        private long nextAttempt;
        ReturnHomeGoal() { setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() {
            if (tutorAnchor == null || isConversing() || insideHome(position()) || isPassenger()) return false;
            long now = level().getGameTime();
            if (now < nextAttempt) return false;
            nextAttempt = now + RETURN_RETRY_TICKS;
            stopWalking();
            home = tutorAnchor;
            if (!level().hasChunkAt(home.pos())) return false;
            returnPath = getNavigation().createPath(home.pos(), 0);
            return returnPath != null && returnPath.canReach() && pathLoaded(returnPath);
        }
        @Override public void start() { getNavigation().moveTo(returnPath, 1.0); }
        @Override public boolean canContinueToUse() {
            return home != null && home.equals(tutorAnchor) && !isConversing() && !isPassenger()
                && !getNavigation().isDone() && pathLoaded(getNavigation().getPath());
        }
        @Override public void stop() { stopWalking(); nextAttempt = level().getGameTime() + RETURN_RETRY_TICKS; }
    }

    private final class HomeStrollGoal extends Goal {
        @Nullable private GlobalPos home;
        @Nullable private Path strollPath;
        private long nextAttempt;
        HomeStrollGoal() { setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() {
            if (tutorAnchor == null || isConversing() || !insideHome(position()) || isPassenger()) return false;
            long now = level().getGameTime();
            if (now < nextAttempt) return false;
            nextAttempt = now + 100 + getRandom().nextInt(80);
            home = tutorAnchor;
            for (int attempt = 0; attempt < 5; attempt++) {
                BlockPos candidate = home.pos().offset(getRandom().nextInt(17) - 8, getRandom().nextInt(5) - 2, getRandom().nextInt(17) - 8);
                if (!validAnchorPosition(candidate) || !level().hasChunkAt(candidate)
                    || !insideHome(Vec3.atBottomCenterOf(candidate))) continue;
                Path path = getNavigation().createPath(candidate, 0);
                if (path != null && boundedPath(path)) {
                    strollPath = path;
                    return true;
                }
            }
            return false;
        }
        @Override public void start() { getNavigation().moveTo(strollPath, 0.75); }
        @Override public boolean canContinueToUse() {
            return home != null && home.equals(tutorAnchor) && !isConversing() && !isPassenger()
                && insideHome(position()) && !getNavigation().isDone() && boundedPath(getNavigation().getPath());
        }
        @Override public void stop() { stopWalking(); }
    }
}