package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.compat.epic.*;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.*;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/** Item lifecycle regressions; fake players do not stand in for the later real network/visual acceptance. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EpicSpellCastGameTests {
    private static final class Caster extends FakePlayer {
        Caster(ServerLevel l){super(l,new GameProfile(UUID.randomUUID(),"EpicCastTest"));}
        void tickUse(){if(isUsingItem())updateUsingItem(getUseItem());}
    }
    private static Caster caster(GameTestHelper h,SpellCatalog spell){
        var p=new Caster(h.getLevel());p.setPos(h.absoluteVec(new Vec3(4,2,4)));p.setXRot(-60);p.setYRot(0);
        p.getAbilities().instabuild=false;p.setItemInHand(InteractionHand.MAIN_HAND,ModContent.TRAINING_STAFF.get().getDefaultInstance());
        p.getData(ModAttachments.KNOWLEDGE).learn(spell.id());p.getData(ModAttachments.CASTING).select(spell.id());
        p.setData(ModAttachments.MANA,ManaData.full(100));
        h.assertTrue(EpicFightCapabilities.getServerPlayerPatch(p)!=null,"Actual server patch attaches to lifecycle fixture");return p;
    }
    private static void sync(Caster p){EpicCastController.onTick(new PlayerTickEvent.Post(p));}
    private static void start(Caster p){ModContent.TRAINING_STAFF.get().use(p.level(),p,InteractionHand.MAIN_HAND);sync(p);}
    @GameTest(template="empty")
    public static void eachExistingSpellHasAnIsolatedFreeEntryAndCancellation(GameTestHelper h){
        for(var spell:SpellCatalog.values()){
            var p=caster(h,spell);var peer=caster(h,spell);start(p);
            h.assertTrue(EpicCastController.hasActiveVisual(p)&&p.getData(ModAttachments.EPIC_WATER_PHASE)==EpicCastAnimations.phase(spell,false),"Accepted item use exposes the selected spell's entry: "+spell);
            for(int t=0;t<10;t++)p.tickUse();sync(p);
            h.assertTrue(p.getData(ModAttachments.EPIC_WATER_PHASE)==EpicCastAnimations.phase(spell,true),"Hold has the same spell identity: "+spell);
            h.assertTrue(peer.getData(ModAttachments.EPIC_WATER_PHASE)==0&&!EpicCastController.hasActiveVisual(peer),"Nearby player's visual remains independent");
            SpellCasting.cancel(p);sync(p);
            h.assertTrue(!EpicCastController.hasActiveVisual(p)&&p.getData(ModAttachments.EPIC_WATER_PHASE)==0,"Cancellation clears the public pose");
            h.assertTrue(p.getData(ModAttachments.MANA).mana()==100&&spell.practiceCount(p)==0,"Animation and cancellation never spend or practice");
        }h.succeed();
    }
    @GameTest(template="empty")
    public static void visualTracksCastLockRatherThanLaterSelectionAndNeverSaves(GameTestHelper h){
        var p=caster(h,SpellCatalog.FIRE_BALL);p.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_BOLT.id());start(p);
        p.getData(ModAttachments.CASTING).select(SpellCatalog.WATER_BOLT.id());sync(p);
        h.assertTrue(p.getData(ModAttachments.EPIC_WATER_PHASE)==EpicCastAnimations.phase(SpellCatalog.FIRE_BALL,false),"An already locked fire cast is not repainted as water by selection data");
        var saved=p.saveWithoutId(new CompoundTag());
        h.assertTrue(!saved.toString().contains("epic_water_phase"),"Visual pair is never written to player NBT");
        var restored=caster(h,SpellCatalog.WATER_BOLT);restored.load(saved);
        h.assertTrue(restored.getData(ModAttachments.EPIC_WATER_PHASE)==0&&!EpicCastController.hasActiveVisual(restored),"Reload does not inherit a historical cast");
        SpellCasting.cancel(p);sync(p);h.succeed();
    }
    @GameTest(template="empty")
    public static void rejectedGroundSpellEndsPoseWithoutManaOrRelease(GameTestHelper h){
        var p=caster(h,SpellCatalog.MIRE);start(p);for(int t=0;t<SpellCatalog.MIRE.chantTicks(p)+2;t++)p.tickUse();sync(p);
        h.assertTrue(!SpellCasting.release(p,p.getMainHandItem(),SpellCatalog.MIRE.chantTicks(p)+2),"Looking at sky has no valid mire ground");
        h.assertTrue(!EpicCastController.hasActiveVisual(p)&&p.getData(ModAttachments.EPIC_WATER_PHASE)==0,"Failed target cannot keep the held pose");
        h.assertTrue(p.getData(ModAttachments.MANA).mana()==100&&SpellCatalog.MIRE.practiceCount(p)==0,"Rejected placement is free");
        var patch=EpicFightCapabilities.getServerPlayerPatch(p);
        h.assertTrue(!EpicCastAnimations.same(patch.getAnimator().getPlayerFor(null).getRealAnimation(),EpicCastAnimations.clips(SpellCatalog.MIRE).release()),"Failed result never plays successful release");
        p.stopUsingItem();h.succeed();
    }
    private EpicSpellCastGameTests(){}
}
