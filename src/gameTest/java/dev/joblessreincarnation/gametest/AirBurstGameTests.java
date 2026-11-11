package dev.joblessreincarnation.magic;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.TrainingTrials;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real server targeting/payment regressions; no fake motion packets or replacement effect implementation. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AirBurstGameTests {
    private static final class Caster extends FakePlayer {
        Caster(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "AirTest")); }
        void tickUse() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }
    private static final class OwnedZombie extends Zombie implements OwnableEntity {
        private final UUID owner = UUID.randomUUID();
        OwnedZombie(ServerLevel level) { super(EntityType.ZOMBIE, level); }
        @Override public UUID getOwnerUUID() { return owner; }
    }
    private static Caster caster(GameTestHelper helper) {
        Caster player = new Caster(helper.getLevel());
        player.setPos(helper.absoluteVec(new Vec3(8, 2, 4)));
        player.setYRot(0); player.setXRot(0);
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(AirBurstSpell.SPELL);
        player.getData(ModAttachments.CASTING).select(AirBurstSpell.SPELL);
        player.setData(ModAttachments.MANA, ManaData.full(100));
        return player;
    }
    private static Husk husk(GameTestHelper helper, Caster player, double x, double y, double z) {
        Husk mob = EntityType.HUSK.create(helper.getLevel());
        mob.setPos(player.position().add(x, y, z));
        mob.setNoAi(true); mob.setNoGravity(true); mob.setOnGround(true);
        mob.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(mob);
        return mob;
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
    private static void free(GameTestHelper helper, Caster player, String reason) {
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, reason + ": no mana spent");
        helper.assertValueEqual(AirBurstSpell.practiceCount(player), 0, reason + ": no air practice");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), 0, reason + ": no shared cooldown");
    }
    private static void stationary(GameTestHelper helper, Mob mob, String reason) {
        helper.assertTrue(mob.getDeltaMovement().lengthSqr() < 1.0E-12, reason);
    }

    @GameTest(template = "empty")
    public static void emptyPulseSettlesOnceAndSharesCooldownWithoutWaterProgress(GameTestHelper helper) {
        Caster player = caster(helper);
        player.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
        player.getData(ModAttachments.KNOWLEDGE).learn(EarthWallSpell.SPELL);
        helper.assertTrue(AirBurstSpell.cast(player), "An empty pulse is a successful released spell");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100 - MagicConfig.AIR_BURST_COST.getAsInt(), "One fixed fee");
        helper.assertValueEqual(AirBurstSpell.practiceCount(player), 1, "One air practice");
        helper.assertValueEqual(WaterMagic.practiceCount(player), 0, "No water practice");
        helper.assertValueEqual(EarthWallSpell.practiceCount(player), 0, "No earth practice");
        helper.assertValueEqual(player.getData(ModAttachments.TRAINING).targetHits(), 0, "No water target hit");
        helper.assertFalse(player.getData(ModAttachments.TRAINING).chargedTargetHit(), "No charged water hit");
        helper.assertFalse(TrainingTrials.completed(player), "Air cannot complete water board");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).cooldown(), MagicConfig.AIR_BURST_COOLDOWN.getAsInt(), "Shared cooldown applied");
        helper.assertTrue(player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get()), "Item cooldown also applied");
        helper.assertFalse(AirBurstSpell.cast(player), "Same-tick second release fails");
        helper.assertFalse(WaterMagic.cast(player), "Water shares the cooldown");
        helper.assertFalse(EarthWallSpell.canBeginCast(player), "Earth shares the cooldown");
        helper.assertValueEqual(AirBurstSpell.practiceCount(player), 1, "No duplicate settlement");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void coneRangeAndVerticalBoundariesPushOnlyForwardLivingCenters(GameTestHelper helper) {
        Caster player = caster(helper);
        Husk front = husk(helper, player, 0, 0, 3);
        Husk inner = husk(helper, player, 1.3, 0, 3);
        Husk outer = husk(helper, player, -2.0, 0, 3);
        Husk behind = husk(helper, player, 0, 0, -2);
        Husk beyond = husk(helper, player, 0, 0, MagicConfig.AIR_BURST_RANGE.getAsInt() + 0.5);
        Husk above = husk(helper, player, 0, 3, 0);
        Husk high = husk(helper, player, 0, 3, 2);
        float health = front.getHealth();
        helper.setBlock(new BlockPos(8, 1, 7), Blocks.STONE);
        helper.assertTrue(AirBurstSpell.cast(player), "Forward pulse releases");
        helper.assertTrue(front.getDeltaMovement().z > 0.0 && inner.getDeltaMovement().x > 0.0 && inner.getDeltaMovement().z > 0.0, "Targets move away from caster");
        for (Husk denied : List.of(outer, behind, beyond, above, high)) stationary(helper, denied, "Outside cone/range or pure vertical target stays still");
        helper.assertValueEqual(front.getHealth(), health, "No direct damage");
        helper.assertTrue(helper.getBlockState(new BlockPos(8, 1, 7)).is(Blocks.STONE), "Terrain remains intact");
        helper.assertTrue(front.hasImpulse, "Vanilla motion tracking is notified");
        player.setXRot(-90);
        Husk almostVertical = husk(helper, player, 0.001, 3, 0);
        Husk stableDirection = husk(helper, player, 0.004, 3, 0);
        helper.assertFalse(AirBurstSpell.inPulse(player, almostVertical), "Near-vertical ratio below vanilla 1e-5 squared threshold cannot randomize impulse");
        helper.assertTrue(AirBurstSpell.inPulse(player, stableDirection), "A stable horizontal ratio above vanilla threshold remains eligible when aimed upward");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void colliderOcclusionUsesTargetCenterWithoutLoadingRemoteChunks(GameTestHelper helper) {
        Caster player = caster(helper);
        Husk target = husk(helper, player, 0, 0, 4);
        helper.setBlock(new BlockPos(8, 3, 6), Blocks.STONE);
        helper.assertTrue(AirBurstSpell.cast(player), "Obstructed pulse still releases");
        stationary(helper, target, "Solid collision shape blocks impulse");
        reset(player);
        helper.setBlock(new BlockPos(8, 3, 6), Blocks.SHORT_GRASS);
        helper.assertTrue(AirBurstSpell.cast(player), "Non-colliding decoration does not block air");
        helper.assertTrue(target.getDeltaMovement().z > 0, "Target moves through non-colliding ray");
        Vec3 remote = new Vec3(10000000, 80, 10000000);
        player.setPos(remote);
        BlockPos absent = BlockPos.containing(remote);
        helper.assertFalse(helper.getLevel().hasChunkAt(absent), "Remote chunk starts absent");
        helper.assertFalse(AirBurstSpell.visible(player, remote.add(0, 1, 3)), "Unloaded ray is rejected");
        helper.assertFalse(helper.getLevel().hasChunkAt(absent), "Ray did not request remote chunk");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void boundedTargetsAreNearestThenStableByEntityId(GameTestHelper helper) {
        Caster player = caster(helper);
        List<Husk> tied = new ArrayList<>();
        for (int index = 0; index < MagicConfig.AIR_BURST_MAX_TARGETS.getAsInt() + 2; index++) tied.add(husk(helper, player, 0, 0, 3));
        Husk nearer = husk(helper, player, 0, 0, 2);
        List<Mob> selected = AirBurstSpell.targets(player);
        helper.assertValueEqual(selected.size(), MagicConfig.AIR_BURST_MAX_TARGETS.getAsInt(), "Bounded work per pulse");
        helper.assertValueEqual(selected.getFirst(), nearer, "Distance precedes creation order");
        for (int index = 1; index < selected.size(); index++) helper.assertValueEqual(selected.get(index), tied.get(index - 1), "Equal distances use stable entity id order");
        helper.assertTrue(AirBurstSpell.cast(player), "Crowded pulse releases");
        long moved = tied.stream().filter(target -> target.getDeltaMovement().lengthSqr() > 0).count() + 1;
        helper.assertValueEqual(moved, (long) MagicConfig.AIR_BURST_MAX_TARGETS.getAsInt(), "Only selected limit receives impulse");
        helper.assertValueEqual(AirBurstSpell.practiceCount(player), 1, "Many targets still mean one spell");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void resistanceAndCancelledHookPreservePaymentAndBlockReentry(GameTestHelper helper) {
        Caster player = caster(helper);
        Husk half = husk(helper, player, -0.8, 0, 3);
        Husk full = husk(helper, player, 0, 0, 3);
        Husk denied = husk(helper, player, 0.8, 0, 3);
        half.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(0.5);
        full.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0);
        AtomicInteger hooks = new AtomicInteger();
        Consumer<LivingKnockBackEvent> cancel = event -> {
            if (event.getEntity() != denied) return;
            hooks.incrementAndGet();
            helper.assertFalse(AirBurstSpell.cast(player), "Resources settle before effect hooks, blocking reentry");
            event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(LivingKnockBackEvent.class, cancel);
        try { helper.assertTrue(AirBurstSpell.cast(player), "Resistance and cancelled targets do not unrelease pulse"); }
        finally { NeoForge.EVENT_BUS.unregister(cancel); }
        double expected = MagicConfig.AIR_BURST_STRENGTH.get() * 0.5;
        helper.assertTrue(Math.abs(half.getDeltaMovement().horizontalDistance() - expected) < 0.0001, "Vanilla resistance scales knockback exactly once");
        stationary(helper, full, "Full resistance stays still");
        stationary(helper, denied, "Cancelled vanilla knockback event stays still");
        helper.assertValueEqual(hooks.get(), 1, "Event fires once per selected target");
        helper.assertValueEqual(AirBurstSpell.practiceCount(player), 1, "Resistance does not refund or duplicate practice");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100 - MagicConfig.AIR_BURST_COST.getAsInt(), "Resistance does not refund fee");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void friendlyOwnedMountedInvulnerableAndBossTargetsAreExcluded(GameTestHelper helper) {
        Caster player = caster(helper);
        Husk allied = husk(helper, player, 0, 0, 3);
        var scoreboard = helper.getLevel().getScoreboard();
        var team = scoreboard.addPlayerTeam("air" + UUID.randomUUID().toString().substring(0, 8));
        try {
            scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
            scoreboard.addPlayerToTeam(allied.getScoreboardName(), team);
            helper.assertFalse(AirBurstSpell.eligible(player, allied), "Allied enemy is protected");
            helper.assertTrue(AirBurstSpell.cast(player), "All-allied pulse remains a release");
            stationary(helper, allied, "Allied target did not move");
        } finally { scoreboard.removePlayerTeam(team); }
        Husk immune = husk(helper, player, 0, 0, 3);
        immune.setInvulnerable(true);
        helper.assertFalse(AirBurstSpell.eligible(player, immune), "Invulnerable enemy protected");
        helper.assertFalse(AirBurstSpell.eligible(player, new OwnedZombie(helper.getLevel())), "Owned Enemy mob protected even with owner offline");
        helper.assertFalse(AirBurstSpell.eligible(player, caster(helper)), "Other players never affected");
        helper.assertFalse(AirBurstSpell.eligible(player, EntityType.COW.create(helper.getLevel())), "Animals never affected");
        helper.assertFalse(AirBurstSpell.eligible(player, EntityType.VILLAGER.create(helper.getLevel())), "Villagers never affected");
        helper.assertFalse(AirBurstSpell.eligible(player, EntityType.WITHER.create(helper.getLevel())), "Default boss immunity tag applies");
        helper.assertTrue(EntityType.ENDER_DRAGON.is(AirBurstSpell.IMMUNE), "Default dragon immunity tag is loaded");
        var slime = EntityType.SLIME.create(helper.getLevel());
        helper.assertTrue(AirBurstSpell.eligible(player, slime), "Enemy classification includes Slime despite not extending Monster");
        Husk mounted = husk(helper, player, 0, 0, 3);
        var carrier = EntityType.COW.create(helper.getLevel());
        helper.assertTrue(mounted.startRiding(carrier, true), "Mounted fixture succeeds");
        helper.assertFalse(AirBurstSpell.eligible(player, mounted), "Passengers excluded");
        mounted.stopRiding();
        var rider = EntityType.COW.create(helper.getLevel());
        helper.assertTrue(rider.startRiding(mounted, true), "Carrier fixture succeeds");
        helper.assertFalse(AirBurstSpell.eligible(player, mounted), "Carriers excluded");
        rider.stopRiding();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void releaseGuardsRejectUnknownMissingStaffAndInsufficientResources(GameTestHelper helper) {
        Caster player = caster(helper);
        Husk target = husk(helper, player, 0, 0, 3);
        player.setData(ModAttachments.KNOWLEDGE, new MagicKnowledge());
        helper.assertFalse(AirBurstSpell.cast(player), "Unknown spell rejected");
        player.getData(ModAttachments.KNOWLEDGE).learn(AirBurstSpell.SPELL);
        player.setItemInHand(InteractionHand.MAIN_HAND, Items.STICK.getDefaultInstance());
        helper.assertFalse(AirBurstSpell.cast(player), "Ordinary stick rejected");
        free(helper, player, "Knowledge and held item validation");
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.setData(ModAttachments.MANA, new ManaData(MagicConfig.AIR_BURST_COST.getAsInt() - 1, 100, 0, 0));
        helper.assertFalse(AirBurstSpell.cast(player), "Insufficient mana rejected");
        reset(player);
        player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), 10);
        helper.assertFalse(AirBurstSpell.cast(player), "Item-only cooldown cannot be bypassed by direct entry");
        stationary(helper, target, "Rejected releases have no effects");
        reset(player);
        player.getAbilities().instabuild = true;
        helper.assertTrue(AirBurstSpell.cast(player), "Creative can release");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100, "Creative pays no mana");
        helper.assertValueEqual(AirBurstSpell.practiceCount(player), 0, "Creative earns no practice");
        helper.assertTrue(player.getData(ModAttachments.MANA).cooldown() > 0, "Creative retains shared cooldown");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void earlySneakAndSwitchCancelWhileLongHoldNeverStrengthensPulse(GameTestHelper helper) {
        Caster player = caster(helper);
        Husk target = husk(helper, player, 0, 0, 3);
        hold(player, AirBurstSpell.chantTicks(player) - 1);
        player.releaseUsingItem();
        free(helper, player, "Early release");
        hold(player, AirBurstSpell.chantTicks(player));
        player.setShiftKeyDown(true);
        player.releaseUsingItem();
        player.setShiftKeyDown(false);
        free(helper, player, "Sneak release");
        player.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);
        hold(player, AirBurstSpell.chantTicks(player));
        helper.assertTrue(SpellCasting.cycle(player), "G can leave ready air spell");
        player.releaseUsingItem();
        free(helper, player, "Switching clears active air lock");
        stationary(helper, target, "Cancelled holds impart no motion");
        player.getData(ModAttachments.CASTING).select(AirBurstSpell.SPELL);
        hold(player, AirBurstSpell.chantTicks(player) + 100);
        player.releaseUsingItem();
        double first = target.getDeltaMovement().horizontalDistance();
        helper.assertTrue(Math.abs(first - MagicConfig.AIR_BURST_STRENGTH.get()) < 0.0001, "Long hold keeps configured strength");
        helper.assertValueEqual(player.getData(ModAttachments.MANA).mana(), 100 - MagicConfig.AIR_BURST_COST.getAsInt(), "Long hold keeps fixed mana fee");
        helper.assertValueEqual(AirBurstSpell.practiceCount(player), 1, "Long hold releases once");
        helper.succeed();
    }
    private AirBurstGameTests() {}
}
