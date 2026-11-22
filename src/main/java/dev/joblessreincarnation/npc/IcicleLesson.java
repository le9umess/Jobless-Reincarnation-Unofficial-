package dev.joblessreincarnation.npc;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.*;
import dev.joblessreincarnation.training.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;

/** Roxy teaches; ice and a wet dummy prove learning. No client packet can advance this course. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID)
public final class IcicleLesson {
    private record Demonstration(RoxyTutorEntity teacher,long end){}
    private static final Map<ServerPlayer,Demonstration> DEMOS=new IdentityHashMap<>();
    public static IcicleCourse progress(ServerPlayer p){return p.hasData(ModAttachments.ICICLE_COURSE)?p.getData(ModAttachments.ICICLE_COURSE):IcicleCourse.NEW;}
    public static boolean qualified(ServerPlayer p){return progress(p).complete()&&SpellCatalog.ICICLE_FIELD.knows(p);}
    private static void tell(ServerPlayer p,String key){p.sendSystemMessage(Component.translatable("message.jobless_reincarnation.icicle.lesson."+key));}
    public static boolean interact(ServerPlayer p,RoxyTutorEntity teacher){
        boolean request=p.isShiftKeyDown()&&p.getMainHandItem().is(ModContent.TRAINING_STAFF.get());
        boolean selected=p.getMainHandItem().is(ModContent.TRAINING_STAFF.get())&&SpellCatalog.selected(p)==SpellCatalog.ICICLE_FIELD;
        var data=progress(p);
        if(!request&&!selected&&!(p.getMainHandItem().is(ModContent.TRAINING_STAFF.get())&&data.stage()==3))return false;
        if(data.stage()==0){
            if(!SpellCatalog.WATER_BOLT.knows(p)){tell(p,"water_first");return true;}
            if(DEMOS.containsKey(p)){tell(p,"watch");return true;}
            if(!request){tell(p,"start_hint");return true;}
            Vec3 base=Vec3.atBottomCenterOf(teacher.blockPosition().offset(2,0,0));
            var field=IcicleFieldEntity.demonstration(p.serverLevel(),teacher,base);
            DEMOS.put(p,new Demonstration(teacher,p.serverLevel().getGameTime()+40));
            if(!p.serverLevel().addFreshEntity(field)||field.isRemoved()){DEMOS.remove(p);field.discard();tell(p,"retry");return true;}
            NpcAnimations.playLecture(teacher);IcicleVisuals.cast(p.serverLevel(),base);tell(p,"watch");return true;
        }
        if(data.stage()==3){p.setData(ModAttachments.ICICLE_COURSE,new IcicleCourse(4,6));tell(p,"complete");}
        else tell(p,data.stage()==1?"freeze":data.stage()==2?"combo":"done");
        return true;
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        for(var entry:new ArrayList<>(DEMOS.entrySet())){
            var p=entry.getKey();if(p.getServer()!=event.getServer())continue;var demo=entry.getValue();var t=demo.teacher;
            if(!p.isAlive()||p.isRemoved()||p.isSpectator()||!t.isAlive()||t.isRemoved()||p.level()!=t.level()||p.distanceToSqr(t)>64
                ||p.containerMenu!=p.inventoryMenu||!p.getMainHandItem().is(ModContent.TRAINING_STAFF.get())||p.serverLevel().getGameTime()>demo.end+20){DEMOS.remove(p);continue;}
            t.beginConversation(p);
            if(p.serverLevel().getGameTime()<demo.end)continue;
            DEMOS.remove(p);
            if(progress(p).stage()!=0||!SpellCatalog.WATER_BOLT.knows(p))continue;
            p.getData(ModAttachments.KNOWLEDGE).learn(IcicleSpell.SPELL);p.syncData(ModAttachments.KNOWLEDGE);
            p.setData(ModAttachments.ICICLE_COURSE,new IcicleCourse(1,0));SpellCatalog.normalizeSelection(p);tell(p,"learned");
        }
    }
    public static void frozen(ServerPlayer p,int actualNewIce){var data=progress(p);if(p.getAbilities().instabuild||data.stage()!=1)return;int count=Math.max(data.frozen(),Math.min(6,actualNewIce));p.setData(ModAttachments.ICICLE_COURSE,new IcicleCourse(count>=6?2:1,count));if(count>=6)tell(p,"combo");}
    public static void nova(ServerPlayer p,PracticeDummyEntity dummy){if(p.getAbilities().instabuild||progress(p).stage()!=2||!p.getUUID().equals(dummy.ownerId())||!dummy.hasSupport()||!IcicleWet.contact(dummy,p.getUUID()))return;p.setData(ModAttachments.ICICLE_COURSE,new IcicleCourse(3,6));tell(p,"return");}
    public static CourseRecordSnapshot snapshot(ServerPlayer p){var d=progress(p);return new CourseRecordSnapshot(CourseRecordSnapshot.Course.ICICLE_FIELD,d.stage(),d.frozen(),6,d.stage()>=3?1:0,1,d.stage()>=1,d.complete());}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){DEMOS.remove(e.getEntity());}
    @SubscribeEvent public static void stop(ServerStoppedEvent e){DEMOS.keySet().removeIf(p->p.getServer()==e.getServer());}
    private IcicleLesson(){}
}
