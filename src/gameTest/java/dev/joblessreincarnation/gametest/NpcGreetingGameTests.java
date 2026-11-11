package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.magic.MagicKnowledge;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.npc.*;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/** Real level entities and native interaction packets. No rendering, physical keyboard or save/restart claim. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcGreetingGameTests {
    private static final String BATCH="npcGreetingS03";
    private static boolean npcLine(Component c) {
        if(c.getContents() instanceof TranslatableContents t){
            if(t.getKey().startsWith("message.jobless_reincarnation.family.")
                    ||t.getKey().startsWith("message.jobless_reincarnation.sylphiette."))return true;
            for(Object a:t.getArgs())if(a instanceof Component child&&npcLine(child))return true;
        }
        return c.getSiblings().stream().anyMatch(NpcGreetingGameTests::npcLine);
    }
    private static final class Reader extends ServerPlayer {
        final List<Component> replies=new ArrayList<>();
        Reader(ServerLevel level){
            super(level.getServer(),level,new GameProfile(UUID.randomUUID(),"Greet"+UUID.randomUUID().toString().substring(0,8)),ClientInformation.createDefault());
            connection=new ServerGamePacketListenerImpl(server,new Connection(PacketFlow.SERVERBOUND),this,CommonListenerCookie.createInitial(getGameProfile(),false)){
                @Override public void send(Packet<?> p){capture(p);}
                @Override public void send(Packet<?> p,@Nullable PacketSendListener listener){capture(p);}
            };
            setGameMode(GameType.SURVIVAL);setNoGravity(true);
        }
        private void capture(Packet<?> p){if(p instanceof ClientboundSystemChatPacket chat&&npcLine(chat.content()))replies.add(chat.content());}
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final Reader a,b;final List<PathfinderMob> npcs=new ArrayList<>();boolean closed;
        Fixture(GameTestHelper h){
            this.h=h;
            for(int x=1;x<=14;x++)for(int z=1;z<=14;z++){h.setBlock(new BlockPos(x,0,z),Blocks.STONE);for(int y=1;y<=4;y++)h.setBlock(new BlockPos(x,y,z),Blocks.AIR);}
            a=new Reader(h.getLevel());b=new Reader(h.getLevel());
            a.setPos(h.absoluteVec(new Vec3(5.5,1,2.5)));b.setPos(h.absoluteVec(new Vec3(7.5,1,4.5)));
            h.getLevel().addNewPlayer(a);h.getLevel().addNewPlayer(b);
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(a));NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(b));
            a.setData(ModAttachments.KNOWLEDGE,new MagicKnowledge());b.setData(ModAttachments.KNOWLEDGE,new MagicKnowledge());
        }
        FamilyResidentEntity family(FamilyResidentEntity.Member member){
            EntityType<FamilyResidentEntity> type=switch(member){case PAUL->ModContent.PAUL_GREYRAT.get();case ZENITH->ModContent.ZENITH_GREYRAT.get();case LILIA->ModContent.LILIA_GREYRAT.get();};
            return spawn(type.create(h.getLevel()));
        }
        SylphietteEntity sylph(){return spawn(ModContent.SYLPHIETTE.get().create(h.getLevel()));}
        <T extends PathfinderMob>T spawn(T npc){
            if(npc==null)throw new IllegalStateException("Missing NPC factory");
            Vec3 position=h.absoluteVec(new Vec3(5.5,1,4.5));
            npc.moveTo(position.x,position.y,position.z,180,0);npcs.add(npc);
            h.assertTrue(h.getLevel().addFreshEntity(npc),"NPC is indexed in the live level");
            h.assertFalse(npc.isNoAi(),"Navigation remains enabled for pause checks");return npc;
        }
        void talk(Reader player,PathfinderMob npc){talk(player,npc,InteractionHand.MAIN_HAND);}
        void talk(Reader player,PathfinderMob npc,InteractionHand hand){player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(npc,false,hand,new Vec3(0,1,0)));}
        void later(int ticks,Runnable step){h.runAfterDelay(ticks,()->checked(step));}
        void checked(Runnable step){if(closed)return;try{step.run();}catch(RuntimeException|Error e){close();throw e;}}
        void done(){close();h.succeed();}
        @Override public void close(){
            if(closed)return;closed=true;npcs.forEach(Entity::discard);
            for(Reader p:List.of(a,b)){p.getAdvancements().stopListening();NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(p));p.discard();
                removeOwn(h.getLevel().getServer().getPlayerList(),"advancements",p.getUUID(),p.getAdvancements());removeOwn(h.getLevel().getServer().getPlayerList(),"stats",p.getUUID(),p.getStats());}
        }
    }
    private static void removeOwn(PlayerList list,String name,UUID id,Object expected){
        try{Field f=PlayerList.class.getDeclaredField(name);f.setAccessible(true);((Map<?,?>)f.get(list)).remove(id,expected);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private static void learnWater(Reader p){MagicKnowledge k=new MagicKnowledge();k.learn(WaterMagic.SPELL);p.setData(ModAttachments.KNOWLEDGE,k);}

    @GameTest(template="empty",batch=BATCH) public static void allFourNormalConversationsStartGreetings(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            for(var member:FamilyResidentEntity.Member.values()){
                var npc=f.family(member);f.talk(f.a,npc);h.assertTrue(npc.isGreeting()&&npc.greetingPlayer()==f.a,"Ordinary family reply starts its own greeting: "+member);npc.discard();
            }
            var sylph=f.sylph();f.talk(f.a,sylph);h.assertTrue(sylph.isGreeting()&&sylph.greetingPlayer()==f.a,"Unlearned visitor receives normal Sylphiette greeting");
            h.assertFalse(SylphietteFirstLesson.hasActiveSession(sylph),"Greeting did not invent a lesson");
            h.assertTrue(f.a.replies.size()==4&&f.b.replies.isEmpty(),"Only the actor receives the four original lines");h.succeed();
        }
    }
    @GameTest(template="empty",batch=BATCH) public static void anotherSpeakerCannotRestartOrStealGreeting(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            var npc=f.family(FamilyResidentEntity.Member.ZENITH);f.talk(f.a,npc);long end=npc.greetingUntil();float yaw=npc.getYRot();
            f.talk(f.a,npc);f.talk(f.b,npc);
            h.assertTrue(npc.greetingUntil()==end&&npc.greetingPlayer()==f.a,"Repeated and peer clicks retain deadline and original actor");
            h.assertTrue(Math.abs(Mth.wrapDegrees(npc.getYRot()-yaw))<0.001,"Peer reply causes no immediate facing jump");
            h.assertTrue(f.a.replies.size()==1&&f.b.replies.size()==1,"Shared animation cooldown does not suppress a different person's private reply");
            h.assertTrue(npc.conversationPlayer()==f.b,"Original conversation bookkeeping remains independent");h.succeed();
        }
    }
    @GameTest(template="empty",batch=BATCH) public static void sameCharacterInstancesHaveIndependentCooldowns(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            var first=f.family(FamilyResidentEntity.Member.ZENITH);var second=f.family(FamilyResidentEntity.Member.ZENITH);
            second.setPos(first.position().add(1.0,0,0));f.talk(f.a,first);f.talk(f.a,second);
            h.assertTrue(first.isGreeting()&&second.isGreeting(),"Cooldown belongs to each NPC, not its member type or the player");h.succeed();
        }
    }
    @GameTest(template="empty",batch=BATCH,timeoutTicks=240) public static void realTimeCooldownAllowsChatBeforeAnotherGreeting(GameTestHelper h){
        Fixture f=new Fixture(h);f.checked(()->{
            var npc=f.family(FamilyResidentEntity.Member.PAUL);f.talk(f.a,npc);
            f.later(70,()->{f.b.setPos(npc.position().add(0,0,-2));f.talk(f.b,npc);h.assertFalse(npc.isGreeting(),"Valid chat during the 200-tick cooldown does not replay");h.assertTrue(f.b.replies.size()==1,"Private text still works");});
            f.later(205,()->{f.b.setPos(npc.position().add(0,0,-2));f.talk(f.b,npc);h.assertTrue(npc.isGreeting()&&npc.greetingPlayer()==f.b,"Cooldown ends based on the first accepted trigger, not later requests");f.done();});
        });
    }
    @GameTest(template="empty",batch=BATCH,timeoutTicks=90) public static void liliaFinishesAfterActorLogoutAndConversationExpiry(GameTestHelper h){
        Fixture f=new Fixture(h);f.checked(()->{
            var npc=f.family(FamilyResidentEntity.Member.LILIA);f.talk(f.a,npc);Vec3 start=npc.position();
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(f.a));f.a.discard();
            h.assertTrue(npc.isGreeting()&&npc.greetingPlayer()==null,"Lost actor does not cut the bow or remain a look target");
            f.later(45,()->{h.assertTrue(npc.isGreeting()&&npc.isMovementPaused(),"Greeting outlives the 40-tick conversation");h.assertTrue(npc.position().subtract(start).horizontalDistanceSqr()<0.0025,"She remains at her standing spot");});
            f.later(65,()->{h.assertFalse(npc.isGreeting()||npc.isMovementPaused(),"The extra pause has a finite end");f.done();});
        });
    }
    @GameTest(template="empty",batch=BATCH,timeoutTicks=90) public static void sylphietteFinishesWithoutChasingDistantActor(GameTestHelper h){
        Fixture f=new Fixture(h);f.checked(()->{
            var npc=f.sylph();f.talk(f.a,npc);Vec3 start=npc.position();f.a.setPos(h.absoluteVec(new Vec3(13.5,1,13.5)));
            h.assertTrue(npc.isGreeting()&&npc.greetingPlayer()==null,"Walking away ends target tracking but keeps the short finish");
            f.later(45,()->{h.assertTrue(npc.isMovementPaused(),"She does not stroll before the clip can finish");h.assertTrue(npc.position().subtract(start).horizontalDistanceSqr()<0.0025,"No chase while greeting finishes");});
            f.later(72,()->{h.assertFalse(npc.isGreeting()||npc.isMovementPaused(),"The hold cannot survive its deadline");f.done();});
        });
    }
    @GameTest(template="empty",batch=BATCH,timeoutTicks=80) public static void swordLessonPreemptsGreetingAndKeepsItsActor(GameTestHelper h){
        Fixture f=new Fixture(h);f.checked(()->{
            var npc=f.family(FamilyResidentEntity.Member.PAUL);f.talk(f.a,npc);f.b.setItemInHand(InteractionHand.MAIN_HAND,ModContent.PRACTICE_WOODEN_SWORD.get().getDefaultInstance());f.talk(f.b,npc);
            h.assertFalse(npc.isGreeting(),"Teaching cancels the greeting immediately");h.assertTrue(npc.isDemonstratingSword()&&f.b.getData(ModAttachments.SWORD_TRAINING).started(),"Existing lesson and demo still begin");
            f.later(21,()->{float yaw=npc.getYRot();f.talk(f.a,npc);h.assertTrue(Math.abs(Mth.wrapDegrees(npc.getYRot()-yaw))<0.001,"Ordinary talk cannot pull an active demonstration away");
                f.later(2,()->{h.assertTrue(Math.abs(Mth.wrapDegrees(npc.getYRot()-yaw))<2,"Pause goal also keeps the demo target on later ticks");h.assertFalse(npc.isGreeting(),"Busy demo cannot start a greeting");f.done();});});
        });
    }
    @GameTest(template="empty",batch=BATCH,timeoutTicks=80) public static void observationPreemptsGreetingAndReservesSylphiette(GameTestHelper h){
        Fixture f=new Fixture(h);f.checked(()->{
            var npc=f.sylph();f.talk(f.a,npc);learnWater(f.b);f.talk(f.b,npc);
            h.assertFalse(npc.isGreeting(),"A valid invitation takes over the greeting");h.assertTrue(SylphietteFirstLesson.observingPlayer(npc)==f.b,"The observer owns the reservation");
            f.later(21,()->{float yaw=npc.getYRot();f.talk(f.a,npc);h.assertFalse(npc.isGreeting(),"A failed new observation does not mean the NPC is free");h.assertTrue(Math.abs(Mth.wrapDegrees(npc.getYRot()-yaw))<0.001,"An unrelated ordinary reply does not pull her away");
                h.assertFalse(WaterMagic.isLearned(f.a)||StoryAdvancements.hasCompleted(f.b,StoryAdvancements.SYLPHIETTE_FIRST_LESSON),"Greeting/chat grants neither a spell nor lesson completion");f.done();});
        });
    }
    @GameTest(template="empty",batch=BATCH,timeoutTicks=90) public static void attemptCannotBeTurnedByAnotherConversation(GameTestHelper h){
        Fixture f=new Fixture(h);f.checked(()->{
            var npc=f.sylph();learnWater(f.a);f.talk(f.a,npc);
            // Stage the existing admitted-cast hook for this state/priority test; not a claim of a paid cast.
            SylphietteFirstLesson.onWaterCast(f.a);
            f.later(SylphietteFirstLesson.ATTEMPT_DELAY_TICKS+3,()->{float yaw=npc.getYRot();f.talk(f.b,npc);
                h.assertTrue(Math.abs(Mth.wrapDegrees(npc.getYRot()-yaw))<0.001,"No one-tick facing theft during the actual attempt phase");
                h.assertFalse(npc.isGreeting(),"Attempt owns presentation even when this actor cannot start a new lesson");
                f.later(2,()->{h.assertTrue(Math.abs(Mth.wrapDegrees(npc.getYRot()-yaw))<2,"Lesson remains the look target");f.done();});});
        });
    }
    @GameTest(template="empty",batch=BATCH) public static void greetingStateDoesNotSurviveNpcReloadOrRemoval(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            var npc=f.family(FamilyResidentEntity.Member.LILIA);f.talk(f.a,npc);CompoundTag saved=new CompoundTag();npc.addAdditionalSaveData(saved);
            h.assertTrue(saved.getAllKeys().stream().noneMatch(k->k.toLowerCase(Locale.ROOT).contains("greet")),"No greeting fields are persisted");
            npc.discard();h.assertFalse(npc.isGreeting(),"Removed instance releases its runtime state");
            var restored=f.family(FamilyResidentEntity.Member.LILIA);restored.readAdditionalSaveData(saved);
            h.assertFalse(restored.isGreeting(),"Restored entity does not replay or stand for an old greeting");f.talk(f.b,restored);
            h.assertTrue(restored.isGreeting(),"No saved cooldown locks a fresh entity");
            var sylph=f.sylph();f.talk(f.a,sylph);CompoundTag s=new CompoundTag();sylph.addAdditionalSaveData(s);sylph.readAdditionalSaveData(s);
            h.assertFalse(sylph.isGreeting(),"Sylphiette also drops transient presentation when reloaded");h.succeed();
        }
    }
    @GameTest(template="empty",batch=BATCH) public static void invalidInputDoesNotStartOrSpendGreeting(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            var npc=f.family(FamilyResidentEntity.Member.ZENITH);
            f.talk(f.a,npc,InteractionHand.OFF_HAND);h.assertFalse(npc.isGreeting(),"Offhand is consumed without greeting");
            f.a.setGameMode(GameType.SPECTATOR);f.talk(f.a,npc);h.assertFalse(npc.isGreeting(),"Spectator does not greet");
            f.a.setGameMode(GameType.SURVIVAL);f.a.setPos(h.absoluteVec(new Vec3(13.5,1,13.5)));f.talk(f.a,npc);h.assertFalse(npc.isGreeting(),"Distant packet cannot greet");
            f.a.setPos(npc.position().add(0,0,-2));f.talk(f.a,npc);h.assertTrue(npc.isGreeting(),"Invalid attempts did not consume the cooldown");h.succeed();
        }
    }
    @GameTest(template="empty",batch=BATCH) public static void ordinaryGreetingLeavesGameplayAttachmentsAndHeldItemAlone(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            var npc=f.family(FamilyResidentEntity.Member.PAUL);f.a.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STICK,3));
            f.a.getData(ModAttachments.MANA);f.a.getData(ModAttachments.KNOWLEDGE);f.a.getData(ModAttachments.SWORD_TRAINING);
            CompoundTag before=f.a.saveWithoutId(new CompoundTag()).getCompound("neoforge:attachments").copy();ItemStack item=f.a.getMainHandItem().copy();
            f.talk(f.a,npc);h.assertTrue(npc.isGreeting(),"Fixture did enter the greeting path");
            h.assertTrue(before.equals(f.a.saveWithoutId(new CompoundTag()).getCompound("neoforge:attachments")),"Greeting does not modify personal gameplay attachments");
            h.assertTrue(ItemStack.matches(item,f.a.getMainHandItem()),"Held item is unchanged");h.succeed();
        }
    }
    private NpcGreetingGameTests(){}
}
