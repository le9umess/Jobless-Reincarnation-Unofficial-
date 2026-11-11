package dev.joblessreincarnation.gametest;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.NpcEpicGestures;
import dev.joblessreincarnation.compat.epic.NpcGestureVisual;
import dev.joblessreincarnation.npc.NpcAnimations;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression scenarios for accepted events, replacement races, late observers and transient persistence. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcEpicGestureGameTests {
    @GameTest(template="empty")
    public static void lectureCooldownDoesNotRestartPublicGesture(GameTestHelper h) {
        var e=h.spawn(ModContent.ROXY_TUTOR.get(),new BlockPos(4,2,4));
        h.assertTrue(NpcAnimations.playLecture(e),"First real lecture trigger accepted");
        var state=e.getData(ModAttachments.NPC_GESTURE_VISUAL);long sequence=state.sequence();
        h.assertTrue(state.kind()==NpcGestureVisual.Kind.LECTURE && state.duration()==36,"Public clip includes the existing four-tick entry");
        h.assertTrue(!NpcAnimations.playLecture(e),"Existing lecture cooldown retained");
        h.assertTrue(state.sequence()==sequence,"Rejected trigger does not renew the presentation");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void namedGreetingStopCannotEraseReplacementSword(GameTestHelper h) {
        var e=h.spawn(ModContent.PAUL_GREYRAT.get(),new BlockPos(4,2,4));
        NpcEpicGestures.greeting(e);long previous=e.getData(ModAttachments.NPC_GESTURE_VISUAL).sequence();
        h.assertTrue(NpcAnimations.playSwordDemo(e),"Real sword demonstration accepted");
        var state=e.getData(ModAttachments.NPC_GESTURE_VISUAL);long current=state.sequence();
        NpcEpicGestures.stop(e,NpcGestureVisual.Kind.GREETING);
        h.assertTrue(current>previous&&state.sequence()==current&&state.kind()==NpcGestureVisual.Kind.SWORD_DEMO,"Late named greeting stop cannot clear the newer teaching action");
        h.assertTrue(e.isDemonstratingSword()&&state.duration()==38,"Original independent movement pause remains, visual duration is bounded");
        h.succeed();
    }
    @GameTest(template="empty")
    public static void publicGestureDoesNotPersistOrReplaceHome(GameTestHelper h) {
        var e=h.spawn(ModContent.ZENITH_GREYRAT.get(),new BlockPos(4,2,4));e.reanchor();var home=e.getHomeAnchor();
        NpcEpicGestures.greeting(e);CompoundTag saved=new CompoundTag();e.saveWithoutId(saved);
        h.assertTrue(!saved.toString().contains("npc_gesture_visual"),"Public visual state is absent from NBT");
        var restored=ModContent.ZENITH_GREYRAT.get().create(h.getLevel());restored.load(saved);
        h.assertTrue(home!=null&&home.equals(restored.getHomeAnchor()),"Persistent home unaffected by animation");
        h.assertTrue(restored.getData(ModAttachments.NPC_GESTURE_VISUAL).kind()==NpcGestureVisual.Kind.NONE,"Reload starts without historical gesture");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=75)
    public static void initialTrackingCodecKeepsAgeAndVisualExpires(GameTestHelper h) {
        var e=h.spawn(ModContent.ROXY_TUTOR.get(),new BlockPos(4,2,4));NpcAnimations.playLecture(e);
        h.runAfterDelay(10,()->{
            var state=e.getData(ModAttachments.NPC_GESTURE_VISUAL);
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
            try {
                new NpcGestureVisual.Sync().write(buffer,state,true);
                var replica=ModContent.ROXY_TUTOR.get().create(h.getLevel());replica.tickCount=7;
                var decoded=new NpcGestureVisual.Sync().read(replica,buffer,null);
                h.assertTrue(decoded.live()&&decoded.age(0)==state.age(0)&&decoded.age(0)>0,"Late initial sync retains elapsed time rather than restarting at zero");
                replica.tickCount+=40;
                h.assertTrue(!decoded.live(),"An unseen received gesture expires by entity ticks");
            } finally {buffer.release();}
        });
        h.runAfterDelay(39,()->{
            h.assertTrue(e.getData(ModAttachments.NPC_GESTURE_VISUAL).kind()==NpcGestureVisual.Kind.NONE,"Server expires one-shot presentation independently of visibility");
            h.succeed();
        });
    }
    private NpcEpicGestureGameTests() {}
}
