package dev.joblessreincarnation.gametest;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.npc.NpcAnimations;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.PracticeDummyEntity;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Opt-in visual check of B01's presentation layer (role side): Paul with the practice wooden sword in his hand, his
 * sword_demo gesture triggered through the real {@link NpcAnimations#playSwordDemo}, walking, the practice dummy with
 * placeholder art and its hit wobble, and the practice sword in the owner's hands, on the ground and in an item frame.
 * Filmed by {@code SwordDemoPreviewClient}. The default capture mode disables Paul's AI and scripts his walking.
 * The optional interaction mode instead retains real AI and starts each demonstration through native client input;
 * it checks independent movement pause, ordinary return-home recovery and course retention in two rounds.
 * Every fixture entity is removed afterwards and the owner's player data, game mode, position, sword-course presence
 * and values, and time of day are put back. It never writes terrain; neither mode claims manual or PCL testing.
 */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID)
public final class SwordDemoPreview {
    public static final String PROPERTY = "jobless_reincarnation.swordDemoPreview";
    public static final String WORLD = "JR Magic 0.2.0 Test";
    /** Open ground needed around the scene's centre: Paul, the dummy and the item shots fit in it. */
    private static final int CLEAR = 9;
    /** Paul faces south; the dummy stands to his left (east), the item shots are to his right (west). */
    private static final Vec3 DUMMY = new Vec3(5.0, 0.0, 0.0), ITEMS = new Vec3(-5.0, 0.0, 0.0), HANDS = new Vec3(-8.0, 0.0, 6.0);
    private static final double WALK_SPEED = 0.08, WALK_FROM = -2.0, WALK_TO = 2.0;
    /** The armor stand of the item shots, from the item spot; it faces south like Paul, so its right hand is to the west. */
    private static final Vec3 STAND = new Vec3(0.0, 0.0, -3.0);
    public enum Step { PAUL_FRONT, PAUL_SIDE, PAUL_THREE_QUARTER, PAUL_BACK, DEMO, HEAD_TO_SWORD_SIDE, HEAD_AHEAD, WALK, DUMMY_FRONT, DUMMY_SIDE, HIT,
        ITEMS_VIEW, STAND_SIDE, STAND_FRONT, HANDS_VIEW, MAIN_ONLY, OFF_ONLY, COMPARE, INTERACTION_APPROACH, INTERACTION_LEAVE }
    public record State(boolean ready, boolean restored, boolean passed, int steps, int paulId, int dummyId, String message) {}
    /** Immutable server observations; the client sends normal interactions and never mutates this result. */
    public record InteractionState(int round, boolean awaiting, long startedAt, long elapsed, boolean pause,
                                   boolean conversation, boolean departed, boolean recovered, int hits, Vec3 position) {}
    private record Snapshot(CompoundTag player, ServerLevel level, GameType mode, Vec3 position, float yaw, float pitch, long dayTime) {}
    private static final class Session {
        final MinecraftServer server;
        final UUID playerId;
        final List<Entity> fixtures = new ArrayList<>();
        Snapshot snapshot;
        Vec3 centre;
        FamilyResidentEntity paul;
        PracticeDummyEntity dummy;
        int ticks, steps;
        boolean walking, finished;
        int interactionRound;
        boolean awaitingInteraction, departed, recovered, saw39, saw40;
        long interactionStartedAt = -1L, interactionRequestedAt;
        Vec3 interactionStop;
        SwordTrainingProgress expectedProgress;
        boolean originallyHadSwordTraining;
        SwordTrainingProgress originalSwordTraining;
        Session(ServerPlayer player) { server = player.getServer(); playerId = player.getUUID(); }
    }
    private static Session session;
    private static boolean attempted;
    private static volatile MinecraftServer publishedServer;
    private static volatile State published;
    private static volatile InteractionState interactionPublished;

    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }
    public static boolean interactionMode() { return Boolean.getBoolean(PROPERTY + ".interaction"); }
    public static InteractionState interactionState(MinecraftServer server) { return publishedServer == server ? interactionPublished : null; }
    public static State state(MinecraftServer server) { return publishedServer == server ? published : null; }
    private static boolean eligible(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return enabled() && server != null && !server.isDedicatedServer() && !server.isPublished()
                && server.isSingleplayerOwner(player.getGameProfile()) && WORLD.equals(server.getWorldData().getLevelName())
                && player.level().dimension() == Level.OVERWORLD && player.isAlive() && !player.isSpectator();
    }

    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (attempted || !(event.getEntity() instanceof ServerPlayer player) || !eligible(player)) return;
        attempted = true; session = new Session(player); publishedServer = session.server;
        published = new State(false, false, false, 0, -1, -1, "Awaiting private-world initialization");
        LogUtils.getLogger().info("[JR_SWORD_DEMO_PREVIEW] SCHEDULED: private owner only, no terrain mutation");
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        Session current = session;
        if (current == null || current.finished || current.server != event.getServer()) return;
        try {
            ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
            if (player == null || !eligible(player)) { restore(current, false, "Player no longer eligible"); return; }
            if (++current.ticks > 6000) { restore(current, false, "Visual preview timed out"); return; }
            if (current.ticks == 80) prepare(current, player);
            if (current.walking) walk(current);
            if (interactionMode() && current.snapshot != null && current.paul != null) observeInteraction(current, player);
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_SWORD_DEMO_PREVIEW] FAILED", failure);
            restore(current, false, failure.toString());
        }
    }

    private static void prepare(Session current, ServerPlayer player) throws Exception {
        if (interactionMode()) {
            current.originallyHadSwordTraining = player.hasData(ModAttachments.SWORD_TRAINING);
            if (current.originallyHadSwordTraining) {
                SwordTrainingProgress original = player.getData(ModAttachments.SWORD_TRAINING);
                current.originalSwordTraining = new SwordTrainingProgress(original.started(), original.hits(), original.recognized());
            }
        }
        current.snapshot = new Snapshot(player.saveWithoutId(new CompoundTag()), player.serverLevel(), player.gameMode.getGameModeForPlayer(),
                player.position(), player.getYRot(), player.getXRot(), current.server.overworld().getDayTime());
        journal(current, false);
        ServerLevel level = current.snapshot.level;
        current.centre = centre(level, current.snapshot.position);
        player.closeContainer();
        player.setGameMode(GameType.SURVIVAL);
        player.getAbilities().invulnerable = true; player.onUpdateAbilities();
        // A clean hotbar: the practice sword selected and the dummy item beside it. The saved inventory comes back on restore.
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(ModContent.PRACTICE_WOODEN_SWORD.get()));
        player.getInventory().setItem(1, new ItemStack(ModContent.PRACTICE_DUMMY_ITEM.get()));
        player.getInventory().selected = 0;
        if (interactionMode()) player.setData(ModAttachments.SWORD_TRAINING, new SwordTrainingProgress());
        player.setShiftKeyDown(false); player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
        current.server.overworld().setDayTime(6000L);

        FamilyResidentEntity paul = ModContent.PAUL_GREYRAT.get().create(level);
        if (paul == null) throw new IllegalStateException("Paul could not be created");
        paul.moveTo(current.centre.x, current.centre.y, current.centre.z, 0.0F, 0.0F);
        if (!interactionMode()) { paul.setYHeadRot(0.0F); paul.setYBodyRot(0.0F); }
        paul.setNoAi(!interactionMode());
        if (!level.addFreshEntity(paul)) throw new IllegalStateException("Paul not admitted");
        current.paul = paul; current.fixtures.add(paul);

        PracticeDummyEntity dummy = ModContent.PRACTICE_DUMMY.get().create(level);
        if (dummy == null) throw new IllegalStateException("Dummy could not be created");
        Vec3 at = current.centre.add(DUMMY);
        dummy.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        dummy.setOwnerId(player.getUUID());
        if (!level.addFreshEntity(dummy)) throw new IllegalStateException("Dummy not admitted");
        current.dummy = dummy; current.fixtures.add(dummy);

        look(current, player, new Vec3(0.0, 0.0, 3.2), Vec3.ZERO, 1.15);
        sync(player);
        published = new State(true, false, false, 0, paul.getId(), dummy.getId(), "Ready");
        LogUtils.getLogger().info("[JR_SWORD_DEMO_PREVIEW] READY at {}; fixture Paul {} and dummy {}; no terrain writes", current.centre, paul.getId(), dummy.getId());
    }

    /** Runs one step of the scene for the client half of the fixture; invoked through the integrated-server executor. */
    public static void perform(MinecraftServer server, Step step) {
        if (!server.isSameThread()) throw new IllegalStateException("Steps must run on the server thread");
        Session current = session;
        if (current == null || current.finished || current.server != server || current.snapshot == null) return;
        try {
            ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
            if (player == null || !eligible(player)) { restore(current, false, "Player no longer eligible"); return; }
            switch (step) {
                case INTERACTION_APPROACH -> approachInteraction(current, player);
                case INTERACTION_LEAVE -> leaveInteraction(current, player);
                case PAUL_FRONT -> look(current, player, new Vec3(0.0, 0.0, 3.2), Vec3.ZERO, 1.15);
                case PAUL_SIDE -> look(current, player, new Vec3(-3.2, 0.0, 0.0), Vec3.ZERO, 1.15);
                case PAUL_THREE_QUARTER -> look(current, player, new Vec3(-2.3, 0.0, 2.3), Vec3.ZERO, 1.15);
                case PAUL_BACK -> look(current, player, new Vec3(0.0, 0.0, -3.2), Vec3.ZERO, 1.15);
                case DEMO -> {
                    if (!NpcAnimations.playSwordDemo(current.paul)) throw new IllegalStateException("sword_demo refused (interval)");
                }
                // Turns Paul's head 30 degrees toward his sword arm, as when the player he answers stands to his front-right.
                case HEAD_TO_SWORD_SIDE -> current.paul.setYHeadRot(30.0F);
                case HEAD_AHEAD -> current.paul.setYHeadRot(0.0F);
                case WALK -> {
                    current.paul.moveTo(current.centre.x, current.centre.y, current.centre.z + WALK_FROM, 0.0F, 0.0F);
                    current.paul.setYHeadRot(0.0F); current.paul.setYBodyRot(0.0F);
                    current.walking = true;
                }
                case DUMMY_FRONT -> look(current, player, DUMMY.add(0.0, 0.0, 2.6), DUMMY, 0.9);
                case DUMMY_SIDE -> look(current, player, DUMMY.add(2.6, 0.0, 0.0), DUMMY, 0.9);
                case HIT -> current.dummy.triggerAnim(PracticeDummyEntity.CONTROLLER, PracticeDummyEntity.HIT_TRIGGER);
                case ITEMS_VIEW -> { items(current, player); look(current, player, ITEMS.add(0.0, 0.0, 2.2), ITEMS.add(0.0, 0.0, 0.7), 0.15); }
                // An armor stand renders held items with the same third-person transforms as a player, and can be filmed from the side.
                case STAND_SIDE -> look(current, player, ITEMS.add(STAND).add(-2.2, 0.0, 0.0), ITEMS.add(STAND), 1.0);
                case STAND_FRONT -> look(current, player, ITEMS.add(STAND).add(0.0, 0.0, 2.2), ITEMS.add(STAND), 1.0);
                case HANDS_VIEW -> {
                    Vec3 feet = current.centre.add(HANDS);
                    player.teleportTo(current.snapshot.level, feet.x, feet.y, feet.z, 90.0F, 0.0F);
                    player.setYHeadRot(90.0F);
                }
                case MAIN_ONLY -> hands(player, new ItemStack(ModContent.PRACTICE_WOODEN_SWORD.get()), ItemStack.EMPTY);
                case OFF_ONLY -> hands(player, ItemStack.EMPTY, new ItemStack(ModContent.PRACTICE_WOODEN_SWORD.get()));
                // Side by side with vanilla's wooden sword, for size and grip.
                case COMPARE -> hands(player, new ItemStack(ModContent.PRACTICE_WOODEN_SWORD.get()), new ItemStack(Items.WOODEN_SWORD));
            }
            published = new State(true, false, false, ++current.steps, current.paul.getId(), current.dummy.getId(), step + " done");
            LogUtils.getLogger().info("[JR_SWORD_DEMO_PREVIEW] STEP {} {}", current.steps, step);
        } catch (Exception failure) {
            LogUtils.getLogger().error("[JR_SWORD_DEMO_PREVIEW] FAILED", failure);
            restore(current, false, failure.toString());
        }
    }

    /** Prepare two native-input scenarios without invoking the gesture or changing any AI goal. */
    private static void approachInteraction(Session current, ServerPlayer player) {
        require(interactionMode(), "Interaction steps require the opt-in mode");
        require(current.interactionRound == 0 || current.recovered, "Previous round must recover before another query");
        require(current.interactionRound < 2, "Exactly two interaction scenarios are expected");
        current.interactionRound++;
        current.awaitingInteraction = true;
        current.departed = false;
        current.recovered = false;
        current.saw39 = false;
        current.saw40 = false;
        current.interactionStartedAt = -1L;
        current.interactionStop = null;
        current.interactionRequestedAt = current.snapshot.level.getGameTime();
        if (current.interactionRound == 2) {
            // Explicit fixture state for a strong no-reset check, not a claim that seven native attacks occurred.
            player.setData(ModAttachments.SWORD_TRAINING, new SwordTrainingProgress(true, 7, false));
            LogUtils.getLogger().info("[JR_SWORD_INTERACTION] FIXTURE_SEED round=2 started=true hits=7 recognized=false; no attack-count claim");
        }
        current.expectedProgress = player.getData(ModAttachments.SWORD_TRAINING);
        FamilyResidentEntity paul = current.paul;
        require(!paul.isNoAi() && paul.getHomeAnchor() != null, "Paul must retain live AI and a real home");
        Vec3 home = Vec3.atBottomCenterOf(paul.getHomeAnchor().pos());
        paul.setPos(home.add(7.0, 0.0, 0.0));
        paul.setDeltaMovement(Vec3.ZERO);
        Vec3 relative = paul.position().subtract(current.centre);
        look(current, player, relative.add(-1.2, 0.0, 1.6), relative, 1.15);
        hands(player, new ItemStack(ModContent.PRACTICE_WOODEN_SWORD.get()), ItemStack.EMPTY);
        require(player.canInteractWithEntity(paul, 0.0) && player.hasLineOfSight(paul), "Native query must begin within reach and sight");
        publishInteraction(current, player);
        LogUtils.getLogger().info("[JR_SWORD_INTERACTION] READY round={} time={} paul={} home={} ai=true; awaiting native client interaction",
                current.interactionRound, current.interactionRequestedAt, paul.position(), home);
    }

    private static void leaveInteraction(Session current, ServerPlayer player) {
        require(interactionMode() && current.interactionStartedAt >= 0 && current.paul.isDemonstratingSword(),
                "Leave while the independently timed demonstration is still active");
        Vec3 relative = current.paul.position().subtract(current.centre);
        look(current, player, relative.add(-4.2, 0.0, 4.2), relative, 1.15);
        current.departed = true;
        require(!player.canInteractWithEntity(current.paul, 0.0) && !current.paul.isConversing(),
                "Leaving reach immediately invalidates the old conversation");
        require(current.paul.isDemonstratingSword(), "Ending conversation must not end the demonstration pause");
        publishInteraction(current, player);
        LogUtils.getLogger().info("[JR_SWORD_INTERACTION] DEPARTED round={} time={} elapsed={} conversation=false pause=true",
                current.interactionRound, current.snapshot.level.getGameTime(), current.snapshot.level.getGameTime() - current.interactionStartedAt);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void nativeSpecific(PlayerInteractEvent.EntityInteractSpecific event) { observeNativeQuery(event.getEntity(), event.getTarget(), event.getHand()); }
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void nativeGeneral(PlayerInteractEvent.EntityInteract event) { observeNativeQuery(event.getEntity(), event.getTarget(), event.getHand()); }

    /** Runs after the production interaction handler, at the actual server game time of the accepted query. */
    private static void observeNativeQuery(Entity actor, Entity target, InteractionHand hand) {
        Session current = session;
        if (!interactionMode() || current == null || current.finished || !current.awaitingInteraction
                || !(actor instanceof ServerPlayer player) || !player.getUUID().equals(current.playerId)
                || target != current.paul || hand != InteractionHand.MAIN_HAND) return;
        try {
            require(current.paul.isDemonstratingSword() && current.paul.conversationPlayer() == player,
                    "Native production handler must start both the demonstration and a valid conversation");
            SwordTrainingProgress progress = player.getData(ModAttachments.SWORD_TRAINING);
            require(progress == current.expectedProgress && progress.started() && !progress.recognized()
                    && progress.hits() == (current.interactionRound == 1 ? 0 : 7), "Native query starts or preserves the expected personal course");
            current.interactionStartedAt = current.snapshot.level.getGameTime();
            current.awaitingInteraction = false;
            publishInteraction(current, player);
            LogUtils.getLogger().info("[JR_SWORD_INTERACTION] NATIVE_STARTED round={} exactServerGameTime={} hits={} paul={} bodyYaw={} headYaw={}",
                    current.interactionRound, current.interactionStartedAt, progress.hits(), current.paul.position(), current.paul.yBodyRot, current.paul.getYHeadRot());
        } catch (Exception failure) { restore(current, false, "Native interaction failed: " + failure); }
    }

    private static void observeInteraction(Session current, ServerPlayer player) {
        if (current.interactionRound == 0) return;
        long now = current.snapshot.level.getGameTime();
        if (current.awaitingInteraction) {
            require(now - current.interactionRequestedAt < 200, "Native client query did not arrive within 200 ticks");
            publishInteraction(current, player);
            return;
        }
        long elapsed = now - current.interactionStartedAt;
        FamilyResidentEntity paul = current.paul;
        require(!paul.isNoAi(), "Interaction fixture must not disable AI");
        SwordTrainingProgress progress = player.getData(ModAttachments.SWORD_TRAINING);
        require(progress == current.expectedProgress && progress.started() && !progress.recognized()
                && progress.hits() == (current.interactionRound == 1 ? 0 : 7), "The course must remain unchanged during presentation");
        boolean pause = paul.isDemonstratingSword(), conversation = paul.isConversing();
        if (!current.recovered) {
            LogUtils.getLogger().info("[JR_SWORD_INTERACTION] TICK round={} now={} elapsed={} pause={} conversation={} position={} navigationDone={} bodyYaw={} headYaw={} hits={}",
                    current.interactionRound, now, elapsed, pause, conversation, paul.position(), paul.getNavigation().isDone(), paul.yBodyRot, paul.getYHeadRot(), progress.hits());
            if (current.departed) require(!conversation, "Departed player must not sustain an old conversation");
            if (elapsed < FamilyResidentEntity.SWORD_DEMO_PAUSE_TICKS) {
                require(pause && paul.getNavigation().isDone(), "Independent pause must block navigation until tick 40");
                if (elapsed >= 6 && current.interactionStop == null) current.interactionStop = paul.position();
                if (current.interactionStop != null) require(horizontalDistanceSqr(paul.position(), current.interactionStop) < 0.0004,
                        "After normal velocity decay, Paul must stand still through the demonstration");
            }
            if (elapsed == 39) current.saw39 = true;
            if (elapsed == 40) {
                current.saw40 = true;
                require(!pause && current.departed, "The independent pause must end exactly on tick 40 after departure");
            }
            if (elapsed > 40) {
                require(!pause, "Presentation must not restart itself after expiry");
                Vec3 home = Vec3.atBottomCenterOf(paul.getHomeAnchor().pos());
                if (current.interactionStop != null && horizontalDistanceSqr(paul.position(), home) + 1.0 < horizontalDistanceSqr(current.interactionStop, home)) {
                    require(current.saw39 && current.saw40, "Both adjacent server tick boundaries must be observed");
                    current.recovered = true;
                    LogUtils.getLogger().info("[JR_SWORD_INTERACTION] ROUND_PASS round={} time={} elapsed={} ordinaryReturnHomeResumed=true courseHits={} position={}",
                            current.interactionRound, now, elapsed, progress.hits(), paul.position());
                }
                require(current.recovered || elapsed < 180, "Autonomous return-home movement did not resume after the pause");
            }
        }
        publishInteraction(current, player);
    }

    private static void publishInteraction(Session current, ServerPlayer player) {
        long elapsed = current.interactionStartedAt < 0 ? -1L : current.snapshot.level.getGameTime() - current.interactionStartedAt;
        interactionPublished = new InteractionState(current.interactionRound, current.awaitingInteraction, current.interactionStartedAt, elapsed,
                current.paul.isDemonstratingSword(), current.paul.isConversing(), current.departed, current.recovered,
                player.getData(ModAttachments.SWORD_TRAINING).hits(), current.paul.position());
    }
    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x, dz = first.z - second.z;
        return dx * dx + dz * dz;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private static void hands(ServerPlayer player, ItemStack main, ItemStack off) {
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, main);
        player.setItemInHand(InteractionHand.OFF_HAND, off);
        sync(player);
    }

    /** Walks Paul south across the side camera's view and back to his mark; he has no AI, so this class moves him. */
    private static void walk(Session current) {
        FamilyResidentEntity paul = current.paul;
        double z = paul.getZ() + WALK_SPEED;
        if (z > current.centre.z + WALK_TO) {
            current.walking = false;
            paul.moveTo(current.centre.x, current.centre.y, current.centre.z, 0.0F, 0.0F);
        } else paul.moveTo(paul.getX(), paul.getY(), z, 0.0F, 0.0F);
        paul.setYHeadRot(0.0F); paul.setYBodyRot(0.0F);
    }

    /** A dropped practice sword that cannot be picked up, and another in an item frame lying on the ground. */
    private static void items(Session current, ServerPlayer player) {
        if (current.fixtures.stream().anyMatch(e -> e instanceof ItemFrame)) return;
        ServerLevel level = current.snapshot.level;
        Vec3 ground = current.centre.add(ITEMS);
        ItemEntity dropped = new ItemEntity(level, ground.x + 0.8, ground.y, ground.z + 0.9, new ItemStack(ModContent.PRACTICE_WOODEN_SWORD.get()), 0.0, 0.0, 0.0);
        dropped.setNeverPickUp(); dropped.setUnlimitedLifetime();
        if (!level.addFreshEntity(dropped)) throw new IllegalStateException("Dropped item not admitted");
        current.fixtures.add(dropped);
        ItemFrame frame = new ItemFrame(level, BlockPos.containing(ground.x - 0.6, ground.y, ground.z + 0.5), Direction.UP);
        frame.setItem(new ItemStack(ModContent.PRACTICE_WOODEN_SWORD.get()), false);
        if (!frame.survives() || !level.addFreshEntity(frame)) throw new IllegalStateException("Item frame not admitted");
        current.fixtures.add(frame);
        ArmorStand stand = new ArmorStand(level, ground.x + STAND.x, ground.y, ground.z + STAND.z);
        stand.setYRot(0.0F); stand.setYBodyRot(0.0F); stand.setYHeadRot(0.0F);
        stand.setShowArms(true); stand.setNoGravity(true); stand.setInvulnerable(true);
        stand.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.PRACTICE_WOODEN_SWORD.get()));
        stand.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.WOODEN_SWORD));
        if (!level.addFreshEntity(stand)) throw new IllegalStateException("Armor stand not admitted");
        current.fixtures.add(stand);
    }

    /** Puts the owner at an offset from the scene's centre, looking at a point given as an offset and a height. */
    private static void look(Session current, ServerPlayer player, Vec3 from, Vec3 at, double height) {
        Vec3 feet = current.centre.add(from);
        Vec3 aim = current.centre.add(at).add(0.0, height, 0.0).subtract(feet.add(0.0, player.getEyeHeight(), 0.0));
        float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(aim.y, aim.horizontalDistance()));
        player.teleportTo(current.snapshot.level, feet.x, feet.y, feet.z, yaw, pitch);
        player.setYHeadRot(yaw);
    }

    /** The owner's own spot if the ground around it is flat and open, else the nearest such spot along the axes. */
    private static Vec3 centre(ServerLevel level, Vec3 origin) {
        int[][] offsets = {{0, 0}, {32, 0}, {-32, 0}, {0, 32}, {0, -32}, {64, 0}, {-64, 0}, {0, 64}, {0, -64}};
        for (int[] offset : offsets) {
            BlockPos column = BlockPos.containing(origin.x + offset[0], origin.y, origin.z + offset[1]);
            BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
            if (open(level, feet)) return Vec3.atBottomCenterOf(feet);
        }
        throw new IllegalStateException("No open, flat ground near " + origin);
    }

    private static boolean open(ServerLevel level, BlockPos feet) {
        for (int dx = -CLEAR; dx <= CLEAR; dx++) {
            for (int dz = -CLEAR; dz <= CLEAR; dz++) {
                BlockPos ground = feet.offset(dx, -1, dz);
                if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP) || !level.getFluidState(ground).isEmpty()) return false;
                for (int dy = 0; dy < 4; dy++) {
                    BlockPos air = feet.offset(dx, dy, dz);
                    if (!level.getBlockState(air).getCollisionShape(level, air).isEmpty() || !level.getFluidState(air).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    /** Invoked through the integrated-server executor by the explicitly enabled local fixture. */
    public static void finish(MinecraftServer server, boolean passed, String reason) {
        if (!server.isSameThread()) throw new IllegalStateException("Restore must run on the server thread");
        if (session != null && session.server == server) {
            if (passed && interactionMode() && (session.interactionRound != 2 || !session.recovered)) {
                restore(session, false, "Both native interaction rounds must pass before successful restore");
            } else restore(session, passed, reason);
        }
    }

    private static void restore(Session current, boolean passed, String reason) {
        if (current == null || current.finished) return;
        current.finished = true;
        current.walking = false;
        boolean success = true;
        int removed = 0;
        try {
            for (Entity fixture : current.fixtures) if (!fixture.isRemoved()) { fixture.discard(); removed++; }
            Snapshot snapshot = current.snapshot;
            if (snapshot != null) {
                ServerPlayer player = current.server.getPlayerList().getPlayer(current.playerId);
                if (player == null) throw new IllegalStateException("Player missing during restore; recovery journal retained");
                // NeoForge overlays serialized attachment keys: an absent original key otherwise leaves fixture data behind.
                if (interactionMode()) player.removeData(ModAttachments.SWORD_TRAINING);
                player.load(snapshot.player.copy());
                if (interactionMode()) {
                    require(player.hasData(ModAttachments.SWORD_TRAINING) == current.originallyHadSwordTraining,
                            "Restore must preserve the original sword-course attachment presence");
                    if (current.originallyHadSwordTraining) {
                        SwordTrainingProgress original = current.originalSwordTraining;
                        SwordTrainingProgress restored = player.getData(ModAttachments.SWORD_TRAINING);
                        require(restored.started() == original.started() && restored.hits() == original.hits()
                                && restored.recognized() == original.recognized(), "Restore must retain every original sword-course fact");
                    }
                    LogUtils.getLogger().info("[JR_SWORD_INTERACTION] COURSE_RESTORED present={} valuesMatch=true",
                            current.originallyHadSwordTraining);
                }
                player.setGameMode(snapshot.mode);
                player.teleportTo(snapshot.level, snapshot.position.x, snapshot.position.y, snapshot.position.z, snapshot.yaw, snapshot.pitch);
                current.server.overworld().setDayTime(snapshot.dayTime);
                sync(player); journal(current, true);
            }
        } catch (Exception failure) {
            success = false;
            LogUtils.getLogger().error("[JR_SWORD_DEMO_PREVIEW] RESTORE_FAILED: recovery journal retained", failure);
        }
        published = new State(false, true, passed && success, current.steps, -1, -1, reason + "; cleanup=" + success);
        LogUtils.getLogger().info("[JR_SWORD_DEMO_PREVIEW] {} RESTORED={} fixturesRemoved={} reason={}; no terrain writes were performed",
                passed && success ? "PASS" : "FAIL", success, removed, reason);
    }

    private static void sync(ServerPlayer player) {
        player.getInventory().setChanged(); player.inventoryMenu.broadcastFullState();
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
    }
    private static void journal(Session current, boolean restored) throws Exception {
        CompoundTag tag = new CompoundTag(); tag.putBoolean("Restored", restored);
        tag.putString("Purpose", "B01 sword demo visual preview; original player and time; fixture entities only, no terrain mutations");
        tag.put("Player", current.snapshot.player.copy()); tag.putLong("OriginalDayTime", current.snapshot.dayTime);
        tag.putString("Dimension", current.snapshot.level.dimension().location().toString());
        NbtIo.writeCompressed(tag, current.server.getWorldPath(LevelResource.ROOT).resolve("jr-sword-demo-preview-recovery.nbt"));
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (session != null && event.getEntity().getUUID().equals(session.playerId)) restore(session, false, "Logout before completion");
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (session != null && event.getServer() == session.server) restore(session, false, "Server stopping before completion");
    }
    private SwordDemoPreview() {}
}
