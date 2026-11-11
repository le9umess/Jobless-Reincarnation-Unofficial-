package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.*;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpellFormGameTests {
    private static final class Caster extends FakePlayer {
        Caster(ServerLevel level) { super(level,new GameProfile(UUID.randomUUID(),"FormCast")); }
        void tickUse() { if(isUsingItem())updateUsingItem(getUseItem()); }
    }

    private static Caster caster(GameTestHelper h,SpellCatalog spell,int count) {
        var p=new Caster(h.getLevel());
        p.setPos(h.absoluteVec(new Vec3(5.5,2,1)));
        p.setYRot(0);p.setXRot(0);
        p.getAbilities().instabuild=false;
        p.setItemInHand(InteractionHand.MAIN_HAND,ModContent.TRAINING_STAFF.get().getDefaultInstance());
        var knowledge=p.getData(ModAttachments.KNOWLEDGE);
        knowledge.learn(spell.id());
        for(int i=0;i<count;i++)knowledge.practice(spell.id());
        p.getData(ModAttachments.CASTING).select(spell.id());
        p.setData(ModAttachments.MANA,ManaData.full(100));
        return p;
    }

    private static int stage(Projectile entity) {
        if(entity instanceof WaterBolt water)return water.formStage();
        if(entity instanceof FireBallProjectile fire)return fire.formStage();
        return ((StoneCannonProjectile)entity).formStage();
    }

    @GameTest(template="empty")
    public static void realStaffReleaseUsesTheBeginningStageAndTheNextCastCanAdvance(GameTestHelper h) {
        for(var spell:List.of(SpellCatalog.WATER_BOLT,SpellCatalog.FIRE_BALL,SpellCatalog.STONE_CANNON)) {
            Caster p=caster(h,spell,SpellTraining.practicedAt()-1);
            try {
                for(int expected=0;expected<2;expected++) {
                    p.setData(ModAttachments.MANA,ManaData.full(100));
                    p.getCooldowns().removeCooldown(ModContent.TRAINING_STAFF.get());
                    ModContent.TRAINING_STAFF.get().use(p.level(),p,InteractionHand.MAIN_HAND);
                    var state=p.getData(ModAttachments.CASTING);
                    h.assertTrue(p.isUsingItem() && spell.id().equals(state.activeSpell()),"Native item started the selected spell");
                    h.assertValueEqual(state.activeFormStage(),expected,"Beginning stage snapshot for "+spell);
                    if(expected==0)p.getData(ModAttachments.KNOWLEDGE).practice(spell.id());
                    h.assertValueEqual(SpellTraining.formStage(p,spell),expected,"Focus uses the same snapshot after practice changes");
                    var buf=Unpooled.buffer();
                    try {
                        CastingData.STREAM_CODEC.encode(buf,state);
                        h.assertValueEqual(CastingData.STREAM_CODEC.decode(buf).activeFormStage(),expected,"Owning client's active stage matches");
                    } finally {buf.release();}
                    var saved=CastingData.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow();
                    var restored=CastingData.CODEC.parse(JsonOps.INSTANCE,saved).getOrThrow();
                    h.assertTrue(restored.activeSpell()==null && restored.activeFormStage()==0,"A saved character never resumes a partial chant");
                    for(int tick=0;tick<65;tick++)p.tickUse();
                    p.releaseUsingItem();
                    var shots=h.getLevel().getEntitiesOfClass(Projectile.class,h.getBounds().inflate(4),
                            entity->entity.getOwner()==p);
                    h.assertValueEqual(shots.size(),1,"One true release creates one projectile");
                    h.assertValueEqual(stage(shots.getFirst()),expected,"Release keeps the snapshot after the casting lock is consumed");
                    shots.forEach(Projectile::discard);
                    h.assertTrue(state.activeSpell()==null,"Finished cast clears its transient state");
                }
            } finally {
                h.getLevel().getEntitiesOfClass(Projectile.class,h.getBounds().inflate(4),entity->entity.getOwner()==p).forEach(Projectile::discard);
                p.discard();
            }
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void focusAndImpactOptionsCarryStageThroughNativeParticlePackets(GameTestHelper h) {
        var options=List.of(new FireFocusOptions(.75F,17,2),
                new SpellBurstOptions(SpellBurstOptions.WATER_FOCUS,17,1),
                new SpellBurstOptions(SpellBurstOptions.STONE_IMPACT,17,2,1.5F),
                new SpellBurstOptions(SpellBurstOptions.FIRE_IMPACT));
        for(var option:options) {
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());
            try {
                var packet=new ClientboundLevelParticlesPacket(option,false,1,2,3,.1F,.2F,.3F,1,0);
                ClientboundLevelParticlesPacket.STREAM_CODEC.encode(buffer,packet);
                h.assertValueEqual(ClientboundLevelParticlesPacket.STREAM_CODEC.decode(buffer).getParticle(),option,
                        "The registered packet round trip retains public identity, shape and radius");
            } finally {buffer.release();}
        }
        var oldJson=JsonOps.INSTANCE.createMap(java.util.Map.of(JsonOps.INSTANCE.createString("kind"),JsonOps.INSTANCE.createInt(2)));
        var old=SpellBurstOptions.CODEC.codec().parse(JsonOps.INSTANCE,oldJson).getOrThrow();
        h.assertTrue(old.stage()==0 && old.radius()==0,"Old particle commands default to the original form");
        h.succeed();
    }

    @GameTest(template="empty")
    public static void savedProjectileStageSurvivesAndLegacyDataDefaultsToNovice(GameTestHelper h) {
        for(var type:List.of(ModContent.WATER_BOLT.get(),ModContent.FIRE_BALL.get(),ModContent.STONE_CANNON.get())) {
            var source=(Projectile)type.create(h.getLevel());
            var saved=new CompoundTag();
            try {
                if(source instanceof WaterBolt water)water.setFormStage(2);
                else if(source instanceof FireBallProjectile fire)fire.setFormStage(2);
                else ((StoneCannonProjectile)source).setFormStage(2);
                source.saveWithoutId(saved);
                var restored=(Projectile)type.create(h.getLevel());
                restored.load(saved);
                h.assertValueEqual(stage(restored),2,"Saved shape is independent of a caster loading later");
                restored.discard();
                saved.remove("FormStage");
                var legacy=(Projectile)type.create(h.getLevel());
                legacy.load(saved);
                h.assertValueEqual(stage(legacy),0,"A pre-upgrade projectile retains the novice presentation");
                legacy.discard();
            } finally {source.discard();}
        }
        h.succeed();
    }
}

