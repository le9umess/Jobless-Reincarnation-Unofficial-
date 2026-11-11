package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.WaterBolt;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingBoardBlock;
import dev.joblessreincarnation.training.TrainingBoardMenu;
import dev.joblessreincarnation.training.TrainingProgress;
import dev.joblessreincarnation.training.TrainingTrials;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Development-only integration tests for personal training and the shared training board. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TrainingGameTests {
    private static final class Trainee extends FakePlayer {
        private boolean spectator;

        private Trainee(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "TrainingTest"));
        }

        @Override
        public boolean isSpectator() { return spectator || super.isSpectator(); }
    }

    private static Trainee trainee(GameTestHelper helper, int lane, double startZ) {
        Trainee player = new Trainee(helper.getLevel());
        Vec3 position = helper.absoluteVec(new Vec3(lane + 0.5, 2.6 - player.getEyeHeight(), startZ));
        player.setPos(position);
        player.setYRot(0);
        player.setXRot(0);
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
        return player;
    }

    private static Trainee trainee(GameTestHelper helper) { return trainee(helper, 8, 3.5); }

    private static WaterBolt fire(GameTestHelper helper, Trainee player, float charge) {
        helper.assertTrue(WaterMagic.cast(player, charge), "Fixture casts through the normal server entry point");
        var bolts = helper.findEntities(ModContent.WATER_BOLT.get(), new Vec3(8, 4, 8), 16)
                .stream().filter(bolt -> bolt.getOwner() == player).toList();
        helper.assertValueEqual(bolts.size(), 1, "Exactly one projectile belongs to this caster");
        return bolts.getFirst();
    }

    private static void addPractice(Trainee player, int count) {
        for (int i = 0; i < count; i++) player.getData(ModAttachments.KNOWLEDGE).practice(WaterMagic.SPELL);
    }

    private static void finishTrials(Trainee player) {
        while (WaterMagic.practiceCount(player) < TrainingTrials.CAST_GOAL) addPractice(player, 1);
        for (int i = 0; i < TrainingTrials.HIT_GOAL; i++) {
            player.getData(ModAttachments.TRAINING).recordTargetHit(true);
        }
    }

    private static int records(Trainee player) {
        int result = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(ModContent.TRAINING_RECORD.get())) result += stack.getCount();
        }
        return result;
    }

    private static BlockPos placeBoard(GameTestHelper helper) {
        BlockPos relative = new BlockPos(8, 2, 8);
        helper.setBlock(relative.south(), Blocks.STONE);
        helper.setBlock(relative, ModContent.TRAINING_BOARD.get().defaultBlockState()
                .setValue(TrainingBoardBlock.FACING, Direction.NORTH));
        return helper.absolutePos(relative);
    }

    private static TrainingBoardMenu openBoard(Trainee player, BlockPos pos) {
        TrainingBoardMenu menu = new TrainingBoardMenu(1, player.getInventory(), pos);
        // FakePlayer.openMenu deliberately does nothing; use the same server container field vanilla sets.
        player.containerMenu = menu;
        return menu;
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void fullTargetHitUsesLaunchDistanceAndOnlyItsOwner(GameTestHelper helper) {
        helper.setBlock(new BlockPos(8, 2, 9), Blocks.TARGET);
        Trainee player = trainee(helper);
        Trainee other = trainee(helper, 4, 3.5);
        WaterBolt bolt = fire(helper, player, 1.0F);
        // Walking close after release must not invalidate an already distant shot.
        player.setPos(helper.absoluteVec(new Vec3(8.5, 2, 8.5)));
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(bolt.isRemoved(), "The projectile actually collided and was consumed");
            helper.assertValueEqual(player.getData(ModAttachments.TRAINING).targetHits(), 1, "One distant collision counts once");
            helper.assertTrue(player.getData(ModAttachments.TRAINING).chargedTargetHit(), "A fully shaped hit completes the charge objective");
            helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "A later hit cannot grant a second casting practice point");
            helper.assertValueEqual(other.getData(ModAttachments.TRAINING).targetHits(), 0, "Nearby players receive no shared credit");
            helper.assertFalse(other.getData(ModAttachments.TRAINING).chargedTargetHit(), "Charged credit belongs to the shooter");
            helper.assertBlockPresent(Blocks.TARGET, new BlockPos(8, 2, 9));
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void partialChargeHitDoesNotFinishFullChargeObjective(GameTestHelper helper) {
        helper.setBlock(new BlockPos(8, 2, 9), Blocks.TARGET);
        // The target's north collision face is exactly four blocks from the launch point.
        Trainee player = trainee(helper, 8, 9.0 - TrainingTrials.MIN_RANGE);
        WaterBolt bolt = fire(helper, player, 0.99F);
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(bolt.isRemoved(), "The partially charged shot reached the target");
            helper.assertValueEqual(player.getData(ModAttachments.TRAINING).targetHits(), 1, "A shot exactly at the minimum range counts");
            helper.assertFalse(player.getData(ModAttachments.TRAINING).chargedTargetHit(), "Almost full is not a full charge");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void closeTargetCannotGainCreditByMovingAwayAfterRelease(GameTestHelper helper) {
        helper.setBlock(new BlockPos(8, 2, 9), Blocks.TARGET);
        Trainee player = trainee(helper, 8, 9.001 - TrainingTrials.MIN_RANGE);
        WaterBolt bolt = fire(helper, player, 1.0F);
        player.setPos(helper.absoluteVec(new Vec3(8.5, 2, 2)));
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(bolt.isRemoved(), "The close shot hit the target");
            helper.assertValueEqual(player.getData(ModAttachments.TRAINING).targetHits(), 0, "Distance is measured at release, not at impact");
            helper.assertFalse(player.getData(ModAttachments.TRAINING).chargedTargetHit(), "A close full charge does not satisfy training");
            helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "The otherwise valid cast retains ordinary spell practice");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void creativeAndUnmarkedProjectilesCannotEarnTargetCredit(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, 2, 9), Blocks.TARGET);
        helper.setBlock(new BlockPos(12, 2, 9), Blocks.TARGET);
        Trainee creative = trainee(helper, 4, 3.5);
        creative.getAbilities().instabuild = true;
        WaterBolt creativeBolt = fire(helper, creative, 1.0F);
        // Switching back before impact cannot turn a free creative cast into a training shot.
        creative.getAbilities().instabuild = false;
        Trainee summonedOwner = trainee(helper, 12, 3.5);
        WaterBolt summoned = new WaterBolt(helper.getLevel(), summonedOwner);
        summoned.setCharge(1.0F);
        summoned.shoot(0, 0, 1, 1.65F, 0);
        helper.getLevel().addFreshEntity(summoned);
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(creativeBolt.isRemoved() && summoned.isRemoved(), "Both ineligible projectiles actually hit their targets");
            helper.assertValueEqual(creative.getData(ModAttachments.TRAINING).targetHits(), 0, "Creative origin receives no target credit");
            helper.assertValueEqual(WaterMagic.practiceCount(creative), 0, "Creative origin receives no casting practice");
            helper.assertValueEqual(summonedOwner.getData(ModAttachments.TRAINING).targetHits(), 0, "A constructed projectile cannot bypass the real casting path");
            helper.assertFalse(summonedOwner.getData(ModAttachments.TRAINING).chargedTargetHit(), "Unmarked charge grants no objective");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void wallsBoardsAndLivingTargetsDoNotCountAsArcheryTargets(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, 2, 9), Blocks.STONE);
        BlockPos board = placeBoard(helper);
        var cow = helper.spawn(EntityType.COW, new BlockPos(12, 2, 9));
        cow.setNoAi(true);
        cow.setNoGravity(true);
        Trainee wallShooter = trainee(helper, 4, 3.5);
        Trainee boardShooter = trainee(helper, 8, 3.5);
        Trainee cowShooter = trainee(helper, 12, 3.5);
        WaterBolt wallShot = fire(helper, wallShooter, 1.0F);
        WaterBolt boardShot = fire(helper, boardShooter, 1.0F);
        WaterBolt cowShot = fire(helper, cowShooter, 1.0F);
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(wallShot.isRemoved() && boardShot.isRemoved() && cowShot.isRemoved(), "All three real collisions occurred");
            for (Trainee player : List.of(wallShooter, boardShooter, cowShooter)) {
                helper.assertValueEqual(player.getData(ModAttachments.TRAINING).targetHits(), 0, "Only vanilla target blocks grant target credit");
                helper.assertFalse(player.getData(ModAttachments.TRAINING).chargedTargetHit(), "Other collisions do not complete charged accuracy");
                helper.assertValueEqual(WaterMagic.practiceCount(player), 1, "A collision adds no casting experience");
            }
            helper.assertTrue(helper.getLevel().getBlockState(board).is(ModContent.TRAINING_BOARD.get()), "Shooting the board does not destroy it");
            helper.assertTrue(cow.getHealth() < cow.getMaxHealth(), "The living target still took ordinary spell damage");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void deadSpectatorOrCreativeOwnersAtImpactCannotProgress(GameTestHelper helper) {
        Trainee dead = trainee(helper, 4, 3.5);
        Trainee spectator = trainee(helper, 8, 3.5);
        Trainee creative = trainee(helper, 12, 3.5);
        for (int lane : List.of(4, 8, 12)) helper.setBlock(new BlockPos(lane, 2, 9), Blocks.TARGET);
        WaterBolt deadShot = fire(helper, dead, 1.0F);
        WaterBolt spectatorShot = fire(helper, spectator, 1.0F);
        WaterBolt creativeShot = fire(helper, creative, 1.0F);
        dead.setHealth(0);
        spectator.spectator = true;
        creative.getAbilities().instabuild = true;
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(deadShot.isRemoved() && spectatorShot.isRemoved() && creativeShot.isRemoved(), "Eligibility was checked at real impact");
            for (Trainee player : List.of(dead, spectator, creative)) {
                helper.assertValueEqual(player.getData(ModAttachments.TRAINING).targetHits(), 0, "Ineligible owner cannot gain delayed credit");
                helper.assertFalse(player.getData(ModAttachments.TRAINING).chargedTargetHit(), "Ineligible owner cannot gain delayed charged credit");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void existingPracticeCountsButEveryTrialIsRequired(GameTestHelper helper) {
        Trainee player = trainee(helper);
        addPractice(player, TrainingTrials.CAST_GOAL);
        helper.assertFalse(TrainingTrials.completed(player), "Older casting practice alone does not complete new accuracy trials");
        for (int i = 0; i < TrainingTrials.HIT_GOAL; i++) player.getData(ModAttachments.TRAINING).recordTargetHit(false);
        helper.assertFalse(TrainingTrials.completed(player), "Three basic hits still require one fully charged distant hit");
        player.getData(ModAttachments.TRAINING).recordTargetHit(true);
        helper.assertTrue(TrainingTrials.completed(player), "A later full hit can finish the charge objective after basic hits are complete");
        Trainee shortOnPractice = trainee(helper);
        addPractice(shortOnPractice, TrainingTrials.CAST_GOAL - 1);
        for (int i = 0; i < TrainingTrials.HIT_GOAL; i++) shortOnPractice.getData(ModAttachments.TRAINING).recordTargetHit(true);
        helper.assertFalse(TrainingTrials.completed(shortOnPractice), "Four prior casts do not meet the five-cast requirement");
        addPractice(shortOnPractice, 1);
        helper.assertTrue(TrainingTrials.completed(shortOnPractice), "The ordinary practice counter supplies the fifth cast");
        Trainee unlearned = trainee(helper);
        unlearned.removeData(ModAttachments.KNOWLEDGE);
        for (int i = 0; i < TrainingTrials.HIT_GOAL; i++) unlearned.getData(ModAttachments.TRAINING).recordTargetHit(true);
        helper.assertFalse(TrainingTrials.completed(unlearned), "Accuracy data alone cannot teach the spell");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void savingAndCloningRetainProgressWithoutSharingMutableState(GameTestHelper helper) {
        Trainee original = trainee(helper);
        addPractice(original, 2);
        original.getData(ModAttachments.TRAINING).recordTargetHit(false);
        Trainee restored = trainee(helper);
        restored.load(original.saveWithoutId(new CompoundTag()));
        helper.assertValueEqual(restored.getData(ModAttachments.TRAINING).targetHits(), 1, "Player NBT retains distant hits");
        helper.assertFalse(restored.getData(ModAttachments.TRAINING).chargedTargetHit(), "Save preserves incomplete objectives");
        helper.assertValueEqual(WaterMagic.practiceCount(restored), 2, "Existing spell progression survives alongside training");
        Trainee respawned = trainee(helper);
        respawned.copyAttachmentsFrom(original, true);
        respawned.getData(ModAttachments.TRAINING).recordTargetHit(true);
        helper.assertTrue(respawned.getData(ModAttachments.TRAINING).chargedTargetHit(), "Death clone can continue training");
        helper.assertValueEqual(original.getData(ModAttachments.TRAINING).targetHits(), 1, "Clone mutations do not change the former player's progress");
        helper.assertFalse(original.getData(ModAttachments.TRAINING).chargedTargetHit(), "Clone and original do not share mutable flags");
        finishTrials(original);
        helper.assertTrue(TrainingTrials.claim(original), "Fixture completes and claims before another save");
        Trainee claimedClone = trainee(helper);
        claimedClone.copyAttachmentsFrom(original, true);
        helper.assertTrue(claimedClone.getData(ModAttachments.TRAINING).claimed(), "Death cannot reset the once-only reward marker");
        Trainee endClone = trainee(helper);
        endClone.copyAttachmentsFrom(original, false);
        helper.assertTrue(endClone.getData(ModAttachments.TRAINING).claimed(), "Non-death clone also retains the reward marker");
        restored.load(original.saveWithoutId(new CompoundTag()));
        helper.assertTrue(restored.getData(ModAttachments.TRAINING).claimed(), "Reconnect/save retains reward ownership");
        helper.assertFalse(TrainingTrials.claim(restored), "Reload cannot duplicate the card");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void legacySaveStartsEmptyTrainingAndKeepsExistingMagic(GameTestHelper helper) {
        Trainee legacy = trainee(helper);
        addPractice(legacy, TrainingTrials.CAST_GOAL);
        helper.assertFalse(legacy.hasData(ModAttachments.TRAINING), "Fixture has no new training attachment");
        Trainee restored = trainee(helper);
        restored.load(legacy.saveWithoutId(new CompoundTag()));
        helper.assertTrue(WaterMagic.isLearned(restored), "Upgrading retains previously learned water magic");
        helper.assertValueEqual(WaterMagic.practiceCount(restored), TrainingTrials.CAST_GOAL, "Upgrading retains existing practice");
        TrainingProgress progress = restored.getData(ModAttachments.TRAINING);
        helper.assertValueEqual(progress.targetHits(), 0, "New accuracy objectives begin empty in an older save");
        helper.assertFalse(progress.chargedTargetHit() || progress.claimed(), "Old saves do not fabricate charge or reward completion");
        TrainingProgress defaults = TrainingProgress.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        helper.assertValueEqual(defaults.targetHits(), 0, "Missing optional serialized fields have safe defaults");
        helper.assertFalse(defaults.chargedTargetHit() || defaults.claimed(), "Empty serialized attachment has no completion flags");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rewardRequiresCompletionAndCannotBeClaimedTwice(GameTestHelper helper) {
        Trainee player = trainee(helper);
        helper.assertFalse(TrainingTrials.claim(player), "Incomplete trial grants no reward");
        helper.assertValueEqual(records(player), 0, "Rejected claim adds no inventory item");
        finishTrials(player);
        helper.assertTrue(TrainingTrials.claim(player), "All objectives grant one card");
        helper.assertValueEqual(records(player), 1, "Exactly one card is inserted");
        helper.assertValueEqual(ModContent.TRAINING_RECORD.get().getDefaultInstance().getMaxStackSize(), 1, "Training cards do not stack");
        helper.assertTrue(player.getData(ModAttachments.TRAINING).claimed(), "Successful inventory delivery records the claim");
        helper.assertFalse(TrainingTrials.claim(player), "Repeated claim is rejected");
        player.getInventory().clearContent();
        helper.assertFalse(TrainingTrials.claim(player), "Removing the card cannot reset personal completion");
        helper.assertValueEqual(records(player), 0, "A lost card is not silently duplicated");
        Trainee dead = trainee(helper);
        finishTrials(dead);
        dead.setHealth(0);
        helper.assertFalse(TrainingTrials.claim(dead), "Dead players cannot receive a reward");
        Trainee spectator = trainee(helper);
        finishTrials(spectator);
        spectator.spectator = true;
        helper.assertFalse(TrainingTrials.claim(spectator), "Spectators cannot receive a reward");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullInventoryKeepsTheRewardAvailableUntilThereIsSpace(GameTestHelper helper) {
        Trainee player = trainee(helper);
        finishTrials(player);
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        helper.assertFalse(TrainingTrials.claim(player), "A full inventory cannot accept the card");
        helper.assertFalse(player.getData(ModAttachments.TRAINING).claimed(), "Failed delivery preserves the player's claim");
        helper.assertValueEqual(records(player), 0, "Full inventory did not receive a hidden item");
        helper.assertItemEntityNotPresent(ModContent.TRAINING_RECORD.get());
        player.getInventory().setItem(17, ItemStack.EMPTY);
        helper.assertTrue(TrainingTrials.claim(player), "Making one slot available permits a later claim");
        helper.assertValueEqual(records(player), 1, "One slot receives one reward");
        helper.assertTrue(player.getInventory().getItem(17).is(ModContent.TRAINING_RECORD.get()), "Reward fills the newly freed slot");
        helper.assertFalse(TrainingTrials.claim(player), "Successful retry remains once-only");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void menuExposesPersonalProgressAndRejectsOtherPlayersOrClosedMenus(GameTestHelper helper) {
        BlockPos board = placeBoard(helper);
        Trainee owner = trainee(helper);
        Trainee other = trainee(helper, 4, 3.5);
        TrainingBoardMenu menu = openBoard(owner, board);
        helper.assertTrue(menu.learned(), "Menu sees the owner's learned spell");
        helper.assertValueEqual(menu.casts(), 0, "Menu starts with the owner's practice count");
        helper.assertFalse(menu.completed() || menu.claimed(), "New trainee cannot claim");
        finishTrials(owner);
        menu.broadcastChanges();
        helper.assertValueEqual(menu.casts(), TrainingTrials.CAST_GOAL, "Open menu observes later practice changes");
        helper.assertValueEqual(menu.hits(), TrainingTrials.HIT_GOAL, "Open menu observes target progress");
        helper.assertTrue(menu.chargedHit() && menu.completed(), "All personal objectives are reflected");
        other.containerMenu = menu;
        helper.assertFalse(menu.clickMenuButton(other, TrainingBoardMenu.CLAIM_BUTTON), "A different player cannot act on the owner's menu");
        helper.assertFalse(owner.getData(ModAttachments.TRAINING).claimed(), "Cross-player action cannot consume the owner's reward");
        helper.assertValueEqual(records(other), 0, "Cross-player action cannot divert a card");
        owner.containerMenu = owner.inventoryMenu;
        helper.assertFalse(menu.clickMenuButton(owner, TrainingBoardMenu.CLAIM_BUTTON), "A stale closed menu cannot claim");
        owner.containerMenu = menu;
        helper.assertFalse(menu.clickMenuButton(owner, 99), "Unknown button ids do not trigger reward delivery");
        helper.assertTrue(menu.clickMenuButton(owner, TrainingBoardMenu.CLAIM_BUTTON), "The active owner's legitimate menu can claim");
        menu.broadcastChanges();
        helper.assertTrue(menu.claimed(), "Open menu reflects successful reward delivery");
        helper.assertValueEqual(records(owner), 1, "Menu delivered the reward only to its owner");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void menuCannotClaimAfterWalkingAwayOrRemovingTheBoard(GameTestHelper helper) {
        BlockPos board = placeBoard(helper);
        Trainee player = trainee(helper);
        finishTrials(player);
        TrainingBoardMenu menu = openBoard(player, board);
        helper.assertTrue(menu.stillValid(player), "Nearby supported board is valid");
        Vec3 nearby = player.position();
        player.setPos(Vec3.atCenterOf(board).add(9, 0, 0));
        helper.assertFalse(menu.stillValid(player), "A board more than eight blocks away is invalid");
        helper.assertFalse(menu.clickMenuButton(player, TrainingBoardMenu.CLAIM_BUTTON), "Distance is rechecked on claim");
        player.setPos(nearby);
        helper.getLevel().setBlock(board, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertFalse(menu.stillValid(player), "Removing the board invalidates the menu");
        helper.assertFalse(menu.clickMenuButton(player, TrainingBoardMenu.CLAIM_BUTTON), "A removed board cannot grant a stale claim");
        helper.assertFalse(player.getData(ModAttachments.TRAINING).claimed(), "Invalid menu actions preserve the reward");
        helper.assertValueEqual(records(player), 0, "Invalid menus deliver no card");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void boardFacesTheClickedWallAndDropsWhenSupportIsRemoved(GameTestHelper helper) {
        BlockPos support = helper.absolutePos(new BlockPos(8, 2, 8));
        helper.getLevel().setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Trainee player = trainee(helper);
        TrainingBoardBlock block = ModContent.TRAINING_BOARD.get();
        for (Direction face : Direction.Plane.HORIZONTAL) {
            BlockPos pos = support.relative(face);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, support, false);
            BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, ModContent.TRAINING_BOARD_ITEM.get().getDefaultInstance(), hit);
            var state = block.getStateForPlacement(context);
            helper.assertTrue(state != null, "A sturdy vertical face accepts the board: " + face);
            helper.assertValueEqual(state.getValue(TrainingBoardBlock.FACING), face, "The front faces away from the clicked wall");
            helper.assertTrue(state.canSurvive(helper.getLevel(), pos), "The clicked wall supports the board");
            AABB shape = state.getShape(helper.getLevel(), pos).bounds();
            double min = face.getAxis() == Direction.Axis.X ? shape.minX : shape.minZ;
            double max = face.getAxis() == Direction.Axis.X ? shape.maxX : shape.maxZ;
            boolean positiveEdge = face == Direction.NORTH || face == Direction.WEST;
            helper.assertTrue(Math.abs(min - (positiveEdge ? 14.0 / 16.0 : 0.0)) < 0.0001
                    && Math.abs(max - (positiveEdge ? 1.0 : 2.0 / 16.0)) < 0.0001, "Thin board shape sits flush against its supporting face: " + face);
            helper.assertValueEqual(state.rotate(Rotation.CLOCKWISE_90).getValue(TrainingBoardBlock.FACING), face.getClockWise(), "Structure rotations preserve board facing");
            helper.assertValueEqual(state.mirror(Mirror.LEFT_RIGHT).getValue(TrainingBoardBlock.FACING),
                    face.getAxis() == Direction.Axis.Z ? face.getOpposite() : face, "Structure mirrors preserve board facing");
            helper.getLevel().setBlock(pos, state, Block.UPDATE_ALL);
            var drops = Block.getDrops(state, helper.getLevel(), pos, null);
            helper.assertTrue(drops.size() == 1 && drops.getFirst().is(ModContent.TRAINING_BOARD_ITEM.get())
                    && drops.getFirst().getCount() == 1, "Ordinary board loot is one reusable board");
        }
        for (Direction face : List.of(Direction.UP, Direction.DOWN)) {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), face, support, false);
            helper.assertTrue(block.getStateForPlacement(new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                    ModContent.TRAINING_BOARD_ITEM.get().getDefaultInstance(), hit)) == null, "Boards cannot stand on floors or hang from ceilings");
        }
        helper.getLevel().setBlock(support, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction face : Direction.Plane.HORIZONTAL) {
            helper.assertTrue(helper.getLevel().getBlockState(support.relative(face)).isAir(), "Removing support breaks the attached board");
        }
        helper.assertItemEntityCountIs(ModContent.TRAINING_BOARD_ITEM.get(), new BlockPos(8, 2, 8), 3.0, 4);
        helper.getLevel().setBlock(support, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        BlockHitResult fenceHit = new BlockHitResult(Vec3.atCenterOf(support), Direction.NORTH, support, false);
        helper.assertTrue(block.getStateForPlacement(new BlockPlaceContext(player, InteractionHand.MAIN_HAND,
                ModContent.TRAINING_BOARD_ITEM.get().getDefaultInstance(), fenceHit)) == null, "A fence has no full sturdy side for this wall board");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void trainingBoardRecipeAcceptsMixedPlanksAndYieldsOneBoard(GameTestHelper helper) {
        CraftingInput input = CraftingInput.of(3, 3, List.of(
                new ItemStack(Items.PAPER), new ItemStack(Items.PAPER), new ItemStack(Items.PAPER),
                new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.BIRCH_PLANKS), new ItemStack(Items.SPRUCE_PLANKS),
                ItemStack.EMPTY, new ItemStack(Items.STICK), ItemStack.EMPTY));
        var holder = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel()).orElseThrow();
        helper.assertValueEqual(holder.id(), ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "training_board"), "Survival recipe is registered");
        ItemStack crafted = holder.value().assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(crafted.is(ModContent.TRAINING_BOARD_ITEM.get()), "Paper, planks, and a stick craft a training board");
        helper.assertValueEqual(crafted.getCount(), 1, "One recipe produces one board");
        helper.assertTrue(holder.value().getRemainingItems(input).stream().allMatch(ItemStack::isEmpty), "The recipe consumes ordinary materials without unexpected remainders");
        helper.succeed();
    }

    private TrainingGameTests() {}
}
