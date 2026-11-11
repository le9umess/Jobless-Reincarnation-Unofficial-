package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/** Observes the real item hook, real native particle packets and actual sound dispatch. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FireFocusGameTests {
    private static final class Reader extends FakePlayer {
        final List<ClientboundLevelParticlesPacket> particles = new ArrayList<>();
        final List<SpellBurstOptions> bursts = new ArrayList<>();
        Reader(ServerLevel level) {
            super(level,new GameProfile(UUID.randomUUID(),"Focus"+UUID.randomUUID().toString().substring(0,8)));
            connection=new ServerGamePacketListenerImpl(level.getServer(),new Connection(PacketFlow.SERVERBOUND),this,
                    CommonListenerCookie.createInitial(getGameProfile(),false)) {
                @Override public void send(Packet<?> packet) { record(packet); }
                @Override public void send(Packet<?> packet,@Nullable PacketSendListener listener) { record(packet); }
            };
        }
        void record(Packet<?> p) {if(p instanceof ClientboundLevelParticlesPacket particle&&particle.getParticle() instanceof FireFocusOptions)particles.add(particle);
            if(p instanceof ClientboundLevelParticlesPacket particle&&particle.getParticle() instanceof SpellBurstOptions burst)bursts.add(burst);}
        void tickUse(){if(isUsingItem())updateUsingItem(getUseItem());}
    }
    private static final class Fixture implements AutoCloseable {
        final Reader p;
        int cues;
        final Consumer<PlayLevelSoundEvent.AtPosition> sound;
        Fixture(GameTestHelper h) {
            p=new Reader(h.getLevel());p.setPos(h.absoluteVec(new Vec3(5.5,2,1)));p.setYRot(0);p.setYHeadRot(0);p.setXRot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND,ModContent.TRAINING_STAFF.get().getDefaultInstance());
            p.getData(ModAttachments.KNOWLEDGE).learn(FireMagic.SPELL);p.getData(ModAttachments.CASTING).select(FireMagic.SPELL);
            p.setData(ModAttachments.MANA,ManaData.full(100));p.getAbilities().instabuild=false;
            h.getLevel().addNewPlayer(p);
            sound=e->{if(e.getLevel()==p.level()&&e.getSound()!=null&&e.getSound().value()==SoundEvents.FLINTANDSTEEL_USE
                    &&e.getPosition().distanceToSqr(p.position())<9)cues++;};
            NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class,sound);
        }
        void begin(){ModContent.TRAINING_STAFF.get().use(p.level(),p,InteractionHand.MAIN_HAND);}
        void ticks(int n){for(int i=0;i<n;i++)p.tickUse();}
        void free(GameTestHelper h){h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),100,"Presentation never spends mana");
            h.assertValueEqual(p.getData(ModAttachments.MANA).cooldown(),0,"Presentation never starts cooldown");
            h.assertValueEqual(FireMagic.practiceCount(p),0,"Presentation never grants practice");
            h.assertEntityNotPresent(ModContent.FIRE_BALL.get());}
        @Override public void close(){NeoForge.EVENT_BUS.unregister(sound);SpellCasting.cancel(p);p.discard();}
    }
    @GameTest(template="empty")
    public static void realUseEmitsGrowingNativeParticlesAndOnlyOneReadyCue(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            f.begin();int chant=FireMagic.chantTicks(f.p);f.ticks(chant-1);
            h.assertTrue(!f.p.particles.isEmpty(),"Native server particle delivery reached the caster");
            h.assertTrue(f.p.particles.stream().allMatch(p->((FireFocusOptions)p.getParticle()).progress()<1),"No ready visual before the boundary");
            h.assertTrue(f.p.particles.stream().allMatch(p->((FireFocusOptions)p.getParticle()).caster()==f.p.getId()),"The chant names its caster");
            h.assertValueEqual(f.cues,0,"No early ready sound");f.p.tickUse();
            h.assertValueEqual(f.cues,1,"Exactly one cue on the completed chant tick");
            h.assertValueEqual(((FireFocusOptions)f.p.particles.getLast().getParticle()).progress(),1F,"Ready visual matches chant boundary");
            f.ticks(90);h.assertValueEqual(f.cues,1,"Long hold never loops sound");
            FireFocusEffects.onUseTick(f.p,chant-1);FireFocusEffects.onUseTick(f.p,chant-1);
            h.assertValueEqual(f.cues,1,"Repeated external boundary callbacks cannot repeat cue");
            for(var p:f.p.particles){h.assertValueEqual(p.getCount(),1,"One bounded particle per emission");h.assertTrue(p.isOverrideLimiter(),"Essential focus reaches minimal particles; decorative children remain limited");}
            f.free(h);
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void essentialVisualsKeepTheNormalDeliveryRadius(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            Vec3 point=FireFocusEffects.visibleFocus(f.p).orElseThrow();
            Reader near=new Reader(h.getLevel()),far=new Reader(h.getLevel());
            near.setPos(point.add(30,0,0));far.setPos(point.add(34,0,0));
            h.getLevel().addNewPlayer(near);h.getLevel().addNewPlayer(far);
            try{
                f.begin();f.ticks(FireMagic.chantTicks(f.p));
                h.assertTrue(!near.particles.isEmpty(),"Nearby viewer receives essential native focus");
                h.assertValueEqual(near.particles.size(),f.p.particles.size(),"One shared cue per nearby viewer");
                h.assertTrue(near.particles.stream().allMatch(packet->packet.isOverrideLimiter()),"Nearby visual passes client particle preferences");
                h.assertTrue(far.particles.isEmpty(),"Force never expands delivery to the native 512 block radius");
                for(int kind:List.of(SpellBurstOptions.WATER_FOCUS,SpellBurstOptions.STONE_FOCUS,SpellBurstOptions.WATER_IMPACT,SpellBurstOptions.FIRE_IMPACT,SpellBurstOptions.STONE_IMPACT))
                    AttackSpellVisuals.send(h.getLevel(),new SpellBurstOptions(kind,f.p.getId(),2,2),point.x,point.y,point.z,0,0,0,1,1);
                h.assertValueEqual(near.bursts.size(),5,"Every attack visual takes the bounded native route");
                h.assertTrue(far.bursts.isEmpty(),"Burst delivery also keeps the original distance limit");
                f.free(h);
            }finally{near.discard();far.discard();}
        }h.succeed();
    }
    /** Two casters can stand close enough for their points to nearly meet, so the stone chant names its caster too; impacts have nobody to name. */
    @GameTest(template="empty")
    public static void stoneChantNamesItsCasterAndImpactsNameNobody(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            f.p.getData(ModAttachments.KNOWLEDGE).learn(StoneCannonSpell.SPELL);f.p.getData(ModAttachments.CASTING).select(StoneCannonSpell.SPELL);
            f.begin();f.ticks(4);
            h.assertTrue(!f.p.bursts.isEmpty(),"The real use path sent the stone chant");
            for(var burst:f.p.bursts){h.assertValueEqual(burst.kind(),SpellBurstOptions.STONE_FOCUS,"Only the chant was sent");h.assertValueEqual(burst.caster(),f.p.getId(),"The chant names its caster");}
            for(var option:List.of(new SpellBurstOptions(SpellBurstOptions.STONE_FOCUS,7),new SpellBurstOptions(SpellBurstOptions.FIRE_IMPACT))){
                var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
                try{ClientboundLevelParticlesPacket.STREAM_CODEC.encode(buffer,new ClientboundLevelParticlesPacket(option,false,1,2,3,0,0,0,0,1));
                    h.assertValueEqual(ClientboundLevelParticlesPacket.STREAM_CODEC.decode(buffer).getParticle(),option,"Native packet round trip");
                }finally{buffer.release();}
            }
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void cancelStopsEmissionAndNewUseRearmsReadyCue(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            f.begin();f.ticks(FireMagic.chantTicks(f.p));int count=f.p.particles.size();
            SpellCasting.openJournal(f.p);FireFocusEffects.onUseTick(f.p,99);
            h.assertValueEqual(f.p.particles.size(),count,"Closed journal cancels the native use lock");f.free(h);
            f.begin();f.ticks(FireMagic.chantTicks(f.p));h.assertValueEqual(f.cues,2,"New cast earns one new ready cue");
            f.p.setShiftKeyDown(true);count=f.p.particles.size();f.ticks(12);
            h.assertValueEqual(f.p.particles.size(),count,"Sneaking suspends presentation");f.p.releaseUsingItem();f.free(h);
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void wrongSpellSlotAndUnknownFireNeverEmit(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            f.p.getData(ModAttachments.KNOWLEDGE).learn(WaterMagic.SPELL);f.p.getData(ModAttachments.CASTING).select(WaterMagic.SPELL);f.begin();f.ticks(30);
            h.assertTrue(f.p.particles.isEmpty(),"Water has no fire focus");SpellCasting.cancel(f.p);
            f.p.getData(ModAttachments.CASTING).select(FireMagic.SPELL);f.begin();f.p.getInventory().selected=1;
            f.p.setItemInHand(InteractionHand.MAIN_HAND,ModContent.TRAINING_STAFF.get().getDefaultInstance());FireFocusEffects.onUseTick(f.p,30);
            h.assertTrue(f.p.particles.isEmpty(),"Changing slot invalidates old focus");SpellCasting.cancel(f.p);
            f.begin();f.p.setData(ModAttachments.KNOWLEDGE,new MagicKnowledge());FireFocusEffects.onUseTick(f.p,30);
            h.assertTrue(f.p.particles.isEmpty(),"Removed knowledge cannot retain focus");h.assertValueEqual(f.cues,0,"No cue for invalid use");f.free(h);
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void solidAndWaterOcclusionSuppressEffectsWithoutWorldWrites(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            Vec3 focus=FireFocusEffects.visibleFocus(f.p).orElseThrow();BlockPos block=BlockPos.containing(focus);
            var level=h.getLevel();level.setBlockAndUpdate(block,Blocks.TNT.defaultBlockState());f.begin();f.ticks(30);
            h.assertTrue(f.p.particles.isEmpty(),"Blocked focus is not emitted through TNT");h.assertTrue(level.getBlockState(block).is(Blocks.TNT),"No TNT callback");
            level.setBlockAndUpdate(block,Blocks.WATER.defaultBlockState());f.ticks(12);
            h.assertTrue(f.p.particles.isEmpty(),"Submerged focus suppressed");h.assertValueEqual(f.cues,0,"Obstructed focus makes no ready sound");
            level.setBlockAndUpdate(block,Blocks.AIR.defaultBlockState());f.ticks(6);
            h.assertFalse(f.p.particles.isEmpty(),"Clear space allows feedback again");h.assertValueEqual(f.cues,1,"First visible ready cue can follow occlusion");f.free(h);
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void shallowWaterHandednessPitchAndUnloadedAreaAreHandled(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            Vec3 right=FireFocusEffects.visibleFocus(f.p).orElseThrow();f.p.setMainArm(HumanoidArm.LEFT);
            Vec3 left=FireFocusEffects.visibleFocus(f.p).orElseThrow();h.assertTrue(right.x<f.p.getX()&&left.x>f.p.getX(),"Handedness mirrors focus side");
            BlockPos pos=BlockPos.containing(left);h.getLevel().setBlockAndUpdate(pos,Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,7));
            h.assertTrue(FireFocusEffects.visibleFocus(f.p).isPresent(),"Focus above a shallow actual water surface remains visible");
            h.getLevel().setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());f.p.setXRot(-90);
            Vec3 up=FireFocusEffects.visibleFocus(f.p).orElseThrow();h.assertTrue(up.y>f.p.getEyeY(),"Vertical aim stays finite and above eye");
            BlockPos remote=new BlockPos(-20000000,80,-20000000);h.assertFalse(h.getLevel().hasChunkAt(remote),"Remote initially unloaded");
            Reader detached=new Reader(h.getLevel());detached.setPos(Vec3.atCenterOf(remote));
            h.assertFalse(h.getLevel().hasChunkAt(remote),"Detached fixture did not preload remote chunk");
            h.assertTrue(FireFocusEffects.visibleFocus(detached).isEmpty(),"Unloaded area has no cue");
            h.assertFalse(h.getLevel().hasChunkAt(remote),"No chunk was forced by visibility test");
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void nativeParticlePacketCodecClampsMalformedAppearance(GameTestHelper h){
        for(float value:new float[]{-4,0.4F,1,9,Float.NaN,Float.POSITIVE_INFINITY}){
            var option=new FireFocusOptions(value,7);var packet=new ClientboundLevelParticlesPacket(option,false,1,2,3,0,0,0,0,1);
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
            try{ClientboundLevelParticlesPacket.STREAM_CODEC.encode(buffer,packet);var decoded=ClientboundLevelParticlesPacket.STREAM_CODEC.decode(buffer);
                h.assertTrue(decoded.getParticle() instanceof FireFocusOptions,"Vanilla registry particle dispatch resolves mod type");
                float progress=((FireFocusOptions)decoded.getParticle()).progress();h.assertTrue(Float.isFinite(progress)&&progress>=0&&progress<=1,"Malformed values cannot reach unbounded geometry");
                h.assertValueEqual(progress,option.progress(),"Native packet round trip preserves sanitized progress");
                h.assertValueEqual(((FireFocusOptions)decoded.getParticle()).caster(),7,"Native packet round trip preserves the caster");
                h.assertFalse(decoded.isOverrideLimiter(),"Particle settings still honored");
            }finally{buffer.release();}
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void masteryBoundaryAndCueLatchAreNotSavedOrSynced(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            for(int i=0;i<60;i++)f.p.getData(ModAttachments.KNOWLEDGE).practice(FireMagic.SPELL);
            f.begin();String before=CastingData.CODEC.encodeStart(JsonOps.INSTANCE,f.p.getData(ModAttachments.CASTING)).getOrThrow().toString();
            var beforeWire=Unpooled.buffer();
            byte[] beforeBytes;
            try {
                CastingData.STREAM_CODEC.encode(beforeWire,f.p.getData(ModAttachments.CASTING));
                beforeBytes=io.netty.buffer.ByteBufUtil.getBytes(beforeWire);
            } finally { beforeWire.release(); }
            f.ticks(FireMagic.chantTicks(f.p)-1);h.assertValueEqual(f.cues,0,"No early expert cue");f.p.tickUse();
            h.assertValueEqual(f.cues,1,"Expert ready boundary respected");
            String after=CastingData.CODEC.encodeStart(JsonOps.INSTANCE,f.p.getData(ModAttachments.CASTING)).getOrThrow().toString();
            h.assertValueEqual(after,before,"Cue latch never enters saved casting data");
            var buffer=Unpooled.buffer();try{CastingData.STREAM_CODEC.encode(buffer,f.p.getData(ModAttachments.CASTING));
                h.assertTrue(java.util.Arrays.equals(beforeBytes, io.netty.buffer.ByteBufUtil.getBytes(buffer)), "Firing the cue changes no wire bytes");
                CastingData decoded=CastingData.STREAM_CODEC.decode(buffer);
                h.assertValueEqual(decoded.selectedSpell(),FireMagic.SPELL,"Owner still receives selection");
                h.assertValueEqual(decoded.activeSpell(),FireMagic.SPELL,"Owner still receives the active spell");
                h.assertValueEqual(buffer.readableBytes(),0,"Protocol 26 casting snapshot consumed exactly once");
            }finally{buffer.release();}
            h.assertValueEqual(FireMagic.practiceCount(f.p),60,"Presentation adds no expert practice");
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void nearbyObserverReceivesOnlyPublicNativeAppearance(GameTestHelper h){
        try(Fixture f=new Fixture(h)){
            Reader other=new Reader(h.getLevel());other.setPos(h.absoluteVec(new Vec3(7.5,2,4)));h.getLevel().addNewPlayer(other);
            try{boolean knowledge=other.hasData(ModAttachments.KNOWLEDGE),mana=other.hasData(ModAttachments.MANA),casting=other.hasData(ModAttachments.CASTING);
                f.begin();f.ticks(FireMagic.chantTicks(f.p));h.assertFalse(other.particles.isEmpty(),"Nearby observer receives normal particle packets");
                h.assertValueEqual(other.hasData(ModAttachments.KNOWLEDGE),knowledge,"No observer knowledge mutation");
                h.assertValueEqual(other.hasData(ModAttachments.MANA),mana,"No observer mana mutation");
                h.assertValueEqual(other.hasData(ModAttachments.CASTING),casting,"No observer casting mutation");f.free(h);
            }finally{other.discard();}
        }h.succeed();
    }
    private FireFocusGameTests(){}
}
