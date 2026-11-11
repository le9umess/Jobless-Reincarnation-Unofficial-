package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.TutorUseState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server-loadable state contract; raw input, binding and lifecycle wiring are exercised by the native probe. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TutorUseStateGameTests {
    @GameTest(template="empty")
    public static void onePressConsultsOnceAcrossLongHoldAndOffhand(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "First valid main-hand tutor interaction proceeds");
        for (int tick = 0; tick < 120; tick++) {
            helper.assertTrue(state.interact(true, true, true), "Holding cannot repeat consultation");
            helper.assertTrue(state.interact(true, false, true), "The same action cannot fall through to offhand");
        }
        helper.assertTrue(state.claimed(), "The consultation owns the entire hold");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void onlyReleaseThenFreshEligiblePressAllowsAnotherConsultation(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "First press succeeds");
        state.release();
        helper.assertTrue(state.interact(true, true, true), "Release alone cannot authorize a queued use");
        helper.assertTrue(state.interact(false, false, true), "Release alone cannot leak consumed hold into offhand");
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "A second physical press allows one new consultation");
        helper.assertTrue(state.interact(true, true, true), "The second press is also single use");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void duplicatePressCallbacksCannotRefillConsumedPermission(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "Duplicated press still permits the initial consultation");
        for (int repeat = 0; repeat < 40; repeat++) {
            state.press(true);
            helper.assertTrue(state.interact(true, true, true), "A repeated press callback without release cannot refill permission");
        }
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void ordinaryContinuousUseAndOffhandRemainAvailable(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        for (int tick = 0; tick < 120; tick++) {
            helper.assertFalse(state.interact(false, true, true), "Ordinary continuous main-hand use is unchanged");
            helper.assertFalse(state.interact(false, false, true), "Ordinary offhand use remains available");
        }
        helper.assertFalse(state.hasPermit(), "Ordinary first use spends the tutor permission for this press");
        helper.assertFalse(state.claimed(), "Ordinary use does not claim the hold");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void draggingOrdinaryUseOntoTutorRequiresAnotherPress(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        helper.assertFalse(state.interact(false, true, true), "Initial ordinary use proceeds");
        helper.assertTrue(state.interact(true, true, true), "Dragging a held use onto a tutor cannot consult");
        helper.assertFalse(state.claimed(), "Passing over a tutor does not claim an already ordinary gesture");
        helper.assertFalse(state.interact(false, true, true), "Moving back off the tutor resumes ordinary continuous use");
        helper.assertFalse(state.interact(false, false, true), "Ordinary offhand fallback also remains available");
        helper.assertTrue(state.interact(true, true, true), "Passing over the tutor again still cannot consult");
        state.release();
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "Releasing and pressing on the tutor succeeds");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void consumedTutorHoldCannotEscapeThroughAimOrItemChanges(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "Initial consultation succeeds");
        // Target/item identities are deliberately absent: none is allowed to create a new press.
        helper.assertTrue(state.interact(false, true, true), "Looking at air, a block, food or a book stays blocked");
        helper.assertTrue(state.interact(false, false, true), "Changing to a usable offhand stays blocked");
        helper.assertTrue(state.interact(true, true, true), "Changing to another tutor stays blocked");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void offhandCannotSpendFreshTutorPermission(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        helper.assertTrue(state.interact(true, false, true), "A tutor consultation must start from main hand");
        helper.assertTrue(state.interact(true, true, true), "A rejected offhand cannot preserve a permit for a later callback");
        helper.assertTrue(state.interact(false, false, true), "Rejected tutor hold remains consumed");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void ineligiblePressCannotBecomeValidByDuplicateCallback(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(false);
        state.press(true);
        helper.assertFalse(state.hasPermit(), "A press begun outside valid gameplay cannot become valid while held");
        helper.assertTrue(state.interact(true, true, true), "Later eligibility alone cannot authorize consultation");
        state.release();
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "A new eligible press recovers normally");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void lifecycleInvalidationRevokesUnusedPermission(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        helper.assertTrue(state.hasPermit(), "Fresh press begins with a permit");
        state.invalidate();
        helper.assertFalse(state.hasPermit(), "GUI, focus, binding or player/world invalidation revokes the permit");
        helper.assertTrue(state.interact(true, true, true), "The queued use cannot consult after a lifecycle boundary");
        helper.assertTrue(state.interact(false, true, true), "The rejected consultation cannot fall through to an item");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void lifecycleInvalidationPreservesConsumedHold(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "Initial consultation succeeds");
        state.invalidate();
        state.release();
        helper.assertTrue(state.claimed(), "Context changes and synthetic releases preserve the consumed hold");
        helper.assertTrue(state.interact(false, true, true), "GUI close, focus return or a new player cannot leak a stale hold");
        helper.assertTrue(state.interact(true, true, true), "No stale consultation after the boundary");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void lostReleaseDuringContextChangeDoesNotPermanentlyLockInput(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "Initial consultation succeeds");
        state.invalidate();
        // A focus loss/rebinding may swallow RELEASE. A genuinely new eligible raw PRESS must recover.
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "A new raw press after context invalidation recovers without an old release");
        helper.assertTrue(state.interact(true, true, true), "The recovered press still allows only one consultation");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void logicalUseWithoutRawPressCannotConsult(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        helper.assertFalse(state.interact(false, true, true), "Unrelated normal use is not globally disabled");
        helper.assertTrue(state.interact(true, true, true), "KeyMapping clicks alone cannot manufacture a tutor permission");
        helper.assertTrue(state.interact(false, true, true), "The stale tutor hold cannot fall through afterward");
        state.release();
        state.press(true);
        helper.assertFalse(state.interact(true, true, true), "Real new input unlocks the tutor");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void shortPhysicalClickMaySettleAfterItsRelease(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        state.release();
        helper.assertFalse(state.interact(true, true, true), "A real quick click is not lost when vanilla processes it after RELEASE");
        helper.assertTrue(state.interact(true, true, true), "A short click still has only one consultation");
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void eligibilityLostBeforeDispatchCannotUseExistingPermission(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        helper.assertTrue(state.interact(true, true, false), "A valid initial press cannot authorize an ineligible dispatch");
        helper.assertTrue(state.interact(true, true, true), "Later eligibility does not revive the rejected interaction");
        helper.assertTrue(state.interact(false, false, true), "Rejected dispatch consumes the complete hold");
        helper.succeed();
    }
    @GameTest(template="empty")
    public static void ordinaryGestureClassificationCannotSurviveContextInvalidation(GameTestHelper helper) {
        TutorUseState state = new TutorUseState();
        state.press(true);
        helper.assertFalse(state.interact(false, true, true), "Valid press starts an ordinary gesture");
        helper.assertTrue(state.interact(true, true, true), "Passing over tutor blocks that consultation");
        helper.assertFalse(state.claimed(), "An ordinary gesture can initially move back to normal use");
        state.invalidate();
        helper.assertTrue(state.interact(true, true, true), "A lifecycle boundary makes the remaining tutor-directed hold stale");
        helper.assertTrue(state.claimed(), "The old ordinary classification cannot bypass stale-hold protection");
        helper.assertTrue(state.interact(false, true, true), "Rejected stale tutor input cannot spill back into item use");
        state.press(true);
        helper.assertFalse(state.interact(false, true, true), "A genuine new eligible press recovers ordinary behavior");
        helper.succeed();
    }
    private TutorUseStateGameTests() {}
}
