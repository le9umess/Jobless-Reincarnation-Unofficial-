package dev.joblessreincarnation.magic;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingTrials;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real ray selection, vanilla healing hook and actual item-use lifecycle regressions. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HealingGameTests {
    private static final class Caster extends FakePlayer {
        Caster(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "HealTest")); }
        void tickUse() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }
    private static Caster caster(GameTestHelper helper) {
        Caster player = new Caster(helper.getLevel());
        player.setPos(helper.absoluteVec(new Vec3(8, 2, 4)));
        player.setYRot(0); player.setXRot(0);
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(HealingSpell.SPELL);
        player.getData(ModAttachments.CASTING).select(HealingSpell.SPELL);
        player.setData(ModAttachments.MANA, ManaData.full(100));
        player.setHealth(10);
        return player;
    }
    private static <T extends LivingEntity> T target(GameTestHelper helper, Caster player, EntityType<T> type, double distance) {
        T entity = type.create(helper.getLevel());
        entity.setPos(player.position().add(0, 0, distance));
        entity.setNoGravity(true);
        if (entity instanceof Mob mob) mob.setNoAi(true);
        entity.setHealth(Math.min(8, entity.getMaxHealth() - 1));
        helper.getLevel().addFreshEntity(entity);
        return entity;
    }
    private static void reset(Caster player) {
        player.stopUsingItem();
        player.setData(ModAttachments.MANA, ManaData.full(100));
        player.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
    }
    private static void hold(Caster player, int ticks) {
        ModContent.TRAINING_STAFF.get().use(player.level(), player, InteractionHand.MAIN_HAND);
        for (int tick = 0; tick < ticks; tick++) player.tickUse();
    }
    private static void free(GameTestHelper helper, Caster player, String label) {
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, label + ": no fee");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), 0, label + ": no cooldown");
        helper.assertFalse(player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), label + ": no item cooldown");
        helper.assertValueEqual(HealingSpell.practiceCount(player), 0, label + ": no healing practice");
    }
    private static void health(GameTestHelper helper, LivingEntity target, double expected, String label) {
        helper.assertTrue(Math.abs(target.getHealth() - expected) < 0.0001, label + ": expected " + expected + ", received " + target.getHealth());
    }
    private static void paid(GameTestHelper helper, Caster player, int practice, String label) {
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100 - MagicConfig.HEALING_COST.getAsInt(), label + ": fixed fee");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), MagicConfig.HEALING_COOLDOWN.getAsInt(), label + ": shared cooldown");
        helper.assertTrue(player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), label + ": item cooldown");
        helper.assertValueEqual(HealingSpell.practiceCount(player), practice, label + ": one practice per release");
    }

    @GameTest(template = "empty")
    public static void heldSelfHealingWaitsForReleaseAndSettlesOnceWithoutOtherProgress(GameTestHelper helper) {
        Caster player = caster(helper);
        player.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
        player.getData(ModAttachments.KNOWLEDGE).learn(EarthWallSpell.SPELL);
        player.getData(ModAttachments.KNOWLEDGE).learn(AirBurstSpell.SPELL);
        helper.assertTrue(HealingTarget.resolve(player).self(), "Empty aim explicitly selects self");
        hold(player, HealingSpell.chantTicks(player) + 100);
        helper.assertTrue(player.isUsingItem(), "Ready and long-held healing waits for release");
        health(helper, player, 10, "Holding alone never restores health");
        free(helper, player, "Holding");
        player.releaseUsingItem(); player.releaseUsingItem();
        health(helper, player, 10 + MagicConfig.HEALING_AMOUNT.get(), "One fixed recovery despite long hold and duplicate release");
        paid(helper, player, 1, "Self release");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "No water practice");
        helper.assertValueEqual(EarthWallSpell.practiceCount(player), 0, "No earth practice");
        helper.assertValueEqual(AirBurstSpell.practiceCount(player), 0, "No air practice");
        helper.assertValueEqual(player.getData(ModAttachments.TRAINING).targetHits(), 0, "No water board target hit");
        helper.assertFalse(TrainingTrials.completed(player), "Healing cannot complete water board");
        helper.assertFalse(WaterMagic.cast(player), "Water cannot bypass shared cooldown");
        helper.assertFalse(AirBurstSpell.cast(player), "Air cannot bypass shared cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void earlySneakSwitchAndReplacementCancelHealingForFree(GameTestHelper helper) {
        Caster player = caster(helper);
        hold(player, HealingSpell.chantTicks(player) - 1);
        player.releaseUsingItem(); free(helper, player, "Early release");
        hold(player, HealingSpell.chantTicks(player));
        player.setShiftKeyDown(true); player.releaseUsingItem(); player.setShiftKeyDown(false);
        free(helper, player, "Sneak release");
        player.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
        hold(player, HealingSpell.chantTicks(player));
        helper.assertTrue(SpellCasting.cycle(player), "G leaves ready healing");
        player.releaseUsingItem(); free(helper, player, "Spell switch");
        player.getData(ModAttachments.CASTING).select(HealingSpell.SPELL);
        hold(player, HealingSpell.chantTicks(player));
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.releaseUsingItem(); free(helper, player, "Replacement wand cannot inherit use lock");
        health(helper, player, 10, "All cancellations preserve health");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aimedLivingReceivesHealingInsteadOfInjuredCaster(GameTestHelper helper) {
        Caster player = caster(helper);
        Villager ally = target(helper, player, EntityType.VILLAGER, 2.0);
        HealingTarget.Plan plan = HealingTarget.resolve(player);
        helper.assertValueEqual(plan.target(), ally, "Ray selects the aimed body");
        helper.assertFalse(plan.self(), "Aim preview explicitly identifies the other target");
        helper.assertTrue(plan.valid(), "Injured ordinary living target eligible");
        hold(player, HealingSpell.chantTicks(player)); player.releaseUsingItem();
        health(helper, ally, 8 + MagicConfig.HEALING_AMOUNT.get(), "Actual held release heals target");
        health(helper, player, 10, "Caster is not also healed");
        paid(helper, player, 1, "Aimed release");
        helper.assertTrue(HealingTarget.eligible(player, caster(helper)), "Other players are eligible without invented team rule");
        helper.assertTrue(HealingTarget.eligible(player, EntityType.COW.create(helper.getLevel())), "Ordinary animals eligible");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void firstInvalidAndFullTargetsBlockHealingBehindAndSelfFallback(GameTestHelper helper) {
        Caster player = caster(helper);
        Villager back = target(helper, player, EntityType.VILLAGER, 3.0);
        var front = target(helper, player, EntityType.HUSK, 1.5);
        helper.assertValueEqual(HealingTarget.resolve(player).target(), front, "First pickable enemy is retained before eligibility filtering");
        helper.assertFalse(HealingSpell.cast(player), "Enemy target refuses healing");
        health(helper, front, 8, "No inverted potion damage to undead");
        health(helper, back, 8, "Cannot heal through invalid target");
        front.discard();
        Villager full = target(helper, player, EntityType.VILLAGER, 1.5);
        full.setHealth(full.getMaxHealth());
        helper.assertValueEqual(HealingTarget.resolve(player).reason(), HealingTarget.Reason.FULL_HEALTH, "Full first target is explicit");
        helper.assertFalse(HealingSpell.cast(player), "Full target refuses healing");
        health(helper, back, 8, "Cannot skip full target for injured target behind");
        health(helper, player, 10, "Invalid and full aimed targets do not fall back to injured self");
        free(helper, player, "Invalid or full aimed release");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void collidersBlockOthersWhileEmptyAimSelectsSelfAndGrassDoesNotBlock(GameTestHelper helper) {
        Caster player = caster(helper);
        Villager other = target(helper, player, EntityType.VILLAGER, 3.0);
        BlockPos obstacle = new BlockPos(8, 3, 6);
        helper.setBlock(obstacle, Blocks.STONE);
        helper.assertTrue(HealingTarget.resolve(player).self(), "Wall blocks entity ray and preview selects self");
        helper.assertTrue(HealingSpell.cast(player), "Explicit self aim can recover health beside a wall");
        health(helper, other, 8, "No healing through solid collision");
        health(helper, player, 10 + MagicConfig.HEALING_AMOUNT.get(), "Wall aim follows explicit self fallback");
        helper.assertTrue(helper.getBlockState(obstacle).is(Blocks.STONE), "Healing does not edit terrain");
        reset(player);
        helper.setBlock(obstacle, Blocks.SHORT_GRASS);
        helper.assertValueEqual(HealingTarget.resolve(player).target(), other, "Non-colliding decoration does not obstruct ray");
        helper.assertTrue(HealingSpell.cast(player), "Target beyond decoration can recover");
        health(helper, other, 8 + MagicConfig.HEALING_AMOUNT.get(), "Ordinary target recovery");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rayUsesSurfaceRangeAndRejectsUnloadedTraversalWithoutLoadingChunks(GameTestHelper helper) {
        Caster player = caster(helper);
        Villager other = target(helper, player, EntityType.VILLAGER, 3);
        double radius = other.getBbWidth() / 2.0 + other.getPickRadius();
        other.setPos(player.position().add(0, 0, MagicConfig.HEALING_RANGE.getAsInt() + radius + 0.02));
        helper.assertTrue(HealingTarget.resolve(player).self(), "Body entirely beyond range is not selected");
        other.setPos(player.position().add(0, 0, MagicConfig.HEALING_RANGE.getAsInt() + radius - 0.02));
        helper.assertValueEqual(HealingTarget.resolve(player).target(), other, "Body surface within range is selected even with center beyond range");
        other.setPos(player.position().add(1, 0, 2));
        helper.assertTrue(HealingTarget.resolve(player).self(), "No broad cone or nearby-target magnetism");
        Vec3 absent = new Vec3(10000000, 80, 10000000);
        player.setPos(absent);
        helper.assertFalse(helper.getLevel().hasChunkAt(BlockPos.containing(absent)), "Fixture chunk starts unloaded");
        helper.assertValueEqual(HealingTarget.resolve(player).reason(), HealingTarget.Reason.UNLOADED, "Unloaded ray is invalid rather than a self fallback");
        helper.assertFalse(HealingSpell.cast(player), "Unloaded ray cannot release");
        helper.assertFalse(helper.getLevel().hasChunkAt(BlockPos.containing(absent)), "Preview and release do not request chunks");
        free(helper, player, "Unloaded release");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void releaseRecomputesAimAndDoesNotRetainTheBeginningTarget(GameTestHelper helper) {
        Caster player = caster(helper);
        Villager first = target(helper, player, EntityType.VILLAGER, 2);
        Villager second = target(helper, player, EntityType.VILLAGER, 3);
        helper.assertValueEqual(HealingTarget.resolve(player).target(), first, "First body is original aim");
        hold(player, HealingSpell.chantTicks(player));
        first.setPos(player.position().add(2, 0, 2));
        player.releaseUsingItem();
        health(helper, first, 8, "Moved-out original target is not latched");
        health(helper, second, 8 + MagicConfig.HEALING_AMOUNT.get(), "Release uses current line of sight");
        health(helper, player, 10, "Current aimed target prevents self fallback");
        paid(helper, player, 1, "Recomputed release");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void deadArmorStandBossAndUndeadTargetsAreNeverHealedOrDamaged(GameTestHelper helper) {
        Caster player = caster(helper);
        var stand = target(helper, player, EntityType.ARMOR_STAND, 2);
        helper.assertValueEqual(HealingTarget.resolve(player).target(), stand, "Pickable armor stand blocks aim");
        helper.assertFalse(HealingSpell.cast(player), "Armor stand cannot be healed");
        float standHealth = stand.getHealth();
        stand.discard();
        Villager dead = target(helper, player, EntityType.VILLAGER, 2);
        dead.setHealth(0);
        helper.assertValueEqual(HealingTarget.resolve(player).target(), dead, "Dying pickable body keeps target intent");
        helper.assertFalse(HealingSpell.cast(player), "No resurrection");
        health(helper, dead, 0, "Dead body remains dead");
        helper.assertTrue(EntityType.ZOMBIE.is(HealingTarget.IMMUNE), "Default healing immunity incorporates undead tag");
        helper.assertTrue(EntityType.WITHER.is(HealingTarget.IMMUNE), "Wither immune");
        helper.assertTrue(EntityType.ENDER_DRAGON.is(HealingTarget.IMMUNE), "Dragon immune");
        helper.assertFalse(HealingTarget.eligible(player, EntityType.SLIME.create(helper.getLevel())), "Non-undead Enemy also excluded");
        helper.assertFalse(HealingTarget.eligible(player, EntityType.ZOMBIE.create(helper.getLevel())), "Inverted healing and undead excluded");
        health(helper, stand, standHealth, "No damage to decorative target");
        free(helper, player, "Excluded target release");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullSelfIsFreeAndOverhealClampsWithoutCleansingOrFeeding(GameTestHelper helper) {
        Caster player = caster(helper);
        player.setHealth(player.getMaxHealth());
        helper.assertFalse(HealingSpell.cast(player), "Full self is not a practice source");
        free(helper, player, "Full self");
        player.setHealth(player.getMaxHealth() - 1);
        player.getFoodData().setFoodLevel(7);
        player.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        player.setRemainingFireTicks(100);
        player.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(4);
        player.setAbsorptionAmount(3);
        helper.assertTrue(HealingSpell.cast(player), "A small injury is enough to release");
        health(helper, player, player.getMaxHealth(), "Vanilla clamps excess recovery");
        helper.assertValueEqual(player.getFoodData().getFoodLevel(), 7, "Hunger unchanged");
        helper.assertTrue(player.hasEffect(MobEffects.POISON), "Poison is not detoxified");
        helper.assertValueEqual(player.getEffect(MobEffects.POISON).getDuration(), 200, "Poison duration unchanged");
        helper.assertValueEqual(player.getRemainingFireTicks(), 100, "Burning is not extinguished");
        helper.assertValueEqual(player.getAbsorptionAmount(), 3.0F, "Absorption unchanged");
        paid(helper, player, 1, "Overheal release");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void healingHooksCanCancelOrReduceButCannotReenterOrRefundRelease(GameTestHelper helper) {
        Caster player = caster(helper);
        AtomicInteger called = new AtomicInteger();
        Consumer<LivingHealEvent> cancel = event -> {
            if (event.getEntity() != player) return;
            called.incrementAndGet();
            helper.assertFalse(HealingSpell.cast(player), "Paid shared/item cooldowns reject hook reentry");
            event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(LivingHealEvent.class, cancel);
        try { helper.assertTrue(HealingSpell.cast(player), "Cancelled recovery remains a released spell"); }
        finally { NeoForge.EVENT_BUS.unregister(cancel); }
        health(helper, player, 10, "Cancelled heal does not change health");
        helper.assertValueEqual(called.get(), 1, "Hook called once without recursion");
        paid(helper, player, 1, "Cancelled recovery");
        reset(player);
        Consumer<LivingHealEvent> reduce = event -> { if (event.getEntity() == player) event.setAmount(1); };
        NeoForge.EVENT_BUS.addListener(LivingHealEvent.class, reduce);
        try { helper.assertTrue(HealingSpell.cast(player), "Reduced recovery remains a release"); }
        finally { NeoForge.EVENT_BUS.unregister(reduce); }
        health(helper, player, 11, "Vanilla applies hook-modified amount");
        paid(helper, player, 2, "Reduced recovery");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void knowledgeStaffManaAndCooldownGuardsRejectWithoutRecovery(GameTestHelper helper) {
        Caster player = caster(helper);
        player.setData(ModAttachments.KNOWLEDGE, new MagicKnowledge());
        helper.assertFalse(HealingSpell.cast(player), "Unknown healing cannot cast");
        player.getData(ModAttachments.KNOWLEDGE).learn(HealingSpell.SPELL);
        player.setItemInHand(InteractionHand.MAIN_HAND, Items.STICK.getDefaultInstance());
        helper.assertFalse(HealingSpell.cast(player), "Ordinary stick cannot cast");
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        free(helper, player, "Knowledge and item guards");
        int lowMana = MagicConfig.HEALING_COST.getAsInt() - 1;
        player.setData(ModAttachments.MANA, new ManaData(lowMana, 100, 0, 0));
        helper.assertFalse(HealingSpell.cast(player), "Insufficient mana fails without partial healing");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), lowMana, "Low mana is not partly spent");
        reset(player);
        player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), 10);
        helper.assertFalse(HealingSpell.cast(player), "Item-only cooldown guards service entry");
        reset(player);
        player.setData(ModAttachments.MANA, new ManaData(100, 100, 0, 10));
        helper.assertFalse(HealingSpell.cast(player), "Shared-only cooldown guards service entry");
        health(helper, player, 10, "All invalid resource paths preserve health");
        helper.assertValueEqual(HealingSpell.practiceCount(player), 0, "Rejected guards grant no practice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeStillNeedsLearningChantAndCooldownButPaysNoManaOrPractice(GameTestHelper helper) {
        Caster player = caster(helper);
        player.getAbilities().instabuild = true;
        player.setData(ModAttachments.KNOWLEDGE, new MagicKnowledge());
        helper.assertFalse(HealingSpell.cast(player), "Creative does not grant spell knowledge");
        player.getData(ModAttachments.KNOWLEDGE).learn(HealingSpell.SPELL);
        hold(player, HealingSpell.chantTicks(player) - 1); player.releaseUsingItem();
        health(helper, player, 10, "Creative still requires full chant");
        hold(player, HealingSpell.chantTicks(player)); player.releaseUsingItem();
        health(helper, player, 10 + MagicConfig.HEALING_AMOUNT.get(), "Creative actual release heals");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "Creative pays no mana");
        helper.assertValueEqual(HealingSpell.practiceCount(player), 0, "Creative earns no practice");
        helper.assertTrue(player.getData(ModAttachments.MANA).cooldown() > 0, "Creative keeps shared cooldown");
        helper.assertFalse(HealingSpell.cast(player), "Creative cannot reenter during cooldown");
        helper.succeed();
    }
    private HealingGameTests() {}
}

