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
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
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
        Caster(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "Air" + UUID.randomUUID().toString().substring(0, 8))); }
        void tickUse() { if (isUsingItem()) updateUsingItem(getUseItem()); }
    }
    private static Caster caster(GameTestHelper helper) {
        Caster player = new Caster(helper.getLevel());
        equipCaster(helper, player);
        return player;
    }
    private static ServerPlayer humanCaster(GameTestHelper helper) {
        var level = helper.getLevel();
        // FakePlayer.canHarmPlayer unconditionally denies PvP. Use vanilla's actual team and PvP checks.
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),
                "AirPvp" + UUID.randomUUID().toString().substring(0, 8)), false);
        var player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player, cookie) {
            @Override public void send(Packet<?> packet) {}
            @Override public void send(Packet<?> packet, PacketSendListener listener) {}
        };
        equipCaster(helper, player);
        return player;
    }
    private static void equipCaster(GameTestHelper helper, ServerPlayer player) {
        player.setPos(helper.absoluteVec(new Vec3(8, 2, 4)));
        player.setYRot(0); player.setXRot(0);
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(AirBurstSpell.SPELL);
        player.getData(ModAttachments.CASTING).select(AirBurstSpell.SPELL);
        player.setData(ModAttachments.MANA, ManaData.full(100));
    }
    private static Husk husk(GameTestHelper helper, Caster player, double x, double y, double z) {
        Husk mob = EntityType.HUSK.create(helper.getLevel());
        mob.setPos(player.position().add(x, y, z));
        mob.setNoAi(true); mob.setNoGravity(true); mob.setOnGround(true);
        mob.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(mob);
        return mob;
    }
    private static void reset(ServerPlayer player) {
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
        List<LivingEntity> selected = AirBurstSpell.targets(player);
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
    public static void airPushesCattleAndVillagersButSparesStoryResidentsAndOwnPets(GameTestHelper helper) {
        var player = humanCaster(helper);
        var level = helper.getLevel();
        var cow = EntityType.COW.create(level);
        var pet = EntityType.WOLF.create(level);
        var villager = EntityType.VILLAGER.create(level);
        List<Mob> protectedTargets = List.of(pet, ModContent.ROXY_TUTOR.get().create(level),
                ModContent.SYLPHIETTE.get().create(level), ModContent.PAUL_GREYRAT.get().create(level),
                ModContent.ZENITH_GREYRAT.get().create(level), ModContent.LILIA_GREYRAT.get().create(level));
        pet.setTame(true, false);
        pet.setOwnerUUID(player.getUUID());
        List<Mob> targets = new ArrayList<>(protectedTargets);
        targets.add(cow);
        targets.add(villager);
        for (Mob target : targets) {
            target.moveTo(player.getX(), player.getY(), player.getZ() + 3, 0, 0);
            helper.assertTrue(level.addFreshEntity(target) && AirBurstSpell.inPulse(player, target),
                    "Each fixture is in range and in sight: " + target.getType());
        }
        boolean oldPvp = level.getServer().isPvpAllowed();
        try {
            level.getServer().setPvpAllowed(true);
            level.addNewPlayer(player);
            helper.assertTrue(AirBurstSpell.cast(player), "Release one pulse across all eight targets");
            helper.assertTrue(cow.getDeltaMovement().z > 0 && villager.getDeltaMovement().z > 0,
                    "Cattle and ordinary villagers are pushed");
            for (Mob target : protectedTargets)
                stationary(helper, target, "Story residents and the caster's own pet stay put: " + target.getType());
        } finally {
            player.discard();
            level.getServer().setPvpAllowed(oldPvp);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void playerImpulseRespectsPvpFriendlyFireAndInvulnerability(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = humanCaster(helper);
        var other = humanCaster(helper);
        other.setPos(player.position().add(0, 0, 3));
        var wolf = EntityType.WOLF.create(level);
        wolf.setTame(true, false);
        wolf.setOwnerUUID(other.getUUID());
        wolf.moveTo(player.getX() + .4, player.getY(), player.getZ() + 3, 0, 0);
        var scoreboard = level.getScoreboard();
        var team = scoreboard.addPlayerTeam("air" + UUID.randomUUID().toString().substring(0, 8));
        boolean oldPvp = level.getServer().isPvpAllowed();
        try {
            level.addNewPlayer(other);
            helper.assertTrue(level.addFreshEntity(wolf) && wolf.getOwner() == other && AirBurstSpell.inPulse(player, wolf),
                    "A nearby pet belongs to the second player in this level");
            level.getServer().setPvpAllowed(false);
            helper.assertFalse(AirBurstSpell.eligible(player, other), "PvP-disabled server protects players");
            helper.assertTrue(AirBurstSpell.cast(player), "PvP-disabled cast still settles");
            stationary(helper, wolf, "PvP-disabled server protects another player's pet");
            reset(player);
            level.getServer().setPvpAllowed(true);
            scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
            scoreboard.addPlayerToTeam(other.getScoreboardName(), team);
            team.setAllowFriendlyFire(false);
            helper.assertFalse(AirBurstSpell.eligible(player, other), "Friendly fire setting protects teammates");
            helper.assertTrue(AirBurstSpell.cast(player), "Same-team pulse releases");
            stationary(helper, wolf, "Pet of a teammate with friendly fire disabled is protected");
            reset(player);
            team.setAllowFriendlyFire(true);
            other.getAbilities().invulnerable = true;
            helper.assertFalse(AirBurstSpell.eligible(player, other), "Creative invulnerability is respected");
            other.getAbilities().invulnerable = false;
            other.hurtMarked = false;
            float health = other.getHealth();
            helper.assertTrue(AirBurstSpell.cast(player), "Allowed player receives the real cast");
            helper.assertTrue(other.getDeltaMovement().z > 0 && other.hurtMarked,
                    "Player impulse points away and is marked for the controlling client");
            helper.assertTrue(wolf.getDeltaMovement().z > 0, "Friendly fire enabled lets the caster push the other player's pet");
            helper.assertValueEqual(other.getHealth(), health, "Knockback deals no damage");
        } finally {
            wolf.discard();
            other.discard();
            scoreboard.removePlayerTeam(team);
            level.getServer().setPvpAllowed(oldPvp);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void petWithOfflineOwnerUsesPvpAndStoredTeamMembership(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var caster = humanCaster(helper);
        var owner = new GameProfile(UUID.randomUUID(), "AirPet" + UUID.randomUUID().toString().substring(0, 8));
        var wolf = EntityType.WOLF.create(level);
        wolf.setTame(true, false);
        wolf.setOwnerUUID(owner.getId());
        var board = level.getScoreboard();
        var team = board.addPlayerTeam("air" + UUID.randomUUID().toString().substring(0, 8));
        boolean oldPvp = server.isPvpAllowed();
        try {
            server.setPvpAllowed(false);
            helper.assertFalse(AirBurstSpell.eligible(caster, wolf), "PvP off protects an absent owner's wolf");
            server.setPvpAllowed(true);
            helper.assertTrue(AirBurstSpell.eligible(caster, wolf), "An unteamed caster may affect another owner's wolf with PvP on");
            board.addPlayerToTeam(caster.getScoreboardName(), team);
            team.setAllowFriendlyFire(false);
            helper.assertFalse(AirBurstSpell.eligible(caster, wolf), "Unknown offline owner is protected when friendly fire matters");
            // A GameTest server can be started without a profile cache. That is the unknown-owner case above.
            var cache = server.getProfileCache();
            if (cache != null) {
                cache.add(owner);
                board.addPlayerToTeam(owner.getName(), team);
                helper.assertFalse(AirBurstSpell.eligible(caster, wolf), "An offline teammate's pet remains protected");
                board.removePlayerFromTeam(owner.getName(), team);
                helper.assertTrue(AirBurstSpell.eligible(caster, wolf), "A known offline owner outside the caster's team is eligible");
            }
        } finally {
            board.removePlayerTeam(team);
            server.setPvpAllowed(oldPvp);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pulseHonoursTeamSettingAndPushesMountWithoutDismountingPassenger(GameTestHelper helper) {
        Caster player = caster(helper);
        Husk allied = husk(helper, player, 0, 0, 3);
        var scoreboard = helper.getLevel().getScoreboard();
        var team = scoreboard.addPlayerTeam("air" + UUID.randomUUID().toString().substring(0, 8));
        try {
            scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
            scoreboard.addPlayerToTeam(allied.getScoreboardName(), team);
            team.setAllowFriendlyFire(false);
            helper.assertFalse(AirBurstSpell.eligible(player, allied), "No-friendly-fire protects allied mob");
            team.setAllowFriendlyFire(true);
            helper.assertTrue(AirBurstSpell.eligible(player, allied), "Friendly fire enabled allows allied mob");
        } finally { scoreboard.removePlayerTeam(team); }
        helper.assertFalse(AirBurstSpell.eligible(player, player), "The caster does not push themselves");
        helper.assertFalse(AirBurstSpell.eligible(player, EntityType.ARMOR_STAND.create(helper.getLevel())), "Decorative stands are excluded");
        helper.assertTrue(AirBurstSpell.eligible(player, EntityType.WITHER.create(helper.getLevel())), "Bosses use vanilla knockback resistance");
        var slime = EntityType.SLIME.create(helper.getLevel());
        helper.assertTrue(AirBurstSpell.eligible(player, slime), "Enemy classification includes Slime despite not extending Monster");
        var horse = EntityType.HORSE.create(helper.getLevel());
        horse.moveTo(player.getX(), player.getY(), player.getZ() + 3, 0, 0);
        helper.getLevel().addFreshEntity(horse);
        Husk rider = husk(helper, player, 0, 0, 3);
        helper.assertTrue(rider.startRiding(horse, true), "Rider mounts the real in-world horse");
        helper.assertFalse(AirBurstSpell.eligible(player, rider), "Passengers do not receive an independent impulse");
        helper.assertTrue(AirBurstSpell.targets(player).contains(horse), "Mounted horse is selected by the real cone query");
        helper.assertTrue(AirBurstSpell.cast(player), "Pulse reaches a mount carrying its rider");
        helper.assertTrue(horse.getDeltaMovement().z > 0 && rider.getVehicle() == horse,
                "The mount is pushed without dismounting its passenger");
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
