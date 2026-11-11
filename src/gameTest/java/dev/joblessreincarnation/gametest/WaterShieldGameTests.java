package dev.joblessreincarnation.magic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Water Shield on a real server level: release and payment, ground, replacement, lifetime, and which projectiles the
 * screen stops at real speeds. The "empty" template's stone floor is the relative y 1 layer, so feet stand at y 2.
 * A caster at z 3.5 facing +Z (yaw 0) raises its screen at z 5.5, spanning x 7..10 and y 2..4.25.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WaterShieldGameTests {
    private static final Vec3 CASTER = new Vec3(8.5, 2, 3.5);
    private static final ResourceLocation RECIPE = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "water_shield_tome");

    private static final class Caster extends FakePlayer {
        Caster(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "ShieldTest")); }
        void tickUse() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }

    private static Caster caster(GameTestHelper helper, Vec3 relative) {
        return caster(helper, relative, true);
    }

    private static Caster caster(GameTestHelper helper, Vec3 relative, boolean learned) {
        Caster player = new Caster(helper.getLevel());
        player.setPos(helper.absoluteVec(relative));
        player.setYRot(0); player.setXRot(0);
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        if (learned) {
            player.getData(ModAttachments.KNOWLEDGE).learn(WaterShieldSpell.SPELL);
            player.getData(ModAttachments.CASTING).select(WaterShieldSpell.SPELL);
        }
        player.setData(ModAttachments.MANA, ManaData.full(100));
        return player;
    }

    private static void ready(Caster player) {
        player.setData(ModAttachments.MANA, ManaData.full(100));
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
    }

    private static List<WaterShieldEntity> shields(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(WaterShieldEntity.class, helper.getBounds().inflate(2));
    }

    private static WaterShieldEntity onlyShield(GameTestHelper helper) {
        List<WaterShieldEntity> all = shields(helper);
        helper.assertValueEqual(all.size(), 1, "Exactly one water shield");
        return all.getFirst();
    }

    private static Husk husk(GameTestHelper helper, Vec3 relative) {
        Husk mob = EntityType.HUSK.create(helper.getLevel());
        // Other GameTests have real players hundreds of blocks away. Keep these stationary targets
        // alive until their incoming projectiles arrive, even when their own caster is a FakePlayer.
        mob.setPersistenceRequired();
        mob.setPos(helper.absoluteVec(relative));
        mob.setNoAi(true);
        mob.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(mob);
        return mob;
    }

    private static <T extends AbstractArrow> T launch(GameTestHelper helper, T arrow, Vec3 relative, Vec3 motion, @Nullable Entity shooter) {
        Vec3 at = helper.absoluteVec(relative);
        arrow.setPos(at.x, at.y, at.z);
        if (shooter != null) arrow.setOwner(shooter);
        arrow.setDeltaMovement(motion);
        helper.getLevel().addFreshEntity(arrow);
        return arrow;
    }

    private static Arrow arrow(GameTestHelper helper, Vec3 relative, Vec3 motion, @Nullable Entity shooter) {
        return launch(helper, new Arrow(helper.getLevel(), 0, 0, 0, new ItemStack(Items.ARROW), null), relative, motion, shooter);
    }

    private static void unharmed(GameTestHelper helper, Husk target, String reason) {
        helper.assertValueEqual(target.getHealth(), target.getMaxHealth(), reason);
    }

    @GameTest(template = "empty")
    public static void releaseFormsOneScreenAheadAndSettlesOnce(GameTestHelper helper) {
        Caster player = caster(helper, CASTER);
        helper.assertTrue(WaterShieldSpell.cast(player), "A shield forms on the floor ahead");
        WaterShieldEntity shield = onlyShield(helper);
        Vec3 expected = helper.absoluteVec(new Vec3(8.5, 2, 5.5));
        helper.assertTrue(shield.position().distanceTo(expected) < 1.0E-6, "Base stands two blocks ahead on the floor: " + shield.position());
        helper.assertValueEqual(shield.shieldYaw(), 0.0F, "Faces the caster's look");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100 - MagicConfig.WATER_SHIELD_COST.getAsInt(), "One fixed fee");
        helper.assertValueEqual(WaterShieldSpell.practiceCount(player), 1, "One shield practice");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), MagicConfig.WATER_SHIELD_COOLDOWN.getAsInt(), "Shared mana cooldown");
        helper.assertTrue(player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Shared wand cooldown");
        helper.assertFalse(WaterShieldSpell.cast(player), "Same-tick second release fails");
        helper.assertValueEqual(shields(helper).size(), 1, "No second screen");
        helper.assertValueEqual(WaterShieldSpell.practiceCount(player), 1, "No duplicate settlement");
        helper.assertFalse(shield.shouldBeSaved(), "Never saved to the world");
        helper.assertFalse(shield.isPickable() || shield.canBeCollidedWith(), "Cannot be targeted or collided with");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeReleaseIsFreeOfPracticeAndUnknownSpellFails(GameTestHelper helper) {
        Caster creative = caster(helper, CASTER);
        creative.getAbilities().instabuild = true;
        helper.assertTrue(WaterShieldSpell.cast(creative), "Creative release forms a shield");
        helper.assertValueEqual(WaterShieldSpell.practiceCount(creative), 0, "Creative casts never train");
        Caster stranger = caster(helper, new Vec3(3.5, 2, 3.5), false);
        helper.assertFalse(WaterShieldSpell.cast(stranger), "Not learned, no shield");
        helper.assertValueEqual(stranger.getData(ModAttachments.MANA).mana(), 100, "Not learned costs nothing");
        helper.assertValueEqual(shields(helper).size(), 1, "Only the creative caster's shield exists");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void waterAheadFailsWithoutCostOrPractice(GameTestHelper helper) {
        helper.setBlock(new BlockPos(8, 2, 5), Blocks.WATER);
        Caster player = caster(helper, CASTER);
        helper.assertFalse(WaterShieldSpell.cast(player), "No dry ground ahead");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "Failed release costs nothing");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), 0, "Failed release starts no cooldown");
        helper.assertValueEqual(WaterShieldSpell.practiceCount(player), 0, "Failed release does not train");
        helper.assertValueEqual(shields(helper).size(), 0, "No shield");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oneStepUpOrDownStillFindsTheGround(GameTestHelper helper) {
        helper.setBlock(new BlockPos(8, 2, 5), Blocks.STONE);
        Caster low = caster(helper, CASTER);
        helper.assertTrue(WaterShieldSpell.cast(low), "A block ahead: the screen stands on top of it");
        helper.assertValueEqual(onlyShield(helper).getY(), helper.absoluteVec(new Vec3(0, 3, 0)).y, "One step up");
        onlyShield(helper).discard();
        helper.setBlock(new BlockPos(8, 2, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.STONE);
        Caster high = caster(helper, new Vec3(3.5, 3, 3.5));
        helper.assertTrue(WaterShieldSpell.cast(high), "Standing on a block: the screen stands on the floor below");
        helper.assertValueEqual(onlyShield(helper).getY(), helper.absoluteVec(new Vec3(0, 2, 0)).y, "One step down");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyAPaidReleaseReplacesTheCastersOwnScreen(GameTestHelper helper) {
        Caster first = caster(helper, CASTER);
        Caster second = caster(helper, new Vec3(3.5, 2, 3.5));
        helper.assertTrue(WaterShieldSpell.cast(first), "First caster's shield");
        helper.assertTrue(WaterShieldSpell.cast(second), "Second caster's shield");
        WaterShieldEntity secondShield = shields(helper).stream().filter(s -> second.getUUID().equals(s.ownerId())).findFirst().orElseThrow();
        ready(first);
        helper.assertTrue(WaterShieldSpell.cast(first), "Recast");
        List<WaterShieldEntity> mine = shields(helper).stream().filter(s -> first.getUUID().equals(s.ownerId())).toList();
        helper.assertValueEqual(mine.size(), 1, "Recast leaves one screen for that caster");
        helper.assertFalse(secondShield.isRemoved(), "Another caster's screen stays");
        WaterShieldEntity kept = mine.getFirst();
        first.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
        first.setData(ModAttachments.MANA, new ManaData(5, 100, 0, 0));
        helper.assertFalse(WaterShieldSpell.cast(first), "Too little mana");
        helper.assertFalse(kept.isRemoved(), "A failed release keeps the earlier screen");
        helper.assertValueEqual(first.getData(ModAttachments.MANA).mana(), 5, "Failed release costs nothing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void screenEndsAfterItsLifetime(GameTestHelper helper) {
        Caster player = caster(helper, CASTER);
        helper.assertTrue(WaterShieldSpell.cast(player), "Shield forms");
        helper.startSequence()
                .thenExecuteAfter(WaterShieldEntity.LIFETIME_TICKS - 5, () -> helper.assertValueEqual(shields(helper).size(), 1, "Still standing just before the end"))
                .thenExecuteAfter(10, () -> helper.assertValueEqual(shields(helper).size(), 0, "Gone after its lifetime"))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void casterDeathEndsTheScreen(GameTestHelper helper) {
        Caster player = caster(helper, CASTER);
        helper.assertTrue(WaterShieldSpell.cast(player), "Shield forms");
        player.setHealth(0.0F);
        helper.startSequence()
                .thenExecuteAfter(2, () -> helper.assertValueEqual(shields(helper).size(), 0, "A dead caster's screen ends"))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void arrowsAtRealSpeedsAreStoppedAcrossTheWholeWidth(GameTestHelper helper) {
        Caster player = caster(helper, CASTER);
        helper.assertTrue(WaterShieldSpell.cast(player), "Shield forms");
        // Full bow, skeleton and slow arrows, centre and both edges; each lane has its own target behind the screen.
        // Each start is close enough that the arrow reaches the screen before gravity brings it down to the floor.
        double[] lanes = {7.1, 8.5, 9.9};
        double[] speeds = {3.0, 1.6, 0.6};
        double[] starts = {13.5, 13.5, 7.0};
        Husk[] targets = new Husk[lanes.length];
        Arrow[] shots = new Arrow[lanes.length];
        for (int lane = 0; lane < lanes.length; lane++) {
            targets[lane] = husk(helper, new Vec3(lanes[lane], 2, 4.2));
            shots[lane] = arrow(helper, new Vec3(lanes[lane], 3.0, starts[lane]), new Vec3(0, 0.02, -speeds[lane]), null);
        }
        helper.startSequence()
                .thenExecuteAfter(40, () -> {
                    for (int lane = 0; lane < lanes.length; lane++) {
                        helper.assertTrue(shots[lane].isRemoved(), "The arrow at speed " + speeds[lane] + " is absorbed");
                        unharmed(helper, targets[lane], "The target behind lane " + lane + " is covered");
                    }
                    helper.assertEntityNotPresent(EntityType.ARROW);
                })
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void arrowAboutToHitRightBehindTheScreenIsStopped(GameTestHelper helper) {
        Caster player = caster(helper, CASTER);
        helper.assertTrue(WaterShieldSpell.cast(player), "Shield forms");
        // The target almost touches the back of the screen and the arrow covers the gap in a single tick, so it would
        // hit in its own tick before the screen ticks unless the impact itself is checked.
        Husk target = husk(helper, new Vec3(8.5, 2, 5.0));
        arrow(helper, new Vec3(8.5, 3.0, 7.4), new Vec3(0, 0, -3.0), null);
        helper.startSequence()
                .thenExecuteAfter(10, () -> unharmed(helper, target, "The impact behind the screen never happens"))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void spectralAndTippedArrowsAreStoppedToo(GameTestHelper helper) {
        Caster player = caster(helper, CASTER);
        helper.assertTrue(WaterShieldSpell.cast(player), "Shield forms");
        Husk left = husk(helper, new Vec3(7.5, 2, 4.2));
        Husk right = husk(helper, new Vec3(9.5, 2, 4.2));
        launch(helper, new SpectralArrow(helper.getLevel(), 0, 0, 0, new ItemStack(Items.SPECTRAL_ARROW), null),
                new Vec3(7.5, 3.0, 12.5), new Vec3(0, 0.02, -2.0), null);
        launch(helper, new Arrow(helper.getLevel(), 0, 0, 0, PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.POISON), null),
                new Vec3(9.5, 3.0, 12.5), new Vec3(0, 0.02, -2.0), null);
        helper.startSequence()
                .thenExecuteAfter(30, () -> {
                    unharmed(helper, left, "Spectral arrow stopped");
                    unharmed(helper, right, "Tipped arrow stopped");
                    helper.assertFalse(left.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING), "No glowing from a stopped spectral arrow");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void tridentsAndTheCastersOwnArrowsPassThrough(GameTestHelper helper) {
        Caster player = caster(helper, CASTER);
        helper.assertTrue(WaterShieldSpell.cast(player), "Shield forms");
        Husk behind = husk(helper, new Vec3(7.5, 2, 4.0));
        Husk ahead = husk(helper, new Vec3(9.5, 2, 9.0));
        launch(helper, new ThrownTrident(helper.getLevel(), 0, 0, 0, new ItemStack(Items.TRIDENT)),
                new Vec3(7.5, 3.0, 12.5), new Vec3(0, 0.02, -2.5), null);
        arrow(helper, new Vec3(9.5, 3.0, 3.8), new Vec3(0, 0.02, 2.0), player);
        helper.startSequence()
                .thenExecuteAfter(30, () -> {
                    helper.assertTrue(behind.getHealth() < behind.getMaxHealth(), "A trident is not an arrow and passes");
                    helper.assertTrue(ahead.getHealth() < ahead.getMaxHealth(), "The caster can shoot out through their own screen");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void arrowsBesideOrAboveTheScreenAreUntouched(GameTestHelper helper) {
        Arrow[] arrows = new Arrow[2];
        double[] screenZ = new double[1];
        helper.startSequence()
                .thenExecute(() -> {
                    Caster player = caster(helper, CASTER);
                    helper.assertTrue(WaterShieldSpell.cast(player), "Shield forms");
                    // Two independent lanes cross the screen plane before either arrow reaches the floor.
                    // Verify the crossing itself; a slow arrow that is still approaching the plane proves nothing.
                    arrows[0] = arrow(helper, new Vec3(12.5, 3.0, 7.5), new Vec3(0, 0.0, -1.0), null);
                    arrows[1] = arrow(helper, new Vec3(8.5, 6.5, 8.5), new Vec3(0, -0.35, -1.0), null);
                    screenZ[0] = onlyShield(helper).getZ();
                })
                // A stopped arrow is removed and never gets here.
                .thenWaitUntil(() -> {
                    helper.assertTrue(!arrows[0].isRemoved() && arrows[0].getZ() < screenZ[0],
                            "The side arrow must cross the plane alive: " + arrows[0].position());
                    helper.assertTrue(!arrows[1].isRemoved() && arrows[1].getZ() < screenZ[0],
                            "The high arrow must cross the plane alive: " + arrows[1].position());
                })
                .thenSucceed();
    }

    /** Before its first tick a new arrow's previous position is still zero. That is not a path it has flown, wherever the line from there runs. */
    @GameTest(template = "empty")
    public static void aNewArrowHasFlownNoPathYet(GameTestHelper helper) {
        helper.assertTrue(WaterShieldSpell.cast(caster(helper, CASTER)), "Shield forms");
        WaterShieldEntity shield = onlyShield(helper);
        Vec3 middle = shield.position().add(0.0, WaterShieldEntity.HEIGHT * 0.5, 0.0);
        // Beside the screen and not at it yet, its unset previous position standing on the far side: the line between them runs through the screen's middle.
        Arrow arrow = arrow(helper, new Vec3(12.5, 3.0, 7.5), new Vec3(0, 0, -1.0), null);
        Vec3 unset = middle.add(middle.subtract(arrow.position()));
        arrow.xo = unset.x;
        arrow.yo = unset.y;
        arrow.zo = unset.z;
        helper.assertTrue(shield.crossing(unset, arrow.position()) != null, "That line does cross the screen");
        helper.startSequence()
                .thenExecuteAfter(3, () -> helper.assertFalse(arrow.isRemoved(), "An arrow that has not flown through the screen is not stopped"))
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void anArrowStuckInTheGroundUnderTheScreenStays(GameTestHelper helper) {
        AbstractArrow stuck = arrow(helper, new Vec3(8.5, 2.6, 5.5), new Vec3(0.0, -0.6, 0.01), null);
        helper.startSequence()
                .thenExecuteAfter(15, () -> {
                    helper.assertFalse(stuck.isRemoved(), "Arrow landed");
                    helper.assertFalse(WaterShieldEntity.isFlying(stuck), "A landed arrow no longer moves");
                    helper.assertTrue(WaterShieldSpell.cast(caster(helper, CASTER)), "Shield forms over the landed arrow");
                })
                .thenExecuteAfter(15, () -> helper.assertFalse(stuck.isRemoved(), "Only flying arrows are stopped"))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void crossingFindsFastPathsAndRejectsMisses(GameTestHelper helper) {
        Caster player = caster(helper, CASTER);
        helper.assertTrue(WaterShieldSpell.cast(player), "Shield forms");
        WaterShieldEntity shield = onlyShield(helper);
        Vec3 c = shield.position().add(0, 1.0, 0);
        helper.assertTrue(shield.crossing(c.add(0, 0, 3), c.add(0, 0, -3)) != null, "A six-block step straight through is caught");
        helper.assertTrue(shield.crossing(c.add(0, 0, 0.05), c.add(0, 0, 0.06)) != null, "A short step inside the water is caught");
        helper.assertTrue(shield.crossing(c.add(1.6, 0, 3), c.add(1.6, 0, -3)) != null, "The edge margin still counts");
        helper.assertTrue(shield.crossing(c.add(2.0, 0, 3), c.add(2.0, 0, -3)) == null, "Beside the screen misses");
        helper.assertTrue(shield.crossing(c.add(0, 2.5, 3), c.add(0, 2.5, -3)) == null, "Above the screen misses");
        helper.assertTrue(shield.crossing(c.add(0, 0, 3), c.add(0, 0, 1)) == null, "Stopping short misses");
        helper.assertTrue(shield.crossing(c.add(-3, 0, 2), c.add(3, 0, 2)) == null, "Flying alongside in front misses");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theTomeTeachesTheSpellOnce(GameTestHelper helper) {
        Caster reader = new Caster(helper.getLevel());
        reader.setPos(helper.absoluteVec(CASTER));
        reader.setItemInHand(InteractionHand.MAIN_HAND, ModContent.WATER_SHIELD_TOME.get().getDefaultInstance());
        ModContent.WATER_SHIELD_TOME.get().use(reader.level(), reader, InteractionHand.MAIN_HAND);
        helper.assertTrue(reader.isUsingItem(), "Study begins");
        for (int tick = 0; tick < MagicConfig.STUDY_TICKS.getAsInt() + 2 && reader.isUsingItem(); tick++) reader.tickUse();
        helper.assertTrue(WaterShieldSpell.isLearned(reader), "Study teaches Water Shield");
        helper.assertValueEqual(WaterShieldSpell.practiceCount(reader), 0, "Learning is not practice");
        ModContent.WATER_SHIELD_TOME.get().use(reader.level(), reader, InteractionHand.MAIN_HAND);
        helper.assertFalse(reader.isUsingItem(), "A known tome does not start a second study");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void loadedRecipeIsShapelessBookAmethystAndShield(GameTestHelper helper) {
        Item[] materials = {Items.BOOK, Items.AMETHYST_SHARD, Items.SHIELD};
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] order : orders) {
            List<ItemStack> grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
            grid.set(0, new ItemStack(materials[order[0]]));
            grid.set(4, new ItemStack(materials[order[1]]));
            grid.set(8, new ItemStack(materials[order[2]]));
            CraftingInput input = CraftingInput.of(3, 3, grid);
            var holder = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel()).orElseThrow();
            helper.assertValueEqual(holder.id(), RECIPE, "Every ingredient order with gaps matches the loaded recipe");
            ItemStack result = holder.value().assemble(input, helper.getLevel().registryAccess());
            helper.assertTrue(result.is(ModContent.WATER_SHIELD_TOME.get()) && result.getCount() == 1, "Crafting gives exactly one tome");
            helper.assertTrue(holder.value().getRemainingItems(input).stream().allMatch(ItemStack::isEmpty), "No ingredient leaves a remainder");
        }
        ItemStack worn = new ItemStack(Items.SHIELD);
        worn.setDamageValue(200);
        CraftingInput used = CraftingInput.of(3, 1, List.of(new ItemStack(Items.BOOK), new ItemStack(Items.AMETHYST_SHARD), worn));
        helper.assertTrue(helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, used, helper.getLevel())
                .map(holder -> holder.id().equals(RECIPE)).orElse(false), "A worn shield counts as well");
        CraftingInput wrong = CraftingInput.of(3, 1, List.of(new ItemStack(Items.BOOK), new ItemStack(Items.AMETHYST_SHARD), new ItemStack(Items.PRISMARINE_SHARD)));
        var wrongMatch = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, wrong, helper.getLevel());
        helper.assertTrue(wrongMatch.isEmpty() || !wrongMatch.get().id().equals(RECIPE), "Another item cannot replace the shield");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void loadedAdvancementUnlocksTheRecipeWithEitherCriterion(GameTestHelper helper) {
        var holder = helper.getLevel().getServer().getAdvancements().get(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "recipes/water_shield_tome"));
        helper.assertTrue(holder != null, "The recipe advancement survives datapack decoding");
        var advancement = holder.value();
        helper.assertTrue(advancement.requirements().test("has_shield"::equals), "Having a shield alone unlocks the recipe");
        helper.assertTrue(advancement.requirements().test("has_the_recipe"::equals), "Already knowing the recipe alone satisfies it");
        helper.assertFalse(advancement.requirements().test(name -> false), "Unknown criteria cannot complete it");
        helper.assertTrue(advancement.rewards().recipes().contains(RECIPE), "The reward unlocks the actual recipe");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void packagedModelAndEveryVisibleTextExist(GameTestHelper helper) {
        JsonObject model = resource("/assets/jobless_reincarnation/models/item/water_shield_tome.json");
        helper.assertTrue(model.has("textures") && !model.getAsJsonObject("textures").isEmpty(), "The tome model binds its materials");
        helper.assertTrue(model.has("elements") && !model.getAsJsonArray("elements").isEmpty(), "The tome model provides geometry");
        String tutor = "message.jobless_reincarnation.basic_tutor.water_shield.";
        List<String> keys = new ArrayList<>(List.of("item.jobless_reincarnation.water_shield_tome", "entity.jobless_reincarnation.water_shield",
                "message.jobless_reincarnation.learned_water_shield", "message.jobless_reincarnation.water_shield.learn_first",
                "message.jobless_reincarnation.water_shield.already_learned", "message.jobless_reincarnation.water_shield.ground",
                "tooltip.jobless_reincarnation.water_shield_tome.use", "tooltip.jobless_reincarnation.water_shield_tome.shield",
                "tooltip.jobless_reincarnation.water_shield_tome.tutor", tutor + "speaker", tutor + "dialogue.learn", tutor + "dialogue.novice",
                tutor + "dialogue.practiced", tutor + "dialogue.adept", tutor + "rule.learn", tutor + "rule.practice"));
        // The journal builds these keys from each spell's id, so every spell, this one included, needs all three.
        for (SpellCatalog spell : SpellCatalog.values()) {
            keys.add("spell.jobless_reincarnation." + spell.id().getPath());
            keys.add("screen.jobless_reincarnation.journal.purpose." + spell.id().getPath());
            keys.add("screen.jobless_reincarnation.journal.detail." + spell.id().getPath());
        }
        for (String language : List.of("zh_cn", "en_us")) {
            JsonObject json = resource("/assets/jobless_reincarnation/lang/" + language + ".json");
            for (String key : keys)
                helper.assertTrue(json.has(key) && !json.get(key).getAsString().isBlank(), "Localized visible text exists: " + language + "/" + key);
        }
        helper.succeed();
    }

    private static JsonObject resource(String path) {
        var stream = WaterShieldGameTests.class.getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Missing packaged resource: " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) { throw new IllegalStateException("Cannot read packaged resource: " + path, failure); }
    }
}
