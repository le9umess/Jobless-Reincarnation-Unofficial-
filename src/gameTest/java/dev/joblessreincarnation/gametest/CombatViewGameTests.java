package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.combat.AimMath;
import dev.joblessreincarnation.combat.CombatViewNumbers;
import dev.joblessreincarnation.combat.CombatViewRules;
import dev.joblessreincarnation.combat.CombatViewRules.View;
import dev.joblessreincarnation.combat.CombatViewTrace;
import dev.joblessreincarnation.combat.CrosshairReach;
import dev.joblessreincarnation.combat.HeldRelease;
import dev.joblessreincarnation.combat.ReleaseEye;
import dev.joblessreincarnation.combat.ReleaseSync;
import dev.joblessreincarnation.magic.EarthWallDismissal;
import dev.joblessreincarnation.magic.EarthWallSpell;
import dev.joblessreincarnation.magic.MagicConfig;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.magic.StaffSide;
import dev.joblessreincarnation.magic.WaterBolt;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The combat view's pure rules: who owns the point of view (each switch decided as Epic Fight makes it), where the staff
 * aims, what a release sends first, and what the crosshair may reach. The camera mixins and the real input order need a
 * real client; see the hand-over notes.
 */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatViewGameTests {
    private static View req(CombatViewRules rules, boolean enabled, boolean tps, boolean combat, View view) {
        return rules.tick(enabled, tps, combat, view).request();
    }

    @GameTest(template = "empty")
    public static void perspectiveRulesRestoreOnlyWhatTheyOwn(GameTestHelper h) {
        var r = new CombatViewRules();
        h.assertTrue(req(r, true, true, false, View.FIRST_PERSON) == null, "Joining is not an entry");
        h.assertTrue(req(r, true, true, true, View.FIRST_PERSON) == View.THIRD_PERSON_BACK && r.owns(), "Entering from first person switches to the back view and owns it");
        h.assertTrue(req(r, true, true, true, View.THIRD_PERSON_BACK) == null && r.owns(), "Nothing more while it stays");
        h.assertTrue(req(r, true, true, false, View.THIRD_PERSON_BACK) == View.FIRST_PERSON && !r.owns(), "Leaving restores the view from before entering");
        h.assertTrue(req(r, true, true, false, View.FIRST_PERSON) == null, "And then leaves it alone");

        var third = new CombatViewRules();
        req(third, true, true, false, View.THIRD_PERSON_BACK);
        h.assertTrue(req(third, true, true, true, View.THIRD_PERSON_BACK) == null && !third.owns(), "Entering from third person changes nothing");
        h.assertTrue(req(third, true, true, false, View.THIRD_PERSON_BACK) == null, "...and leaving keeps third person");
        var front = new CombatViewRules();
        req(front, true, true, false, View.THIRD_PERSON_FRONT);
        h.assertTrue(req(front, true, true, true, View.THIRD_PERSON_FRONT) == null && req(front, true, true, false, View.THIRD_PERSON_FRONT) == null, "Front view likewise");

        var joined = new CombatViewRules();
        h.assertTrue(req(joined, true, true, true, View.FIRST_PERSON) == null && req(joined, true, true, true, View.FIRST_PERSON) == null, "A world joined in combat mode is not an entry");
        h.assertTrue(req(joined, true, true, false, View.FIRST_PERSON) == null, "...so leaving restores nothing");

        var off = new CombatViewRules();
        req(off, true, true, false, View.FIRST_PERSON); req(off, true, true, true, View.FIRST_PERSON); req(off, true, true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(req(off, false, true, true, View.THIRD_PERSON_BACK) == View.FIRST_PERSON && !off.owns(), "Turning the setting off gives back an untouched owned view");
        h.assertTrue(req(off, false, true, false, View.FIRST_PERSON) == null, "Off: nothing more");

        var reset = new CombatViewRules();
        req(reset, true, true, false, View.FIRST_PERSON); req(reset, true, true, true, View.FIRST_PERSON);
        reset.reset();
        h.assertTrue(req(reset, true, true, true, View.THIRD_PERSON_BACK) == null && req(reset, true, true, false, View.THIRD_PERSON_BACK) == null,
                "After a new connection nothing stale is restored");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void perspectiveRulesYieldTheRoundToTheFiveKey(GameTestHelper h) {
        var r = new CombatViewRules();
        req(r, true, true, false, View.FIRST_PERSON); req(r, true, true, true, View.FIRST_PERSON); req(r, true, true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(req(r, true, true, true, View.THIRD_PERSON_FRONT) == null && r.yielded() && !r.owns(), "F5 is the player's choice: the round yields, nothing forced back");
        h.assertTrue(req(r, true, true, true, View.FIRST_PERSON) == null && req(r, true, true, true, View.THIRD_PERSON_BACK) == null && r.yielded(),
                "Still nothing forced, even back in the back view");
        h.assertTrue(req(r, true, true, false, View.THIRD_PERSON_BACK) == null && !r.yielded(), "Leaving restores nothing it no longer owns");
        req(r, true, true, false, View.FIRST_PERSON);
        h.assertTrue(req(r, true, true, true, View.FIRST_PERSON) == View.THIRD_PERSON_BACK && r.owns() && !r.yielded(), "The next entry takes over again");

        var third = new CombatViewRules();
        req(third, true, true, false, View.THIRD_PERSON_BACK); req(third, true, true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(req(third, true, true, true, View.FIRST_PERSON) == null && third.yielded(), "F5 yields the round even when the view was never ours");

        var lost = new CombatViewRules();
        req(lost, true, true, false, View.FIRST_PERSON); req(lost, true, true, true, View.FIRST_PERSON); req(lost, true, true, true, View.THIRD_PERSON_BACK);
        var step = lost.tick(true, false, true, View.THIRD_PERSON_BACK);
        h.assertTrue(step.request() == null && step.logTpsLost() && !lost.owns(), "Another camera mod disabling Epic Fight's TPS: stand down, keep the view, log once");
        h.assertTrue(!lost.tick(true, false, true, View.THIRD_PERSON_BACK).logTpsLost(), "...only once");
        h.assertTrue(lost.tick(true, true, true, View.THIRD_PERSON_BACK).logTpsBack(), "Its return is logged once too");
        h.assertTrue(req(lost, true, true, false, View.THIRD_PERSON_BACK) == null, "No restore from before the loss");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void perspectiveSwitchesAreDecidedWhenEpicFightMakesThem(GameTestHelper h) {
        // GPT's case: the setting was turned on a moment ago (no tick since), and the player's own Epic Fight auto switch is on: R.
        var fresh = new CombatViewRules();
        req(fresh, false, true, false, View.FIRST_PERSON); req(fresh, false, true, false, View.FIRST_PERSON);
        var in = fresh.enter(true, View.FIRST_PERSON);
        h.assertTrue(in.request() == View.THIRD_PERSON_BACK && in.holdEpicFight() && fresh.owns() && fresh.guarded(),
                "Classified at the switch itself, with the setting as it is then: protected, switched to the back view and owned");
        h.assertTrue(req(fresh, true, true, true, View.THIRD_PERSON_BACK) == null && fresh.owns() && !fresh.yielded(), "The next tick finds nothing new");
        var out = fresh.exit(true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(out.request() == View.FIRST_PERSON && out.holdEpicFight() && !fresh.owns(), "Leaving gives first person back, Epic Fight held back");
        h.assertTrue(req(fresh, true, true, false, View.FIRST_PERSON) == null, "...once");

        // Turned off a moment before R (the last tick still saw it on): an Epic Fight round.
        var off = new CombatViewRules();
        req(off, true, true, false, View.FIRST_PERSON);
        var offIn = off.enter(false, View.FIRST_PERSON);
        h.assertTrue(offIn.request() == null && !offIn.holdEpicFight() && !off.owns() && !off.guarded(), "Not taking over at that moment: Epic Fight's round");

        // Before the first tick of a new local player: Epic Fight's.
        var early = new CombatViewRules();
        var earlyIn = early.enter(true, View.FIRST_PERSON);
        h.assertTrue(earlyIn.request() == null && !earlyIn.holdEpicFight() && !early.owns() && !early.guarded(), "A switch before the first look is Epic Fight's");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void protectedRoundsHoldEpicFightBackBothWays(GameTestHelper h) {
        // The player's own Epic Fight auto switch is on: left alone it would switch to the back view on entering and to
        // first person on leaving. A protected round entered from the back view switches nothing either way.
        var back = new CombatViewRules();
        req(back, true, true, false, View.THIRD_PERSON_BACK);
        var backIn = back.enter(true, View.THIRD_PERSON_BACK);
        h.assertTrue(backIn.request() == null && backIn.holdEpicFight() && back.guarded() && !back.owns(), "From the back view: protected, Epic Fight held back, nothing owned");
        h.assertTrue(req(back, true, true, true, View.THIRD_PERSON_BACK) == null && !back.yielded(), "The round runs on");
        var backOut = back.exit(true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(backOut.request() == null && backOut.holdEpicFight(), "Leaving keeps the back view: Epic Fight held back, nothing given back");

        var front = new CombatViewRules();
        req(front, true, true, false, View.THIRD_PERSON_FRONT);
        var frontIn = front.enter(true, View.THIRD_PERSON_FRONT);
        h.assertTrue(frontIn.request() == null && frontIn.holdEpicFight() && front.guarded() && !front.owns(), "From the front view: protected, Epic Fight held back");
        h.assertTrue(req(front, true, true, true, View.THIRD_PERSON_FRONT) == null && !front.yielded(), "The front view is the round's own view, not a change");
        var frontOut = front.exit(true, true, View.THIRD_PERSON_FRONT);
        h.assertTrue(frontOut.request() == null && frontOut.holdEpicFight(), "Leaving keeps the front view");

        // F5 during a protected round: the round yields and stays protected; leaving keeps the player's choice.
        var f5 = new CombatViewRules();
        req(f5, true, true, false, View.FIRST_PERSON); f5.enter(true, View.FIRST_PERSON); req(f5, true, true, true, View.THIRD_PERSON_BACK);
        req(f5, true, true, true, View.THIRD_PERSON_FRONT);
        h.assertTrue(f5.yielded() && f5.guarded() && !f5.owns(), "F5 yields the round, which stays protected");
        var f5Out = f5.exit(true, true, View.THIRD_PERSON_FRONT);
        h.assertTrue(f5Out.request() == null && f5Out.holdEpicFight(), "Leaving keeps the view the player chose, Epic Fight held back");
        // F5 in the same tick as R, just before it: the view changed since the last look, so nothing is given back either.
        var same = new CombatViewRules();
        req(same, true, true, false, View.FIRST_PERSON); same.enter(true, View.FIRST_PERSON); req(same, true, true, true, View.THIRD_PERSON_BACK);
        var sameOut = same.exit(true, true, View.THIRD_PERSON_FRONT);
        h.assertTrue(sameOut.request() == null && sameOut.holdEpicFight(), "A view changed since the last look stays, Epic Fight held back");

        // The setting off when entering: an Epic Fight round, both ways.
        var epic = new CombatViewRules();
        req(epic, false, true, false, View.FIRST_PERSON);
        var epicIn = epic.enter(false, View.FIRST_PERSON);
        h.assertTrue(epicIn.request() == null && !epicIn.holdEpicFight() && !epic.guarded(), "Setting off: Epic Fight's entry");
        req(epic, false, true, true, View.THIRD_PERSON_BACK);
        var epicOut = epic.exit(false, true, View.THIRD_PERSON_BACK);
        h.assertTrue(epicOut.request() == null && !epicOut.holdEpicFight(), "...and Epic Fight's exit");

        // Turned off during a protected round: the owned view goes back at once, and the rest of the round is Epic Fight's.
        var offMid = new CombatViewRules();
        req(offMid, true, true, false, View.FIRST_PERSON); offMid.enter(true, View.FIRST_PERSON); req(offMid, true, true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(req(offMid, false, true, true, View.THIRD_PERSON_BACK) == View.FIRST_PERSON && !offMid.guarded() && !offMid.owns(),
                "Turned off: back to first person, and the round becomes Epic Fight's");
        req(offMid, false, true, true, View.FIRST_PERSON);
        var offMidOut = offMid.exit(false, true, View.FIRST_PERSON);
        h.assertTrue(offMidOut.request() == null && !offMidOut.holdEpicFight(), "Its exit is Epic Fight's");
        // ...and when leaving meets that change before any tick has seen it.
        var offRace = new CombatViewRules();
        req(offRace, true, true, false, View.FIRST_PERSON); offRace.enter(true, View.FIRST_PERSON); req(offRace, true, true, true, View.THIRD_PERSON_BACK);
        var raceOut = offRace.exit(false, true, View.THIRD_PERSON_BACK);
        h.assertTrue(raceOut.request() == View.FIRST_PERSON && !raceOut.holdEpicFight(),
                "Turned off a moment before R: the owned view goes back, and Epic Fight switches as it would");

        // Epic Fight's TPS lost during a protected round: the view stays, and the rest of the round is Epic Fight's.
        var lost = new CombatViewRules();
        req(lost, true, true, false, View.FIRST_PERSON); lost.enter(true, View.FIRST_PERSON); req(lost, true, true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(lost.tick(true, false, true, View.THIRD_PERSON_BACK).request() == null && !lost.guarded() && !lost.owns(), "TPS lost: the view stays, the round is Epic Fight's");
        var lostOut = lost.exit(true, false, View.THIRD_PERSON_BACK);
        h.assertTrue(lostOut.request() == null && !lostOut.holdEpicFight(), "Its exit is Epic Fight's");

        // Turned on during an Epic Fight round: that round stays Epic Fight's, and Epic Fight's own switch is not the
        // player's F5; the next entry is protected.
        var onMid = new CombatViewRules();
        req(onMid, false, true, false, View.FIRST_PERSON);
        onMid.enter(false, View.FIRST_PERSON);
        req(onMid, true, true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(!onMid.guarded() && !onMid.yielded(), "Turned on mid-round: still Epic Fight's round, and its switch to the back view was not the player's");
        var onMidOut = onMid.exit(true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(onMidOut.request() == null && !onMidOut.holdEpicFight(), "Its exit is Epic Fight's");
        req(onMid, true, true, false, View.FIRST_PERSON);
        var next = onMid.enter(true, View.FIRST_PERSON);
        h.assertTrue(next.request() == View.THIRD_PERSON_BACK && next.holdEpicFight() && onMid.guarded(), "The next entry is protected");
        h.succeed();
    }

    /** Epic Fight's camera for a framing at a yaw and pitch, before collision: the rotated offset minus zoom/2 along the look. */
    private static Vec3 camera(Vec3 eye, float yaw, float pitch) {
        Vec3 look = AimMath.view(yaw, pitch);
        double h = CombatViewNumbers.HORIZONTAL * 0.2, v = CombatViewNumbers.VERTICAL * 0.2, r = Math.toRadians(yaw);
        Vec3 offset = new Vec3(h * Math.cos(r), v, h * Math.sin(r));
        return eye.add(offset).subtract(look.scale(CombatViewNumbers.ZOOM * 0.5));
    }

    @GameTest(template = "empty")
    public static void staffAimTurnsTheEyesToTheCrosshairPointWithSteadyFallbacks(GameTestHelper h) {
        Vec3 eye = new Vec3(10.5, 72.62, -3.5);
        for (float pitch = -60; pitch <= 60; pitch += 20) for (float yaw = -170; yaw <= 180; yaw += 50) for (double distance : new double[]{3, 10, 40}) {
            Vec3 look = AimMath.view(yaw, pitch), cam = camera(eye, yaw, pitch), point = cam.add(look.scale(distance + 5.0));
            var aim = AimMath.aim(eye, cam, look, point, false);
            Vec3 toPoint = point.subtract(eye).normalize();
            Vec3 seen = AimMath.view(aim.yaw(), aim.pitch());
            double error = Math.toDegrees(Math.atan2(seen.cross(toPoint).length(), seen.dot(toPoint)));
            h.assertTrue(aim.source() == AimMath.Source.BLOCK && error < 0.05, "Eyes look through the crosshair point at yaw " + yaw + " pitch " + pitch + " distance " + distance + ": " + error);
        }
        Vec3 look = AimMath.view(30, 10), cam = camera(eye, 30, 10);
        var miss = AimMath.aim(eye, cam, look, null, false);
        h.assertTrue(miss.source() == AimMath.Source.MISS && miss.point().distanceTo(cam.add(look.scale(AimMath.MISS_DISTANCE))) < 1e-9, "No hit: aim at the camera's far point");
        var behind = AimMath.aim(eye, cam, look, cam.add(look.scale(1.0)), false);
        h.assertTrue(behind.source() == AimMath.Source.CAMERA && Math.abs(behind.yaw() - 30) < 0.02 && Math.abs(behind.pitch() - 10) < 0.02, "A hit behind the eyes: the camera's own direction");
        var close = AimMath.aim(eye, cam, look, eye.add(look.scale(0.2)), true);
        h.assertTrue(close.source() == AimMath.Source.CAMERA, "A hit right at the face: the camera's own direction");
        var entity = AimMath.aim(eye, cam, look, cam.add(look.scale(12)), true);
        h.assertTrue(entity.source() == AimMath.Source.ENTITY, "Entities are reported as such");
        h.assertTrue(AimMath.continuous(540, 180) == 540 && AimMath.continuous(10, 350) == -10, "Yaw stays continuous for interpolation");
        h.assertTrue(Math.abs(AimMath.angle(0, 0, 1, 0) - 1.0) < 0.02 && AimMath.angle(0, 90, 180, 90) < 0.05 && AimMath.angle(45, 5, 45, 5) < 0.05,
                "The acceptance measure is the three-dimensional angle between two looks");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void releaseSendsTheWholeLookEvenWithoutACrouchChange(GameTestHelper h) {
        h.assertValueEqual(ReleaseSync.plan(true, false, true, false, false, true), List.of(ReleaseSync.Step.ROTATION), "An ordinary release sends the look");
        h.assertValueEqual(ReleaseSync.plan(true, false, true, true, false, true), List.of(ReleaseSync.Step.SNEAK, ReleaseSync.Step.ROTATION), "Crouch first, then the look");
        h.assertValueEqual(ReleaseSync.plan(true, false, true, true, false, false), List.of(ReleaseSync.Step.SNEAK), "Without the combat view only the crouch, as before");
        h.assertValueEqual(ReleaseSync.plan(true, false, true, false, false, false), List.of(), "And nothing at all for a plain release outside it");
        h.assertTrue(ReleaseSync.plan(false, false, true, false, false, true).isEmpty() && ReleaseSync.plan(true, true, true, false, false, true).isEmpty()
                && ReleaseSync.plan(true, false, false, false, false, true).isEmpty(), "No release this tick (menu, key held, not using the staff): nothing");
        h.succeed();
    }

    private static BlockHitResult block(Vec3 point) { return new BlockHitResult(point, Direction.NORTH, BlockPos.containing(point), false); }
    /** Epic Fight's distance cut: a miss at exactly the dropped hit's point. */
    private static BlockHitResult cut(HitResult raw) { return BlockHitResult.miss(raw.getLocation(), Direction.NORTH, BlockPos.containing(raw.getLocation())); }

    @GameTest(template = "empty")
    public static void crosshairReachGivesBackOnlyWhatTheCameraDistanceCut(GameTestHelper h) {
        h.assertTrue(CombatViewNumbers.HORIZONTAL >= CombatViewNumbers.HORIZONTAL_MIN && CombatViewNumbers.HORIZONTAL <= CombatViewNumbers.HORIZONTAL_MAX
                && CombatViewNumbers.VERTICAL >= CombatViewNumbers.VERTICAL_MIN && CombatViewNumbers.VERTICAL <= CombatViewNumbers.VERTICAL_MAX
                && CombatViewNumbers.ZOOM >= CombatViewNumbers.ZOOM_MIN && CombatViewNumbers.ZOOM <= CombatViewNumbers.ZOOM_MAX, "The framing is inside Epic Fight's ranges");
        h.assertTrue(CombatViewNumbers.HORIZONTAL == -3 && CombatViewNumbers.VERTICAL == 3 && CombatViewNumbers.ZOOM == 10, "The chosen framing");
        // Eyes looking along +z; the combat view's camera 0.6 aside, 0.6 up and 5 back. Block reach 4.5, entity reach 3,
        // the player's own Epic Fight zoom 6: Epic Fight drops anything farther than 4.5 + 3 = 7.5 from the camera.
        Vec3 eye = new Vec3(0.5, 70.0, 0.5), camera = eye.add(-0.6, 0.6, -5.0);
        var pig = EntityType.PIG.create(h.getLevel());
        HitResult chest = block(eye.add(0, 0, 4.0)), far = block(eye.add(0, 0, 4.6));
        HitResult near = new EntityHitResult(pig, eye.add(0, 0, 2.5)), pigAt4 = new EntityHitResult(pig, eye.add(0, 0, 4.0));
        h.assertValueEqual(CrosshairReach.judge(chest, cut(chest), camera, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.RESTORE,
                "A chest 4 blocks ahead, dropped only because the camera is 5 back, comes back");
        h.assertValueEqual(CrosshairReach.judge(far, cut(far), camera, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.KEEP_OUT_OF_REACH,
                "4.6 blocks is beyond block reach, as in first person");
        h.assertValueEqual(CrosshairReach.judge(near, cut(near), camera, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.RESTORE,
                "An entity 2.5 ahead comes back");
        h.assertValueEqual(CrosshairReach.judge(pigAt4, cut(pigAt4), camera, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.KEEP_OUT_OF_REACH,
                "An entity 4 ahead stays dropped: entities use entity reach, not the larger of the two");
        // A wall pushes the camera in to 1.5 back: a block 6.2 ahead is 7.7 from it, dropped by Epic Fight, and not given
        // back for the 5 blocks the camera no longer has.
        Vec3 walled = eye.add(-0.2, 0.2, -1.5);
        HitResult past = block(eye.add(0, 0, 6.2));
        h.assertValueEqual(CrosshairReach.judge(past, cut(past), walled, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.KEEP_OUT_OF_REACH,
                "Near a wall nothing beyond the player's own reach comes back");
        h.assertValueEqual(CrosshairReach.judge(chest, chest, walled, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.KEEP_ACCEPTED, "What Epic Fight kept stays");
        h.assertValueEqual(CrosshairReach.judge(chest, block(eye.add(0, 0, 3.0)), camera, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.KEEP_OTHER,
                "A different hit put there by someone else stays");
        h.assertValueEqual(CrosshairReach.judge(chest, cut(far), camera, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.KEEP_OTHER,
                "A miss somewhere else stays");
        h.assertValueEqual(CrosshairReach.judge(chest, null, camera, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.KEEP_OTHER, "No result: nothing to do");
        h.assertValueEqual(CrosshairReach.judge(cut(chest), cut(chest), camera, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.KEEP_OTHER,
                "Epic Fight saw nothing: nothing to give back");
        h.assertValueEqual(CrosshairReach.judge(chest, cut(chest), camera, eye, 4.5, 3.0, 10), CrosshairReach.Verdict.KEEP_NOT_DISTANCE,
                "With zoom 10 Epic Fight's own cut keeps 9 blocks: a miss there is not its distance cut");
        h.assertTrue(!CombatViewTrace.ENABLED, "Diagnostics are off unless asked for");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void crosshairKeepsTheFirstPersonReachNearWalls(GameTestHelper h) {
        // The capture's spot W1: a wall behind pulls the camera in to about 1.7 blocks from the eyes. From there Epic
        // Fight's own limit (4.5 + 3 = 7.5 from the camera) keeps a chest 4.68 blocks from the eyes and a body hit 3.39
        // from them, which first person would not reach (block reach 4.5, entity reach 3).
        Vec3 eye = new Vec3(198.5, 73.62, -1103.5), walled = eye.add(-0.2, 0.25, -1.65);
        var pig = EntityType.PIG.create(h.getLevel());
        HitResult chest = block(eye.add(0, 0, 4.68)), body = new EntityHitResult(pig, eye.add(0, 0, 3.39));
        h.assertValueEqual(CrosshairReach.judge(chest, chest, walled, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.CUT,
                "A chest Epic Fight kept 4.68 blocks from the eyes is cut, as in first person");
        h.assertValueEqual(CrosshairReach.judge(body, body, walled, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.CUT,
                "A body hit 3.39 blocks from the eyes is cut: entities keep entity reach");
        var miss = CrosshairReach.missAt(chest, eye);
        h.assertTrue(miss.getType() == HitResult.Type.MISS && miss.getLocation().equals(chest.getLocation())
                && ((BlockHitResult) miss).getDirection() == Direction.SOUTH, "Cut the way vanilla does: a miss at the same point, facing away from the eyes");
        HitResult near = block(eye.add(0, 0, 4.3)), nearBody = new EntityHitResult(pig, eye.add(0, 0, 2.9)), edge = block(eye.add(0, 0, 4.5));
        h.assertValueEqual(CrosshairReach.judge(near, near, walled, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.KEEP_ACCEPTED, "Within block reach it stays");
        h.assertValueEqual(CrosshairReach.judge(nearBody, nearBody, walled, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.KEEP_ACCEPTED, "Within entity reach it stays");
        h.assertValueEqual(CrosshairReach.judge(edge, edge, walled, eye, 4.5, 3.0, 6), CrosshairReach.Verdict.CUT, "Exactly at reach is out, as vanilla's strict test");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void roundsCarryOverANewPlayerEntityInTheSameConnection(GameTestHelper h) {
        // Death and respawn: entered from first person, the view owned. Epic Fight gives the new entity the old one's
        // mode (toMode in its clone hook). When that is a real switch (the new patch not yet in combat mode) it reaches
        // the rules as an entry while the round is still on; otherwise nothing is reported and the round just goes on.
        var respawn = new CombatViewRules();
        req(respawn, true, true, false, View.FIRST_PERSON); respawn.enter(true, View.FIRST_PERSON); req(respawn, true, true, true, View.THIRD_PERSON_BACK);
        req(respawn, true, true, true, View.THIRD_PERSON_BACK); req(respawn, true, true, true, View.THIRD_PERSON_BACK);
        var again = respawn.enter(true, View.THIRD_PERSON_BACK);
        h.assertTrue(again.request() == null && again.holdEpicFight() && respawn.owns() && respawn.guarded(),
                "Respawned: the same round goes on, Epic Fight held back, the view still owned");
        h.assertTrue(req(respawn, true, true, true, View.THIRD_PERSON_BACK) == null && respawn.owns(), "The next tick changes nothing");
        var out = respawn.exit(true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(out.request() == View.FIRST_PERSON && out.holdEpicFight(), "Leaving gives first person back as usual");

        // Another dimension: the same without the death.
        var portal = new CombatViewRules();
        req(portal, true, true, false, View.FIRST_PERSON); portal.enter(true, View.FIRST_PERSON); req(portal, true, true, true, View.THIRD_PERSON_BACK);
        var arrived = portal.enter(true, View.THIRD_PERSON_BACK);
        h.assertTrue(arrived.request() == null && arrived.holdEpicFight() && portal.owns(), "Through a portal: the same round, still owned");
        h.assertTrue(portal.exit(true, true, View.THIRD_PERSON_BACK).request() == View.FIRST_PERSON, "...and first person comes back on leaving");

        // A protected round the player had yielded with F5: the new entity's entry is held back, nothing is switched.
        var yielded = new CombatViewRules();
        req(yielded, true, true, false, View.FIRST_PERSON); yielded.enter(true, View.FIRST_PERSON); req(yielded, true, true, true, View.THIRD_PERSON_BACK);
        req(yielded, true, true, true, View.THIRD_PERSON_FRONT);
        var yieldedAgain = yielded.enter(true, View.THIRD_PERSON_FRONT);
        h.assertTrue(yieldedAgain.request() == null && yieldedAgain.holdEpicFight() && yielded.yielded(), "The player's front view survives the respawn");
        var yieldedOut = yielded.exit(true, true, View.THIRD_PERSON_FRONT);
        h.assertTrue(yieldedOut.request() == null && yieldedOut.holdEpicFight(), "...and the exit keeps it");

        // An Epic Fight round stays Epic Fight's.
        var epic = new CombatViewRules();
        req(epic, false, true, false, View.FIRST_PERSON); epic.enter(false, View.FIRST_PERSON); req(epic, false, true, true, View.THIRD_PERSON_BACK);
        var epicAgain = epic.enter(false, View.THIRD_PERSON_BACK);
        h.assertTrue(epicAgain.request() == null && !epicAgain.holdEpicFight(), "Epic Fight's round: its own switch for the new entity");

        // The camera moved to another entity (spectating): ownership ends, the view is never given back.
        var spectate = new CombatViewRules();
        req(spectate, true, true, false, View.FIRST_PERSON); spectate.enter(true, View.FIRST_PERSON); req(spectate, true, true, true, View.THIRD_PERSON_BACK);
        spectate.cameraLeftPlayer();
        h.assertTrue(!spectate.owns() && spectate.guarded(), "Spectating another entity: no longer owned, still protected");
        var spectateOut = spectate.exit(true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(spectateOut.request() == null && spectateOut.holdEpicFight(), "Leaving keeps the view, Epic Fight held back");

        // Leaving the world (a new connection): everything starts over; the event wiring itself is checked on a client.
        var left = new CombatViewRules();
        req(left, true, true, false, View.FIRST_PERSON); left.enter(true, View.FIRST_PERSON); req(left, true, true, true, View.THIRD_PERSON_BACK);
        left.reset();
        var stale = left.exit(true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(stale.request() == null && !stale.holdEpicFight() && !left.owns(), "After a new connection nothing old is given back");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void respawnKeepsOnlyWhatIsStillOurs(GameTestHelper h) {
        // F5 chose the front view, then death and respawn with no switch reported (the new patch starts in combat mode):
        // leaving keeps the player's choice.
        var f5 = new CombatViewRules();
        req(f5, true, true, false, View.FIRST_PERSON); f5.enter(true, View.FIRST_PERSON); req(f5, true, true, true, View.THIRD_PERSON_BACK);
        req(f5, true, true, true, View.THIRD_PERSON_FRONT);
        for (int tick = 0; tick < 3; tick++) req(f5, true, true, true, View.THIRD_PERSON_FRONT);
        var f5Out = f5.exit(true, true, View.THIRD_PERSON_FRONT);
        h.assertTrue(f5Out.request() == null && f5Out.holdEpicFight(), "F5's front view survives death and respawn");
        // F5 chose first person, then another dimension with Epic Fight reporting the new entity's switch into combat
        // mode: that is not a new R, so nothing is switched and first person stays.
        var portal = new CombatViewRules();
        req(portal, true, true, false, View.FIRST_PERSON); portal.enter(true, View.FIRST_PERSON); req(portal, true, true, true, View.THIRD_PERSON_BACK);
        req(portal, true, true, true, View.FIRST_PERSON);
        var reEntry = portal.enter(true, View.FIRST_PERSON);
        h.assertTrue(reEntry.request() == null && reEntry.holdEpicFight(), "The new entity's switch is not a new R: first person stays");
        var portalOut = portal.exit(true, true, View.FIRST_PERSON);
        h.assertTrue(portalOut.request() == null && portalOut.holdEpicFight(), "...and leaving keeps first person");

        // Entered from the back view (protected, not owned), then death: leaving keeps the back view.
        var back = new CombatViewRules();
        req(back, true, true, false, View.THIRD_PERSON_BACK); back.enter(true, View.THIRD_PERSON_BACK);
        for (int tick = 0; tick < 3; tick++) req(back, true, true, true, View.THIRD_PERSON_BACK);
        var backOut = back.exit(true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(backOut.request() == null && backOut.holdEpicFight(), "Death in a round entered from behind: the back view stays");

        // Anything else changing the view meanwhile ends ownership: the latest choice is kept, nothing is given back.
        var external = new CombatViewRules();
        req(external, true, true, false, View.FIRST_PERSON); external.enter(true, View.FIRST_PERSON); req(external, true, true, true, View.THIRD_PERSON_BACK);
        req(external, true, true, true, View.THIRD_PERSON_FRONT);
        h.assertTrue(!external.owns(), "Changed elsewhere: no longer owned");
        h.assertTrue(external.exit(true, true, View.THIRD_PERSON_FRONT).request() == null, "...so leaving restores nothing");

        // Leaving the world and joining again, already in combat mode: the old connection's view is never restored.
        var rejoin = new CombatViewRules();
        req(rejoin, true, true, false, View.FIRST_PERSON); rejoin.enter(true, View.FIRST_PERSON); req(rejoin, true, true, true, View.THIRD_PERSON_BACK);
        rejoin.reset();
        h.assertTrue(req(rejoin, true, true, true, View.THIRD_PERSON_BACK) == null && !rejoin.owns() && !rejoin.guarded(),
                "Joined in combat mode: not an entry, nothing owned or protected");
        var rejoinOut = rejoin.exit(true, true, View.THIRD_PERSON_BACK);
        h.assertTrue(rejoinOut.request() == null && !rejoinOut.holdEpicFight(), "Leaving is Epic Fight's: no first person from the old connection");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void waterBoltTakesTheCastersKnownMovement(GameTestHelper h) {
        // Why a rotation-only packet before a release lost the caster's motion: the bolt adds the movement the server keeps
        // for its caster (vanilla Projectile.shootFromRotation), and every move packet sets that movement from the
        // position change it brings, a rotation-only one to zero (ServerGamePacketListenerImpl.handleMovePlayer).
        var level = h.getLevel();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "jr-known-move"), false);
        var caster = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Vec3 at = h.absoluteVec(new Vec3(1.5, 2.0, 1.5));
        caster.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        caster.setKnownMovement(new Vec3(0.0431, 0.0, 0.0011));
        var walking = new WaterBolt(level, caster);
        walking.shootFromRotation(caster, caster.getXRot(), caster.getYRot(), 0.0F, 1.5F, 0.0F);
        caster.setKnownMovement(Vec3.ZERO);
        var standing = new WaterBolt(level, caster);
        standing.shootFromRotation(caster, caster.getXRot(), caster.getYRot(), 0.0F, 1.5F, 0.0F);
        Vec3 added = walking.getDeltaMovement().subtract(standing.getDeltaMovement());
        h.assertTrue(Math.abs(added.x - 0.0431) < 1e-6 && Math.abs(added.y) < 1e-6 && Math.abs(added.z - 0.0011) < 1e-6,
                "The bolt carries the caster's known movement: " + added);
        h.assertTrue(standing.getDeltaMovement().subtract(new Vec3(0, 0, 1.5)).length() < 1e-4,
                "With the movement a rotation-only packet leaves (zero) it flies straight along the look: " + standing.getDeltaMovement());
        // Where it leaves from: the caster's eyes less 0.1, where the server has the caster when it handles the release.
        // A first-person release is handled before this tick's movement packet; one held until after that packet
        // leaves from one tick's movement further on (about 0.043 of a block while charging).
        caster.moveTo(at.x + 0.0431, at.y, at.z + 0.0011, 0.0F, 0.0F);
        var later = new WaterBolt(level, caster);
        Vec3 shift = later.position().subtract(standing.position());
        h.assertTrue(shift.subtract(0.0431, 0.0, 0.0011).length() < 1e-9, "The spawn point moves with the caster's handled position: " + shift);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void staffEffectsFollowTheHandTheStaffIsDrawnIn(GameTestHelper h) {
        h.assertTrue(StaffSide.of(HumanoidArm.RIGHT, false) == 1 && StaffSide.of(HumanoidArm.LEFT, false) == -1, "The vanilla model: the main arm's hand");
        h.assertTrue(StaffSide.of(HumanoidArm.LEFT, true) == 1 && StaffSide.of(HumanoidArm.RIGHT, true) == 1,
                "Epic Fight's own model (its combat mode) draws the staff in the right hand whatever the main arm");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aHeldReleaseGoesOnceForItsOwnUseWithinItsTick(GameTestHelper h) {
        Object player = new Object(), respawned = new Object(), world = new Object(), connection = new Object(), staff = new Object(), other = new Object();
        var use = new HeldRelease.Context(world, connection, staff, "main_hand", 0);
        var hold = new HeldRelease();
        // The ordinary case: held with its use, aimed again, sent once right after the movement packet; the call that sends it passes.
        hold.arm(player);
        h.assertTrue(hold.hold(use), "Vanilla's release in the tick a release was prepared is held");
        h.assertTrue(hold.pendingFor(player) && !hold.pendingFor(respawned), "It is held for that entity only (and aimed again for it before the movement packet)");
        h.assertTrue(hold.hold(use), "A second vanilla release in that tick stays the one held release");
        h.assertTrue(hold.afterMove(player, use, true) == HeldRelease.Outcome.SEND, "It goes right after this tick's movement packet");
        hold.sending(true);
        h.assertFalse(hold.hold(use), "The call that sends it passes and is not held again");
        hold.sending(false);
        h.assertTrue(hold.afterMove(player, use, true) == HeldRelease.Outcome.NONE, "It goes only once (another movement packet)");
        h.assertTrue(hold.endOfTick(player, use, true) == HeldRelease.Outcome.NONE, "It goes only once (the end of the tick)");
        h.assertFalse(hold.hold(use), "Nothing outlives its tick: the next tick's release passes");

        // No movement packet this tick (the player's chunk is not loaded): settled at the tick's end, once.
        hold.arm(player);
        hold.hold(use);
        h.assertTrue(hold.endOfTick(player, use, true) == HeldRelease.Outcome.SEND, "With no movement packet it goes at the tick's end");
        h.assertTrue(hold.endOfTick(player, use, true) == HeldRelease.Outcome.NONE && !hold.hold(use), "...once, and nothing is left");

        // Prepared, but vanilla never released (the use key down again, the use stopped): nothing to settle, nothing left.
        hold.arm(player);
        h.assertTrue(hold.endOfTick(player, use, true) == HeldRelease.Outcome.NONE, "A hold vanilla never used sends nothing");
        h.assertFalse(hold.hold(use), "...and is gone the next tick");

        // The use is gone by the time it would go: dropped, not sent, and nothing is left to go later.
        record Change(String what, HeldRelease.Context now, boolean usable) {}
        for (var change : List.of(
                new Change("the use ended (vanilla stops a use whose item changed; death)", use, false),
                new Change("another item in use", new HeldRelease.Context(world, connection, other, "main_hand", 0), true),
                new Change("another hotbar slot", new HeldRelease.Context(world, connection, staff, "main_hand", 1), true),
                new Change("another hand", new HeldRelease.Context(world, connection, staff, "off_hand", 0), true),
                new Change("another world", new HeldRelease.Context(new Object(), connection, staff, "main_hand", 0), true),
                new Change("another connection", new HeldRelease.Context(world, new Object(), staff, "main_hand", 0), true))) {
            hold.arm(player);
            hold.hold(use);
            h.assertTrue(hold.afterMove(player, change.now(), change.usable()) == HeldRelease.Outcome.DROP, change.what() + ": dropped after the movement packet");
            h.assertTrue(hold.endOfTick(player, use, true) == HeldRelease.Outcome.NONE && !hold.hold(use), change.what() + ": nothing goes later either");
            hold.arm(player);
            hold.hold(use);
            h.assertTrue(hold.endOfTick(player, change.now(), change.usable()) == HeldRelease.Outcome.DROP, change.what() + ": dropped at the end of the tick");
        }

        // Another entity (respawn, change of world): never settled for it, and nothing is left for the old one.
        hold.arm(player);
        hold.hold(use);
        h.assertTrue(hold.afterMove(respawned, use, true) == HeldRelease.Outcome.NONE, "Another entity's movement packet does not settle it");
        h.assertTrue(hold.endOfTick(respawned, use, true) == HeldRelease.Outcome.DROP, "At the end of a tick with another entity it is dropped");
        h.assertTrue(hold.afterMove(player, use, true) == HeldRelease.Outcome.NONE && !hold.hold(use), "Nothing of it reaches a later tick or the old entity");

        // No player at the end of the tick (disconnected): dropped, nothing left.
        hold.arm(player);
        hold.hold(use);
        h.assertTrue(hold.endOfTick(null, null, false) == HeldRelease.Outcome.DROP && !hold.armed() && !hold.pending(), "Disconnected: dropped, nothing left");

        // A new entity or connection clears it at once.
        hold.arm(player);
        hold.hold(use);
        hold.clear();
        h.assertTrue(hold.afterMove(player, use, true) == HeldRelease.Outcome.NONE && hold.endOfTick(player, use, true) == HeldRelease.Outcome.NONE, "Cleared: nothing goes");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aHeldReleaseAimsAgainFromWhereTheEyesAreWhenItsPacketGoes(GameTestHelper h) {
        // One tick of the eyes' movement while charging, from vanilla's own movement code: walking sideways, the first tick of a
        // jump, and the tenth tick of a fall. Kept at the input, the aim would miss the input's world point from the eyes
        // the server has when it handles the release; aimed again from there, it does not.
        String around = surroundings(h);
        clearAboveFloor(h);
        JoblessReincarnation.LOGGER.info("CombatViewMeasure area_before_clearing{}", around);
        ServerPlayer walker = walker(h, false), jumper = walker(h, false), faller = walker(h, false);
        for (int t = 0; t < 25; t++) {
            tickMovement(walker, 1, 0, true);
            tickMovement(jumper, 0, 1, true);
        }
        Vec3 walk = tickMovement(walker, 1, 0, true);
        jumper.jumpFromGround();
        Vec3 jump = tickMovement(jumper, 0, 1, true);
        Vec3 high = h.absoluteVec(new Vec3(4.5, 30.0, 3.5));
        faller.moveTo(high.x, high.y, high.z, 0.0F, 0.0F);
        faller.setOnGround(false);
        Vec3 fall = Vec3.ZERO;
        for (int t = 0; t < 10; t++) fall = tickMovement(faller, 0, 1, true);
        h.assertTrue(Math.abs(walk.horizontalDistance() - 0.0432) < 5e-4 && jump.y > 0.3 && fall.y < -0.4,
                "The eyes' movement in one tick: walk " + walk + ", jump " + jump + ", fall " + fall);
        Vec3 eye = h.absoluteVec(new Vec3(8.0, 2.62, 4.0));
        for (double distance : new double[] {4.0, 10.0}) {
            Vec3 point = eye.add(0.0, 0.0, distance);
            Vec3 camera = eye.add(-1.0, 0.6, -4.5);
            var input = new AimMath.Input(eye, camera, point.subtract(camera).normalize(), point, false);
            var first = input.first();
            for (var motion : List.of(java.util.Map.entry("walk_sideways", walk), java.util.Map.entry("jump", jump), java.util.Map.entry("fall", fall))) {
                Vec3 eyeNow = eye.add(motion.getValue());
                Vec3 toPoint = point.subtract(eyeNow);
                var again = input.from(eyeNow);
                double kept = AimMath.angle(first.yaw(), first.pitch(), AimMath.yaw(toPoint), AimMath.pitch(toPoint));
                double aimedAgain = AimMath.angle(again.yaw(), again.pitch(), AimMath.yaw(toPoint), AimMath.pitch(toPoint));
                JoblessReincarnation.LOGGER.info("CombatViewMeasure reaim motion={} target_blocks={} eyes_moved={} error_if_kept_deg={} error_aimed_again_deg={}",
                        motion.getKey(), fmt(distance), CombatViewTrace.vec(motion.getValue()), fmt(kept), fmt(aimedAgain));
                h.assertTrue(aimedAgain < 1e-3, motion.getKey() + " at " + distance + ": aimed again from the eyes now, the look meets the input's point (" + aimedAgain + " degrees off)");
                h.assertTrue(again.point().equals(point) && again.source() == AimMath.Source.BLOCK, motion.getKey() + ": the same world point, kept from the input");
            }
        }
        // The eyes reach the point (a wall right in front of the face): aimed again it keeps the camera's own direction, as at the input.
        Vec3 near = eye.add(0.0, 0.0, 0.7), camera = eye.add(-1.0, 0.6, -4.5);
        var close = new AimMath.Input(eye, camera, near.subtract(camera).normalize(), near, false);
        h.assertTrue(close.first().source() == AimMath.Source.BLOCK && close.from(eye.add(0.0, 0.0, 0.3)).source() == AimMath.Source.CAMERA,
                "Closer than the minimum distance, the camera's direction is kept: " + close.from(eye.add(0.0, 0.0, 0.3)));
        h.succeed();
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.4f", v); }

    /** What stands in the row the movement tests cross (relative x 3..6, y 1..2, z 4), blocks and entities, for a failure message. */
    private static String surroundings(GameTestHelper h) {
        StringBuilder s = new StringBuilder(" [blocks");
        for (int x = 3; x <= 6; x++) for (int y = 1; y <= 2; y++) {
            var state = h.getBlockState(new BlockPos(x, y, 4));
            if (!state.isAir()) s.append(' ').append(x).append(',').append(y).append(",4=").append(state);
        }
        s.append("; entities");
        for (Entity e : h.getLevel().getEntities((Entity) null, new net.minecraft.world.phys.AABB(h.absoluteVec(new Vec3(0, 0, 0)), h.absoluteVec(new Vec3(16, 6, 16)))))
            s.append(' ').append(e.getType().toShortString()).append('@').append(h.relativeVec(e.position())).append(e.canBeCollidedWith() ? "(solid)" : "");
        return s.append(']').toString();
    }

    /** The movement tests need the space above the floor empty: nothing left over in this test's area from earlier ones. */
    private static void clearAboveFloor(GameTestHelper h) {
        h.killAllEntities();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y < 6; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    /**
     * One tick of vanilla movement as LocalPlayer.aiStep feeds it: the input times 0.2 while using an item, then
     * LivingEntity.aiStep (the 0.003 floor, the speed attribute, the input times 0.98) and its travel. @return the movement
     */
    private static Vec3 tickMovement(ServerPlayer p, float left, float forward, boolean using) {
        if (using) { left *= 0.2F; forward *= 0.2F; }
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(Math.abs(v.x) < 0.003 ? 0.0 : v.x, Math.abs(v.y) < 0.003 ? 0.0 : v.y, Math.abs(v.z) < 0.003 ? 0.0 : v.z);
        p.setSpeed((float) p.getAttributeValue(Attributes.MOVEMENT_SPEED));
        left *= 0.98F;
        forward *= 0.98F;
        Vec3 before = p.position();
        p.travel(new Vec3(left, 0.0, forward));
        return p.position().subtract(before);
    }

    private static ServerPlayer walker(GameTestHelper h, boolean sprinting) {
        var level = h.getLevel();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "jr-walker"), false);
        var p = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Vec3 at = h.absoluteVec(new Vec3(4.5, 1.0, 3.5));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.setSprinting(sprinting);
        return p;
    }

    @GameTest(template = "empty")
    public static void aHeldReleaseKeepsTheChargingMovementOneMoreTick(GameTestHelper h) {
        // Vanilla's own movement code on stone, fed as the client feeds it. Three copies of one charging player: released
        // in first person (vanilla stops the use before this tick's movement), held to after this tick's movement packet
        // (the use stops after it), and, for comparison, only the packet sent late after the client had stopped using.
        String around = surroundings(h);
        clearAboveFloor(h);
        JoblessReincarnation.LOGGER.info("CombatViewMeasure area_before_clearing{}", around);
        record Motion(String name, float left, float forward, boolean sprinting, double perTick) {}
        for (var m : List.of(new Motion("walk", 0, 1, false, 0.0432), new Motion("sprint", 0, 1, true, 0.0561),
                new Motion("strafe", 1, 0, false, 0.0432), new Motion("diagonal", 1, 1, false, 0.0611))) {
            ServerPlayer fp = walker(h, m.sprinting()), held = walker(h, m.sprinting()), late = walker(h, m.sprinting());
            Vec3 previous = Vec3.ZERO, last = Vec3.ZERO, start = fp.position();
            List<Vec3> history = new ArrayList<>();
            for (int t = 0; t < 25; t++) {
                previous = last;
                last = tickMovement(fp, m.left(), m.forward(), true);
                if (t < 4) history.add(last);
                tickMovement(held, m.left(), m.forward(), true);
                tickMovement(late, m.left(), m.forward(), true);
            }
            String state = " (start " + h.relativeVec(start) + ", now " + h.relativeVec(fp.position()) + ", first ticks " + history + ", on ground " + fp.onGround()
                    + ", motion " + fp.getDeltaMovement() + ", collided " + fp.horizontalCollision + "/" + fp.verticalCollision + ", local " + fp.isControlledByLocalInstance()
                    + ", speed " + fp.getSpeed() + ", in wall " + fp.isInWall() + ")" + around + surroundings(h);
            h.assertTrue(last.subtract(previous).length() < 1e-6, m.name() + ": the charging movement has settled: " + previous + " -> " + last + state);
            h.assertTrue(Math.abs(last.horizontalDistance() - m.perTick()) < 5e-4, m.name() + ": charging movement per tick " + last.horizontalDistance() + state);
            // First person: the server keeps the last tick's movement, and the shot leaves from where that packet left the player.
            Vec3 fpKnown = last, fpSpawn = fp.position();
            // Held: this tick still moves as a charger, then its packet goes, then the release.
            Vec3 heldKnown = tickMovement(held, m.left(), m.forward(), true), heldSpawn = held.position();
            // Only the packet late: the client had already stopped using, so this tick is at full speed before the packet.
            Vec3 lateKnown = tickMovement(late, m.left(), m.forward(), false);
            h.assertTrue(heldKnown.subtract(fpKnown).length() < 1e-6, m.name() + ": the held shot carries first person's movement: " + heldKnown + " vs " + fpKnown);
            h.assertTrue(heldSpawn.subtract(fpSpawn).subtract(heldKnown).length() < 1e-9, m.name() + ": and leaves one charging tick further on");
            double lateRatio = lateKnown.horizontalDistance() / fpKnown.horizontalDistance();
            h.assertTrue(lateRatio > 1.8 && lateRatio < 3.5, m.name() + ": a late packet alone would carry " + lateRatio + " times first person's movement");
            // Walking on: first person's release tick is already at full speed; the held player's comes one tick later.
            List<Vec3> fpAfter = new ArrayList<>(), heldAfter = new ArrayList<>();
            fpAfter.add(tickMovement(fp, m.left(), m.forward(), false));
            h.assertTrue(fpAfter.getFirst().subtract(lateKnown).length() < 1e-9, m.name() + ": first person's release tick is that full-speed tick");
            for (int t = 0; t < 12; t++) {
                fpAfter.add(tickMovement(fp, m.left(), m.forward(), false));
                heldAfter.add(tickMovement(held, m.left(), m.forward(), false));
            }
            for (int t = 0; t < 12; t++) h.assertTrue(heldAfter.get(t).subtract(fpAfter.get(t)).length() < 1e-6,
                    m.name() + ": after the held release the player moves as first person did one tick earlier (tick " + t + ")");
            double lag = fp.position().subtract(held.position()).horizontalDistance();
            JoblessReincarnation.LOGGER.info("CombatViewMeasure motion={} charging_per_tick={} fp_known={} held_known={} late_packet_known={} late_ratio={} held_spawn_offset={} full_speed_release_tick={} lag_after_13_ticks={}",
                    m.name(), fmt(last.horizontalDistance()), fmt(fpKnown.horizontalDistance()), fmt(heldKnown.horizontalDistance()), fmt(lateKnown.horizontalDistance()),
                    fmt(lateRatio), fmt(heldSpawn.subtract(fpSpawn).horizontalDistance()), fmt(fpAfter.getFirst().horizontalDistance()), fmt(lag));
            h.assertTrue(lag > 0.1 && lag < 0.3, m.name() + ": the held player ends " + lag + " behind first person");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void earthWallDismissalKeepsItsOwnRangePastTheCrosshairCut(GameTestHelper h) {
        FakePlayer player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "jr-dismiss-reach"));
        player.setPos(h.absoluteVec(new Vec3(5.5, 2, 6.5)));
        player.getAbilities().mayBuild = true;
        player.getAbilities().instabuild = false;
        player.setItemInHand(InteractionHand.MAIN_HAND, ModContent.TRAINING_STAFF.get().getDefaultInstance());
        player.getData(ModAttachments.KNOWLEDGE).learn(EarthWallSpell.SPELL);
        player.getData(ModAttachments.CASTING).select(EarthWallSpell.SPELL);
        player.setData(ModAttachments.MANA, ManaData.full(100));
        for (int x = 4; x <= 6; x++) h.setBlock(new BlockPos(x, 1, 10), Blocks.STONE);
        Vec3 support = h.absoluteVec(new Vec3(5.5, 2, 10.5)), toSupport = support.subtract(player.getEyePosition());
        player.setYRot((float) Math.toDegrees(Math.atan2(-toSupport.x, toSupport.z)));
        player.setXRot((float) -Math.toDegrees(Math.atan2(toSupport.y, Math.hypot(toSupport.x, toSupport.z))));
        EarthWallSpell.Plan build = EarthWallSpell.plan(player);
        h.assertTrue(build.valid() && EarthWallSpell.cast(player), "A real owned earth wall stands: " + build.reason().getString());
        BlockPos cell = build.positions().getFirst();
        // The middle of that cell's face toward the player, and the player's own reach (first person's).
        Vec3 face = new Vec3(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ());
        double blockReach = player.blockInteractionRange(), wallRange = MagicConfig.EARTH_WALL_RANGE.getAsInt();
        h.assertTrue(blockReach < 4.78 && 4.78 < wallRange - 0.2, "The case lies between the player's reach and the earth wall's range: " + blockReach + ", " + wallRange);
        for (double distance : new double[] {4.78, wallRange - 0.1, wallRange + 0.3}) {
            // The eyes this far straight in front of the face; the camera pulled in close by a wall behind the player.
            player.setPos(face.x, face.y - player.getEyeHeight(), face.z - distance);
            Vec3 eye = player.getEyePosition(), camera = eye.add(0.25, 0.2, -0.35);
            Vec3 look = face.subtract(camera).normalize();
            var aim = AimMath.aim(eye, camera, look, face, false);
            player.setYRot(aim.yaw());
            player.setXRot(aim.pitch());
            var crosshair = new BlockHitResult(face, Direction.NORTH, cell, false);
            var verdict = CrosshairReach.judge(crosshair, crosshair, camera, eye, blockReach, player.entityInteractionRange(), 4);
            var plan = EarthWallDismissal.plan(player);
            boolean inRange = distance < wallRange;
            h.assertTrue(verdict == CrosshairReach.Verdict.CUT, "At " + distance + " the vanilla crosshair (outline, use) is cut to the player's reach: " + verdict);
            h.assertTrue(plan.valid() == inRange, "At " + distance + " H " + (inRange ? "selects" : "does not reach") + " the wall: " + plan.reason().getString());
            if (inRange) h.assertTrue(plan.positions().contains(cell), "...the wall under the crosshair, at " + distance);
            else h.assertTrue(plan.reason().getContents() instanceof TranslatableContents key && key.getKey().endsWith(".range"),
                    "...for the earth wall's own range: " + plan.reason().getContents());
        }
        h.succeed();
    }

    /** A real server packet listener whose outgoing packets go nowhere: vanilla's own handlers act on what the test sends it. */
    private static final class QuietListener extends ServerGamePacketListenerImpl {
        private int teleportId = -1, teleports;
        QuietListener(ServerPlayer player, CommonListenerCookie cookie) {
            super(player.server, new Connection(PacketFlow.SERVERBOUND), player, cookie);
        }
        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundPlayerPositionPacket teleport) { teleportId = teleport.getId(); teleports++; }
        }
        @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) { send(packet); }
        /** As the client answers a teleport. */
        void acceptTeleport() { handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleportId)); }
    }

    private record Shot(float yaw, float pitch, Vec3 known, Vec3 spawn, Vec3 velocity, int remaining) {}

    /** What the server shoots right now, as a release does it (WaterMagic: a bolt from the eyes along the look, plus the known movement). */
    private static Shot shoot(ServerPlayer p) {
        var bolt = new WaterBolt(p.serverLevel(), p);
        bolt.shootFromRotation(p, p.getXRot(), p.getYRot(), 0.0F, 1.5F, 0.0F);
        return new Shot(p.getYRot(), p.getXRot(), p.getKnownMovement(), bolt.position(), bolt.getDeltaMovement(), p.getUseItemRemainingTicks());
    }

    @GameTest(template = "empty")
    public static void theServerTakesEachKindOfMovementPacketBeforeAHeldRelease(GameTestHelper h) {
        // A player with a real packet listener, in the level only for the length of this call. The tick before the release
        // moved it one charging tick (a real Pos packet); a first-person release is handled there. A held release is handled
        // after this tick's own packet, whichever kind vanilla chose. The use in progress is never touched by any of them.
        var level = h.getLevel();
        String around = surroundings(h);
        clearAboveFloor(h);
        JoblessReincarnation.LOGGER.info("CombatViewMeasure area_before_clearing{}", around);
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "jr-move-kinds"), false);
        var player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        var server = new QuietListener(player, cookie);
        Vec3 at = h.absoluteVec(new Vec3(4.5, 1.0, 4.5));
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        player.setOnGround(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        level.addNewPlayer(player);
        try {
            player.startUsingItem(InteractionHand.MAIN_HAND);
            h.assertTrue(player.isUsingItem(), "The player is using an item through every packet below");
            Vec3 step = new Vec3(0.0432, 0.0, 0.0);
            server.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(player.getX() + step.x, player.getY(), player.getZ(), true));
            h.assertTrue(player.getKnownMovement().subtract(step).length() < 1e-9, "A real position packet sets the known movement: " + player.getKnownMovement());
            record Case(String kind, Runnable packet, boolean moves, boolean turns, boolean keepsKnown) {}
            var cases = List.of(
                    new Case("pos_rot", () -> server.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(player.getX() + step.x, player.getY(), player.getZ(), 12.5F, -3.0F, true)), true, true, false),
                    new Case("pos", () -> server.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(player.getX() + step.x, player.getY(), player.getZ(), true)), true, false, false),
                    new Case("rot", () -> server.handleMovePlayer(new ServerboundMovePlayerPacket.Rot(-8.0F, 2.0F, true)), false, true, false),
                    new Case("status_only", () -> server.handleMovePlayer(new ServerboundMovePlayerPacket.StatusOnly(false)), false, false, false),
                    new Case("none", () -> {}, false, false, true));
            for (var c : cases) {
                h.assertTrue(server.teleports == 0, c.kind() + ": vanilla has not sent the player back before this case (teleports " + server.teleports + ", at " + h.relativeVec(player.position()) + ")");
                Shot fp = shoot(player);
                c.packet().run();
                h.assertTrue(server.teleports == 0, c.kind() + ": vanilla took the packet without sending the player back (at " + h.relativeVec(player.position())
                        + ", box " + player.getBoundingBox() + ", in wall " + player.isInWall() + ")");
                Shot held = shoot(player);
                Vec3 shift = held.spawn().subtract(fp.spawn());
                boolean turned = held.yaw() != fp.yaw() || held.pitch() != fp.pitch();
                Vec3 expectedKnown = c.keepsKnown() ? fp.known() : c.moves() ? step : Vec3.ZERO;
                JoblessReincarnation.LOGGER.info("CombatViewMeasure packet={} server_look={},{} known={} spawn_shift={} base_speed={} use_remaining_unchanged={}",
                        c.kind(), fmt(held.yaw()), fmt(held.pitch()), CombatViewTrace.vec(held.known()), CombatViewTrace.vec(shift),
                        fmt(held.velocity().subtract(held.known().x, 0, held.known().z).length()), held.remaining() == fp.remaining());
                h.assertTrue(turned == c.turns(), c.kind() + ": the server takes the packet's look exactly when it has one: " + fp.yaw() + "," + fp.pitch() + " -> " + held.yaw() + "," + held.pitch());
                h.assertTrue(held.known().subtract(expectedKnown).length() < 1e-9, c.kind() + ": known movement " + held.known() + ", expected " + expectedKnown);
                h.assertTrue(shift.subtract(c.moves() ? step : Vec3.ZERO).length() < 1e-9, c.kind() + ": the shot leaves from where the packet left the player: " + shift);
                h.assertTrue(held.remaining() == fp.remaining(), c.kind() + ": the server's charge count is not touched by a movement packet");
                h.assertTrue(Math.abs(held.velocity().subtract(held.known().x, 0, held.known().z).length() - 1.5) < 1e-4, c.kind() + ": the rest of the velocity is the shot along the look");
                // The next tick moves one charging tick again, so every case starts as the first did.
                Vec3 from = player.position();
                server.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(player.getX() + step.x, player.getY(), player.getZ(), true));
                h.assertTrue(server.teleports == 0, c.kind() + ": the next tick's packet is taken too (from " + h.relativeVec(from) + " to " + h.relativeVec(player.position())
                        + ", box " + player.getBoundingBox() + ", in wall " + player.isInWall() + ")" + around + surroundings(h));
            }
            // A rider: vanilla sends a rotation packet every tick and the combat view's release keeps one, before the release
            // as before. The server only turns a passenger, and its known movement is the vehicle's.
            var cart = new Minecart(EntityType.MINECART, level);
            cart.setPos(player.getX(), player.getY(), player.getZ());
            cart.setDeltaMovement(0.1, 0.0, 0.0);
            h.assertTrue(player.startRiding(cart, true), "The player rides the cart");
            server.acceptTeleport();
            Shot before = shoot(player);
            server.handleMovePlayer(new ServerboundMovePlayerPacket.Rot(20.0F, 0.0F, false));
            Shot riding = shoot(player);
            JoblessReincarnation.LOGGER.info("CombatViewMeasure packet=rider_rot server_look={},{} known={} spawn_shift={} base_speed={} use_remaining_unchanged={}",
                    fmt(riding.yaw()), fmt(riding.pitch()), CombatViewTrace.vec(riding.known()), CombatViewTrace.vec(riding.spawn().subtract(before.spawn())),
                    fmt(riding.velocity().subtract(riding.known().x, 0, riding.known().z).length()), riding.remaining() == before.remaining());
            h.assertTrue(riding.yaw() == 20.0F && riding.pitch() == 0.0F, "A rider's rotation packet turns it: " + riding.yaw() + "," + riding.pitch());
            h.assertTrue(riding.known().subtract(0.1, 0.0, 0.0).length() < 1e-9 && before.known().subtract(riding.known()).length() < 1e-9,
                    "...and leaves its known movement the vehicle's: " + before.known() + " -> " + riding.known());
            h.assertTrue(riding.spawn().subtract(before.spawn()).length() < 1e-9 && riding.remaining() == before.remaining(), "...from the same place, with the charge count untouched");
            player.stopRiding();
        } finally {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void theServerReleasesFromItsOldPoseUntilItsPlayerTicks(GameTestHelper h) {
        // F01, route A. On the tick a crouching player stands up and releases, the server handles the stand-up command,
        // this tick's movement packet and the release in one batch, before its own player tick changes the pose. So it
        // releases from the crouching eyes at the new position; the client aims again from there, not from its own
        // standing eyes.
        String around = surroundings(h);
        clearAboveFloor(h);
        JoblessReincarnation.LOGGER.info("CombatViewMeasure area_before_clearing{}", around);
        var level = h.getLevel();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "jr-stand-up"), false);
        var player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        var server = new QuietListener(player, cookie);
        Vec3 at = h.absoluteVec(new Vec3(4.5, 1.0, 4.5));
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        player.setOnGround(true);
        level.addNewPlayer(player);
        try {
            // Crouching first, as the server comes to see it: the command, then its own player tick.
            server.handlePlayerCommand(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
            player.doTick();
            h.assertTrue(player.getPose() == Pose.CROUCHING, "The server has the player crouching: " + player.getPose());
            Pose atTickStart = player.getPose();
            // The stand-up tick, one batch: the stand-up command, this tick's movement packet, then the release.
            server.handlePlayerCommand(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            server.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(player.getX() + 0.0432, player.getY(), player.getZ(), true));
            h.assertTrue(server.teleports == 0, "The move is taken");
            Vec3 serverEye = player.getEyePosition();
            h.assertTrue(player.getPose() == Pose.CROUCHING && Math.abs(serverEye.y - player.getY() - 1.27) < 1e-6,
                    "Before its own player tick the server still releases from the crouching eyes: " + player.getPose() + ", " + (serverEye.y - player.getY()));
            // The client has already stood up: its own eyes are at the standing height over the same position.
            Vec3 clientEye = player.position().add(0.0, player.getEyeHeight(Pose.STANDING), 0.0);
            Vec3 modelEye = ReleaseEye.serverEye(player, player.position(), atTickStart, Pose.STANDING);
            h.assertTrue(modelEye.distanceTo(serverEye) < 1e-9, "The eyes the client aims from are the server's: " + modelEye + " vs " + serverEye);
            // The input's world point: ten blocks ahead at standing eye height, picked through a camera behind the player.
            Vec3 point = at.add(0.0, 1.62, 10.0);
            Vec3 camera = point.add(-1.0, 0.6, -14.5);
            var input = new AimMath.Input(at.add(0.0, 1.27, 0.0), camera, point.subtract(camera).normalize(), point, false);
            Vec3 toPoint = point.subtract(serverEye);
            var fromModel = input.from(modelEye);
            var fromClient = input.from(clientEye);
            double fixedDeg = AimMath.angle(fromModel.yaw(), fromModel.pitch(), AimMath.yaw(toPoint), AimMath.pitch(toPoint));
            double oldDeg = AimMath.angle(fromClient.yaw(), fromClient.pitch(), AimMath.yaw(toPoint), AimMath.pitch(toPoint));
            JoblessReincarnation.LOGGER.info("CombatViewMeasure stand_up server_eye_height={} client_eye_height={} from_server_eye_deg={} from_client_eye_deg={}",
                    fmt(serverEye.y - player.getY()), fmt(clientEye.y - player.getY()), fmt(fixedDeg), fmt(oldDeg));
            h.assertTrue(fixedDeg < 1e-3, "Aimed from the server's eyes, the look meets the input's point from where the server releases (" + fixedDeg + " degrees)");
            h.assertTrue(oldDeg > 1.0, "Aimed from the client's new eyes it would not (" + oldDeg + " degrees; about 2 at ten blocks)");
            // Its own player tick then stands it up.
            player.doTick();
            h.assertTrue(player.getPose() == Pose.STANDING && Math.abs(player.getEyePosition().y - player.getY() - 1.62) < 1e-6,
                    "After its player tick the server has the player standing: " + player.getPose());
        } finally {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
        h.succeed();
    }
}
