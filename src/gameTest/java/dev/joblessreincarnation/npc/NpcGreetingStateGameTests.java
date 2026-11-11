package dev.joblessreincarnation.npc;

import dev.joblessreincarnation.JoblessReincarnation;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Clock edge cases without changing the shared world's clock or reusing implementation-derived expected values. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcGreetingStateGameTests {
    @GameTest(template="empty") public static void repeatedRequestsDoNotRenewGestureOrCooldown(GameTestHelper h) {
        var state=new NpcGreetingState();var a=UUID.randomUUID();var b=UUID.randomUUID();
        h.assertTrue(state.start(1000,60,a),"First actor starts the presentation");
        h.assertFalse(state.start(1020,60,b),"Second actor does not take over an active gesture");
        h.assertTrue(state.until()==1060&&a.equals(state.target()),"Rejected request changes neither expiry nor target");
        h.assertTrue(state.advance(1060)&&!state.active(),"Pause has a strict end independent of chat length");
        h.assertFalse(state.start(1199,60,b),"Shared cooldown remains in effect after clip ends");
        h.assertTrue(state.start(1200,60,b),"Rejected spam did not move the cooldown endpoint");h.succeed();
    }
    @GameTest(template="empty") public static void teachingCancellationAndClockRollbackAreBounded(GameTestHelper h) {
        var state=new NpcGreetingState();var actor=UUID.randomUUID();
        state.start(500,64,actor);state.cancel();
        h.assertFalse(state.active(),"Teaching can release the greeting immediately");
        h.assertFalse(state.start(520,64,actor),"Cancellation does not refund the shared cooldown");
        state.advance(450);
        h.assertTrue(state.start(450,64,actor),"Backwards clock clears stale cooldown instead of postponing it forever");
        h.assertTrue(state.advance(400)&&!state.active(),"An active greeting also ends on rollback");h.succeed();
    }
    @GameTest(template="empty") public static void invalidDurationsAndIndependentResidents(GameTestHelper h) {
        var first=new NpcGreetingState();var second=new NpcGreetingState();var actor=UUID.randomUUID();
        h.assertFalse(first.start(0,0,actor)||first.start(0,81,actor)||first.start(Long.MAX_VALUE-5,60,actor),"Invalid timers cannot create an indefinite hold");
        h.assertTrue(first.start(0,56,actor)&&second.start(0,56,actor),"Different residents do not share cooldown state");
        first.forgetTarget();h.assertTrue(first.active()&&first.target()==null,"Lost actor stops tracking, not the finishing pose");
        first.reset();h.assertTrue(first.start(1,56,actor),"Freshly loaded resident has no saved greeting timer");h.succeed();
    }
}
