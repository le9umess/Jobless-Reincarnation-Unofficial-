package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.FireBallProjectile;
import dev.joblessreincarnation.magic.FireMagic;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.FireTrainingTrials;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises actual release admission, payment and projectile sweeps; never directly grants a shot marker. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FireTrainingGameTests {
    private static final class Trainee extends FakePlayer {
        boolean spectator;
        final List<String> trainingMessages = new ArrayList<>();
        Trainee(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "FireTraining")); }
        @Override public boolean isSpectator() { return spectator || super.isSpectator(); }
        @Override public void displayClientMessage(Component message, boolean overlay) {
            if (message.getContents() instanceof TranslatableContents text
                    && text.getKey().startsWith("message.jobless_reincarnation.fire_training_")) {
                if (!overlay) throw new IllegalStateException("Training feedback must be private actionbar text");
                trainingMessages.add(text.getKey());
            }
        }
    }
    private static Trainee trainee(GameTestHelper h, int lane, double z) {
        Trainee p = new Trainee(h.getLevel());
        p.setPos(h.absoluteVec(new Vec3(lane + 0.5, 2.6 - p.getEyeHeight(), z)));
        p.setYRot(0); p.setXRot(0); p.getAbilities().instabuild = false;
        p.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        p.getData(ModAttachments.KNOWLEDGE).learn(FireMagic.SPELL);
        p.getData(ModAttachments.CASTING).select(FireMagic.SPELL);
        resetResources(p);
        return p;
    }
    private static void resetResources(Trainee p) {
        p.setData(ModAttachments.MANA, ManaData.full(100));
        p.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
    }
    private static int hits(Trainee p) {
        return p.hasData(ModAttachments.TRAINING) ? p.getData(ModAttachments.TRAINING).fireTargetHits() : 0;
    }
    private static FireBallProjectile release(GameTestHelper h, Trainee p) {
        h.assertTrue(FireMagic.cast(p), "Normal server release succeeds");
        var shots = h.findEntities(ModContent.FIRE_BALL.get(), new Vec3(8, 4, 8), 20)
                .stream().filter(b -> b.getOwner() == p && !b.isRemoved()).toList();
        h.assertValueEqual(shots.size(), 1, "One admitted projectile for this caster");
        return shots.getFirst();
    }
    private static void impact(GameTestHelper h, FireBallProjectile ball) {
        for (int tick = 0; tick < FireBallProjectile.LIFETIME_TICKS + 1 && !ball.isRemoved(); tick++) ball.tick();
        h.assertTrue(ball.isRemoved(), "The actual sweep consumes or expires the projectile");
    }
    private static void target(GameTestHelper h, int lane) { h.setBlock(new BlockPos(lane, 2, 9), Blocks.TARGET); }
    private static BlockHitResult targetHit(GameTestHelper h, int lane) {
        return new BlockHitResult(h.absoluteVec(new Vec3(lane + 0.5, 2.5, 9)), Direction.NORTH,
                h.absolutePos(new BlockPos(lane, 2, 9)), false);
    }
    private static void practice(Trainee p, int count) {
        for (int n = 0; n < count; n++) p.getData(ModAttachments.KNOWLEDGE).practice(FireMagic.SPELL);
    }
    private static void waterUnchanged(GameTestHelper h, Trainee p) {
        TrainingProgress data = p.getData(ModAttachments.TRAINING);
        h.assertValueEqual(data.targetHits(), 3, "Existing water accuracy retained");
        h.assertTrue(data.chargedTargetHit() && data.claimed(), "Water shaping and claimed flags retained");
    }

    @GameTest(template = "empty")
    public static void oldCodecDefaultsFireAndBoundsOnlyNewField(GameTestHelper h) {
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("target_hits", 2); legacy.putBoolean("charged_target_hit", true); legacy.putBoolean("claimed", true);
        TrainingProgress old = TrainingProgress.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow();
        h.assertValueEqual(old.fireTargetHits(), 0, "0.14 three-field saves start fire accuracy at zero");
        h.assertValueEqual(old.targetHits(), 2, "Legacy water count retains its meaning");
        h.assertTrue(old.chargedTargetHit() && old.claimed(), "Legacy flags retained");
        h.assertValueEqual(new TrainingProgress(2, true, true).fireTargetHits(), 0, "Three-argument callers remain compatible");
        legacy.putInt("fire_target_hits", Integer.MIN_VALUE);
        h.assertValueEqual(TrainingProgress.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow().fireTargetHits(), 0, "Negative fire count clamps");
        legacy.putInt("fire_target_hits", Integer.MAX_VALUE);
        TrainingProgress capped = TrainingProgress.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow();
        h.assertValueEqual(capped.fireTargetHits(), 3, "Large fire count clamps");
        h.assertValueEqual(capped.targetHits(), 2, "Clamping fire never changes water");
        h.assertFalse(capped.recordFireTargetHit(), "Completed accuracy stops emitting changes");
        TrainingProgress fresh = new TrainingProgress();
        CompoundTag encoded = (CompoundTag) TrainingProgress.CODEC.encodeStart(NbtOps.INSTANCE, fresh).getOrThrow();
        h.assertFalse(encoded.contains("fire_target_hits"), "Zero fire progress remains an optional field");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void saveAndDeathCopiesRetainIndependentWaterAndFireState(GameTestHelper h) {
        Trainee old = trainee(h, 8, 3.5);
        old.setData(ModAttachments.TRAINING, new TrainingProgress(3, true, true, 2));
        practice(old, 5);
        Trainee saved = trainee(h, 4, 3.5);
        saved.load(old.saveWithoutId(new CompoundTag()));
        h.assertValueEqual(hits(saved), 2, "Player save and load retains the new field");
        waterUnchanged(h, saved);
        h.assertValueEqual(FireMagic.practiceCount(saved), 5, "Existing spell casts retain their value");
        Trainee clone = trainee(h, 12, 3.5);
        clone.copyAttachmentsFrom(old, true);
        h.assertValueEqual(hits(clone), 2, "Death copies personal fire accuracy");
        waterUnchanged(h, clone);
        h.assertTrue(clone.getData(ModAttachments.TRAINING).recordFireTargetHit(), "Respawned player can finish independently");
        h.assertValueEqual(hits(old), 2, "Death copy does not share the mutable progress object");
        Trainee nonDeath = trainee(h, 4, 3.5);
        nonDeath.copyAttachmentsFrom(clone, false);
        h.assertTrue(FireTrainingTrials.completed(nonDeath), "End-return clone retains completion without another claim flag");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void successfulSurvivalReleasePaysOnceAndCreditsOnlyItsOwner(GameTestHelper h) {
        target(h, 4); target(h, 12);
        Trainee a = trainee(h, 4, 3.5), b = trainee(h, 12, 3.5);
        a.setData(ModAttachments.TRAINING, new TrainingProgress(3, true, true));
        FireBallProjectile shot = release(h, a);
        a.setPos(h.absoluteVec(new Vec3(4.5, 2, 8.6)));
        impact(h, shot);
        h.assertValueEqual(hits(a), 1, "Distant release still counts after shooter walks toward the target");
        h.assertValueEqual(hits(b), 0, "Observer cannot receive the shooter's progress");
        h.assertValueEqual(b.trainingMessages.size(), 0, "Observer receives no feedback");
        h.assertValueEqual(a.trainingMessages.size(), 1, "Shooter alone receives one feedback");
        h.assertValueEqual(FireMagic.practiceCount(a), 1, "Impact does not duplicate release practice");
        h.assertValueEqual(a.getData(ModAttachments.MANA).mana(), 100 - MagicConfig.FIRE_BALL_COST.getAsInt(), "One ordinary fee");
        waterUnchanged(h, a);
        impact(h, release(h, b));
        h.assertValueEqual(hits(a), 1, "Second player does not advance the first");
        h.assertValueEqual(hits(b), 1, "Second player receives their own shot");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void exactFourBlockFaceCountsButCloseReleaseCannotBeRescuedByWalking(GameTestHelper h) {
        target(h, 4); target(h, 12);
        Trainee exact = trainee(h, 4, 5.0), close = trainee(h, 12, 5.001);
        FireBallProjectile atBoundary = release(h, exact), tooClose = release(h, close);
        close.setPos(h.absoluteVec(new Vec3(12.5, 2, 1)));
        impact(h, atBoundary); impact(h, tooClose);
        h.assertValueEqual(hits(exact), 1, "Actual impact face at exactly four blocks qualifies");
        h.assertValueEqual(hits(close), 0, "Actual launch point below four blocks remains ineligible");
        h.assertValueEqual(FireMagic.practiceCount(close), 1, "Close but valid release still earns normal spell practice");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void courseCompletionNeedsFiveCastsAndCapsFeedbackWithoutTouchingWaterRewards(GameTestHelper h) {
        target(h, 8);
        Trainee p = trainee(h, 8, 3.5);
        p.setData(ModAttachments.TRAINING, new TrainingProgress(3, true, true));
        practice(p, 2); // Existing release history is valid; all three accuracy points below use real casts.
        for (int i = 0; i < 4; i++) { resetResources(p); impact(h, release(h, p)); }
        h.assertValueEqual(hits(p), 3, "Accuracy saturates at the objective");
        h.assertValueEqual(FireMagic.practiceCount(p), 6, "All four new releases still count normally");
        h.assertTrue(FireTrainingTrials.completed(p), "Five casts and three distant target hits complete the fire course");
        h.assertValueEqual(p.trainingMessages.size(), 3, "Saturated target cannot repeatedly spam progress or completion");
        h.assertValueEqual(p.trainingMessages.get(2), "message.jobless_reincarnation.fire_training_complete", "Final qualified hit reports completion privately");
        waterUnchanged(h, p);
        h.assertFalse(TrainingTrials.claim(p), "Existing claimed water record remains claimed");
        h.assertFalse(p.getInventory().contains(ModContent.TRAINING_RECORD.get().getDefaultInstance()), "Fire training grants no item");
        Trainee unfinished = trainee(h, 4, 3.5);
        unfinished.setData(ModAttachments.TRAINING, new TrainingProgress(3, true, false, 3));
        practice(unfinished, 4);
        h.assertFalse(FireTrainingTrials.completed(unfinished), "Three hits do not replace the five-cast objective");
        unfinished.setData(ModAttachments.TRAINING, new TrainingProgress(0, false, false, 3));
        practice(unfinished, 1);
        h.assertTrue(FireTrainingTrials.completed(unfinished), "Fire completion needs no water charged objective");
        h.assertFalse(TrainingTrials.claim(unfinished), "Fire completion cannot unlock the water card");
        Trainee noProgress = trainee(h, 12, 3.5);
        h.assertFalse(FireTrainingTrials.completed(noProgress), "New trainee is incomplete");
        h.assertFalse(noProgress.hasData(ModAttachments.TRAINING), "Read-only completion check creates no attachment");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeOriginUnmarkedAndForgedDirectHitsNeverEarnTraining(GameTestHelper h) {
        target(h, 4); target(h, 12);
        Trainee creative = trainee(h, 4, 3.5), direct = trainee(h, 12, 3.5);
        creative.getAbilities().instabuild = true;
        FireBallProjectile free = release(h, creative);
        creative.getAbilities().instabuild = false;
        FireBallProjectile unmarked = new FireBallProjectile(h.getLevel(), direct);
        unmarked.shoot(0, 0, 1, 1.15F, 0);
        h.getLevel().addFreshEntity(unmarked);
        FireTrainingTrials.onTargetHit(unmarked, targetHit(h, 12));
        impact(h, free); impact(h, unmarked);
        h.assertValueEqual(hits(creative), 0, "Switching out of creative cannot retroactively mark a free shot");
        h.assertValueEqual(FireMagic.practiceCount(creative), 0, "Creative release stays free of practice");
        h.assertValueEqual(hits(direct), 0, "Constructed or command-origin shot has no runtime receipt");
        resetResources(direct);
        FireBallProjectile real = release(h, direct);
        FireTrainingTrials.onTargetHit(real, targetHit(h, 12));
        h.assertValueEqual(hits(direct), 0, "Even a real shot cannot score through a fabricated early service call");
        impact(h, real);
        h.assertValueEqual(hits(direct), 1, "Invalid early service call does not consume the later genuine hit");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void nbtRoundTripAndDataMergeCannotRestoreRuntimeTrainingReceipt(GameTestHelper h) {
        target(h, 4); target(h, 12);
        Trainee saved = trainee(h, 4, 3.5), merged = trainee(h, 12, 3.5);
        FireBallProjectile original = release(h, saved);
        CompoundTag nbt = original.saveWithoutId(new CompoundTag());
        h.assertFalse(nbt.contains("TrainingShot") || nbt.contains("TrainingX") || nbt.contains("TrainingOrigin"), "No training receipt is written to NBT");
        original.discard();
        nbt.putBoolean("TrainingShot", true); nbt.putDouble("TrainingX", saved.getX());
        nbt.putDouble("TrainingY", saved.getEyeY() - 0.1); nbt.putDouble("TrainingZ", saved.getZ());
        FireBallProjectile restored = new FireBallProjectile(ModContent.FIRE_BALL.get(), h.getLevel());
        restored.load(nbt); restored.setOwner(saved);
        h.getLevel().addFreshEntity(restored);
        impact(h, restored);
        h.assertValueEqual(hits(saved), 0, "A restored projectile cannot earn a guessed or forged receipt");
        FireBallProjectile reread = release(h, merged);
        CompoundTag sameEntity = reread.saveWithoutId(new CompoundTag());
        reread.readAdditionalSaveData(sameEntity); reread.setOwner(merged);
        impact(h, reread);
        h.assertValueEqual(hits(merged), 0, "Reading NBT into an existing marked object clears runtime eligibility too");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void waterWallsAndEntitiesPreventTargetCreditAndNeverPowerTarget(GameTestHelper h) {
        target(h, 4); target(h, 8); target(h, 12);
        h.setBlock(new BlockPos(4, 2, 7), Blocks.WATER);
        h.setBlock(new BlockPos(8, 2, 7), Blocks.STONE);
        var pig = EntityType.PIG.create(h.getLevel());
        pig.setPos(h.absoluteVec(new Vec3(12.5, 2, 7))); pig.setNoAi(true); pig.setNoGravity(true);
        h.getLevel().addFreshEntity(pig);
        for (int lane : new int[]{4, 8, 12}) {
            Trainee p = trainee(h, lane, 3.5);
            impact(h, release(h, p));
            h.assertValueEqual(hits(p), 0, "An earlier water, solid or living collision prevents target credit");
            h.assertValueEqual(h.getBlockState(new BlockPos(lane, 2, 9)).getValue(BlockStateProperties.POWER), 0, "No target block callback or redstone");
        }
        h.setBlock(new BlockPos(4, 2, 7), Blocks.AIR);
        Trainee direct = trainee(h, 4, 3.5);
        impact(h, release(h, direct));
        h.assertValueEqual(hits(direct), 1, "Unobstructed target hit does count");
        h.assertValueEqual(h.getBlockState(new BlockPos(4, 2, 9)).getValue(BlockStateProperties.POWER), 0, "Counted hit still never calls vanilla target activation");
        h.assertBlockPresent(Blocks.TARGET, new BlockPos(4, 2, 9));
        h.assertBlockPresent(Blocks.STONE, new BlockPos(8, 2, 7));
        h.assertBlockNotPresent(Blocks.FIRE, new BlockPos(4, 2, 8));
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void impactCancellationAndReentrantTicksCannotCreditOrRepeat(GameTestHelper h) {
        target(h, 8);
        Trainee p = trainee(h, 8, 3.5);
        AtomicInteger callbacks = new AtomicInteger();
        Consumer<ProjectileImpactEvent> hook = event -> {
            if (event.getProjectile() instanceof FireBallProjectile ball && ball.getOwner() == p) {
                callbacks.incrementAndGet();
                ball.tick();
                if (event.getRayTraceResult() instanceof BlockHitResult hit) FireTrainingTrials.onTargetHit(ball, hit);
                h.assertValueEqual(hits(p), 0, "External impact hook cannot settle before its cancellation decision");
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(ProjectileImpactEvent.class, hook);
        FireBallProjectile shot;
        try { shot = release(h, p); impact(h, shot); }
        finally { NeoForge.EVENT_BUS.unregister(hook); }
        h.assertValueEqual(callbacks.get(), 1, "Resolved-before-hook guard blocks recursive tick");
        h.assertValueEqual(hits(p), 0, "Canceled impact grants nothing");
        shot.tick(); FireTrainingTrials.onTargetHit(shot, targetHit(h, 8));
        h.assertValueEqual(hits(p), 0, "Repeated tick or service call cannot revive canceled shot");
        resetResources(p); FireBallProjectile accepted = release(h, p); impact(h, accepted);
        accepted.tick(); accepted.tick(); FireTrainingTrials.onTargetHit(accepted, targetHit(h, 8));
        h.assertValueEqual(hits(p), 1, "Accepted hit also settles exactly once");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidOwnerAtImpactNeverReceivesProgress(GameTestHelper h) {
        target(h, 8);
        for (int kind = 0; kind < 6; kind++) {
            Trainee p = trainee(h, 8, 3.5);
            FireBallProjectile shot = release(h, p);
            switch (kind) {
                case 0 -> p.setHealth(0);
                case 1 -> p.spectator = true;
                case 2 -> p.getAbilities().instabuild = true;
                case 3 -> p.setData(ModAttachments.KNOWLEDGE, new MagicKnowledge());
                case 4 -> p.setServerLevel(h.getLevel().getServer().getLevel(Level.NETHER));
                case 5 -> p.discard();
            }
            impact(h, shot);
            h.assertValueEqual(hits(p), 0, "Impact rechecks alive, spectator, creative, knowledge, dimension and removal state: " + kind);
            h.assertValueEqual(p.trainingMessages.size(), 0, "Invalid owner receives no progress message");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void impactHookStateChangesAreCheckedAfterTheEvent(GameTestHelper h) {
        target(h, 4); target(h, 12);
        Trainee changedPlayer = trainee(h, 4, 3.5), changedBlock = trainee(h, 12, 3.5);
        Consumer<ProjectileImpactEvent> hook = event -> {
            if (event.getProjectile().getOwner() == changedPlayer) changedPlayer.getAbilities().instabuild = true;
            if (event.getProjectile().getOwner() == changedBlock) h.setBlock(new BlockPos(12, 2, 9), Blocks.STONE);
        };
        NeoForge.EVENT_BUS.addListener(ProjectileImpactEvent.class, hook);
        try { impact(h, release(h, changedPlayer)); impact(h, release(h, changedBlock)); }
        finally { NeoForge.EVENT_BUS.unregister(hook); }
        h.assertValueEqual(hits(changedPlayer), 0, "Post-event owner eligibility is authoritative");
        h.assertValueEqual(hits(changedBlock), 0, "Post-event current block must still be a target");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void admissionTickReentryAndFailedPaymentNeverReceiveAReceipt(GameTestHelper h) {
        target(h, 4); target(h, 12);
        Trainee early = trainee(h, 4, 3.5), unpaid = trainee(h, 12, 3.5);
        AtomicReference<FireBallProjectile> rejected = new AtomicReference<>();
        Consumer<EntityJoinLevelEvent> hook = event -> {
            if (event.getEntity() instanceof FireBallProjectile ball && ball.getOwner() == early) {
                h.assertFalse(FireMagic.cast(early), "Admission cannot reenter the release transaction");
                impact(h, ball);
                h.assertValueEqual(hits(early), 0, "A sweep before admission/payment never carries a receipt");
            }
            if (event.getEntity() instanceof FireBallProjectile ball && ball.getOwner() == unpaid) {
                rejected.set(ball);
                unpaid.setData(ModAttachments.MANA, new ManaData(0, 100, 0, 0));
            }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, hook);
        try {
            h.assertFalse(FireMagic.cast(early), "Removed admission cannot become a completed release");
            h.assertFalse(FireMagic.cast(unpaid), "Resources lost during admission prevent settlement");
        } finally { NeoForge.EVENT_BUS.unregister(hook); }
        h.assertValueEqual(FireMagic.practiceCount(early), 0, "Early invalidated admission gets no cast practice");
        h.assertValueEqual(early.getData(ModAttachments.MANA).mana(), 100, "Early invalidated admission gets no fee");
        h.assertTrue(rejected.get() != null && rejected.get().isRemoved(), "Unpaid admitted shot is discarded");
        rejected.get().tick();
        h.assertValueEqual(hits(unpaid), 0, "Unpaid shot cannot later credit a target");
        h.assertValueEqual(FireMagic.practiceCount(unpaid), 0, "Unpaid shot has no spell practice");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void admissionRelocationUsesOriginalLaunchPoint(GameTestHelper h) {
        target(h, 8);
        Trainee p = trainee(h, 8, 5.0);
        Consumer<EntityJoinLevelEvent> hook = event -> {
            if (event.getEntity() instanceof FireBallProjectile ball && ball.getOwner() == p)
                ball.setPos(ball.getX(), ball.getY(), h.absoluteVec(new Vec3(8.5, 2.5, 8.5)).z);
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, hook);
        FireBallProjectile shot;
        try { shot = release(h, p); }
        finally { NeoForge.EVENT_BUS.unregister(hook); }
        impact(h, shot);
        h.assertValueEqual(hits(p), 1, "Admission movement cannot replace the original release position");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void projectileOwnerChangesCannotTransferAnotherPlayersTraining(GameTestHelper h) {
        target(h, 4); target(h, 12);
        Trainee original = trainee(h, 4, 3.5), other = trainee(h, 12, 3.5);
        FireBallProjectile shot = release(h, original);
        shot.setOwner(other);
        impact(h, shot);
        h.assertValueEqual(hits(original), 0, "Transferred projectile cannot credit the former owner");
        h.assertValueEqual(hits(other), 0, "Transferred projectile cannot credit the new owner");
        resetResources(original);
        FireBallProjectile respawnShot = release(h, original);
        Trainee respawn = trainee(h, 4, 3.5);
        respawn.setUUID(original.getUUID()); respawn.setId(original.getId());
        respawnShot.setOwner(respawn);
        impact(h, respawnShot);
        h.assertValueEqual(hits(respawn), 0, "Same UUID and entity ID do not substitute a new player object for the original release owner");
        resetResources(original);
        AtomicReference<FireBallProjectile> admitted = new AtomicReference<>();
        Consumer<EntityJoinLevelEvent> hook = event -> {
            if (event.getEntity() instanceof FireBallProjectile ball && ball.getOwner() == original) {
                ball.setOwner(other); admitted.set(ball);
            }
        };
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, hook);
        try { h.assertTrue(FireMagic.cast(original), "Preserve existing release settlement semantics under a third-party owner hook"); }
        finally { NeoForge.EVENT_BUS.unregister(hook); }
        impact(h, admitted.get());
        h.assertValueEqual(hits(other), 0, "Admission-time owner changes receive no training receipt either");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cappedCastHistoryDoesNotPreventFurtherLegitimateAccuracyPractice(GameTestHelper h) {
        target(h, 8);
        Trainee p = trainee(h, 8, 3.5);
        CompoundTag spells = new CompoundTag(); spells.putInt(FireMagic.SPELL.toString(), MagicKnowledge.MAX_PRACTICE);
        CompoundTag data = new CompoundTag(); data.put("spells", spells);
        p.setData(ModAttachments.KNOWLEDGE, MagicKnowledge.CODEC.parse(NbtOps.INSTANCE, data).getOrThrow());
        impact(h, release(h, p));
        h.assertValueEqual(FireMagic.practiceCount(p), MagicKnowledge.MAX_PRACTICE, "Existing counter remains bounded");
        h.assertValueEqual(hits(p), 1, "A successfully paid survival release is valid even when its historical cast count is capped");
        h.assertValueEqual(WaterMagic.practiceCount(p), 0, "Fire shot cannot invent water cast history");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aMissAndCanceledAdmissionNeverCreateAccuracyProgress(GameTestHelper h) {
        Trainee miss = trainee(h, 4, 3.5), canceled = trainee(h, 12, 3.5);
        FireBallProjectile airborne = release(h, miss);
        // ServerLevel.tickNonPassenger owns tickCount. Let native world ticks expire the paid miss;
        // the synchronous impact helper below deliberately exercises sweeps without simulating age.
        airborne.setDeltaMovement(Vec3.ZERO);
        h.runAfterDelay(FireBallProjectile.LIFETIME_TICKS + 2, () -> {
            h.assertTrue(airborne.isRemoved(), "Native server entity ticks expire the missed projectile");
            h.assertValueEqual(hits(miss), 0, "A missed projectile never fabricates target accuracy");
            h.assertValueEqual(FireMagic.practiceCount(miss), 1, "A paid miss retains ordinary cast practice");
            h.assertFalse(miss.hasData(ModAttachments.TRAINING), "A miss need not allocate target progress");
            AtomicReference<FireBallProjectile> rejected = new AtomicReference<>();
            Consumer<EntityJoinLevelEvent> hook = event -> {
                if (event.getEntity() instanceof FireBallProjectile ball && ball.getOwner() == canceled) {
                    rejected.set(ball); event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, hook);
            try { h.assertFalse(FireMagic.cast(canceled), "Canceled admission does not settle release"); }
            finally { NeoForge.EVENT_BUS.unregister(hook); }
            target(h, 12);
            // Another mod retaining the rejected object still cannot turn it into a paid training shot.
            impact(h, rejected.get());
            h.assertValueEqual(hits(canceled), 0, "Rejected admission never gets a runtime receipt");
            h.assertValueEqual(FireMagic.practiceCount(canceled), 0, "Rejected admission retains no practice");
            h.assertValueEqual(canceled.getData(ModAttachments.MANA).mana(), 100, "Rejected admission remains free");
            h.succeed();
        });
    }
    @GameTest(template = "empty")
    public static void impactHookCannotTurnAStoneCollisionIntoTargetAccuracy(GameTestHelper h) {
        h.setBlock(new BlockPos(4, 2, 9), Blocks.STONE);
        target(h, 12);
        Trainee stone = trainee(h, 4, 3.5), target = trainee(h, 12, 3.5);
        AtomicInteger changes = new AtomicInteger();
        Consumer<ProjectileImpactEvent> hook = event -> {
            if (event.getProjectile().getOwner() == stone) {
                h.setBlock(new BlockPos(4, 2, 9), Blocks.TARGET); changes.incrementAndGet();
            }
            if (event.getProjectile().getOwner() == target) {
                h.setBlock(new BlockPos(12, 2, 9), Blocks.STONE); changes.incrementAndGet();
            }
        };
        NeoForge.EVENT_BUS.addListener(ProjectileImpactEvent.class, hook);
        try { impact(h, release(h, stone)); impact(h, release(h, target)); }
        finally { NeoForge.EVENT_BUS.unregister(hook); }
        h.assertValueEqual(changes.get(), 2, "Both real block impacts reached the external event");
        h.assertValueEqual(hits(stone), 0, "Stone at the actual collision cannot become target credit during the event");
        h.assertValueEqual(hits(target), 0, "A removed target remains ineligible after the event");
        h.assertBlockPresent(Blocks.TARGET, new BlockPos(4, 2, 9));
        h.assertBlockPresent(Blocks.STONE, new BlockPos(12, 2, 9));
        h.assertValueEqual(h.getBlockState(new BlockPos(4, 2, 9)).getValue(BlockStateProperties.POWER), 0, "Third-party block change is retained without redstone activation");
        h.succeed();
    }
    private FireTrainingGameTests() {}
}
