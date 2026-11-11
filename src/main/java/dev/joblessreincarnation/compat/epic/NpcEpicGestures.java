package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.npc.*;
import dev.joblessreincarnation.registry.ModAttachments;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Called after existing server interaction/lesson branches accept their real outcome. */
public final class NpcEpicGestures {
    private static final Set<Entity> ACTIVE=Collections.newSetFromMap(new WeakHashMap<>());
    public static void lecture(Entity e) { if(e instanceof RoxyTutorEntity)start(e,NpcGestureVisual.Kind.LECTURE,32); }
    public static void sword(FamilyResidentEntity e) { if(e.member()==FamilyResidentEntity.Member.PAUL)start(e,NpcGestureVisual.Kind.SWORD_DEMO,34); }
    public static void attempt(SylphietteEntity e) { start(e,NpcGestureVisual.Kind.ATTEMPT,SylphietteLessonVisuals.ATTEMPT_TICKS-SylphietteLessonVisuals.TRANSITION_TICKS); }
    public static void greeting(Entity e) {
        int ticks=e instanceof FamilyResidentEntity f?NpcGreetings.holdTicks(f.member())-NpcGreetings.SETTLE_TICKS
                :e instanceof SylphietteEntity?NpcGreetings.SYLPHIETTE_HOLD_TICKS-NpcGreetings.SETTLE_TICKS
                :e instanceof RoxyTutorEntity?NpcGreetings.ROXY_HOLD_TICKS-NpcGreetings.SETTLE_TICKS:0;
        if(ticks>0)start(e,NpcGestureVisual.Kind.GREETING,ticks);
    }
    private static void start(Entity e,NpcGestureVisual.Kind kind,int clipTicks) {
        if(e.level().isClientSide()||!e.isAlive()||e.isRemoved())return;
        e.getData(ModAttachments.NPC_GESTURE_VISUAL).begin(kind,clipTicks);
        ACTIVE.add(e);e.syncData(ModAttachments.NPC_GESTURE_VISUAL);
    }
    public static void stop(Entity e,NpcGestureVisual.Kind expected) {
        if(e.level().isClientSide()||!e.hasData(ModAttachments.NPC_GESTURE_VISUAL))return;
        var v=e.getData(ModAttachments.NPC_GESTURE_VISUAL);
        if(v.kind()!=expected)return; // A late greeting stop must not erase the lesson that replaced it.
        v.clear();ACTIVE.remove(e);e.syncData(ModAttachments.NPC_GESTURE_VISUAL);
    }
    public static void tick(ServerTickEvent.Post event) {
        var it=ACTIVE.iterator();
        while(it.hasNext()) {
            Entity e=it.next();
            var v=e.getData(ModAttachments.NPC_GESTURE_VISUAL);
            if(v.live())continue;
            it.remove();v.clear();if(!e.isRemoved())e.syncData(ModAttachments.NPC_GESTURE_VISUAL);
        }
    }
    public static void stopped(ServerStoppedEvent event) { ACTIVE.clear(); }
    private NpcEpicGestures() {}
}
