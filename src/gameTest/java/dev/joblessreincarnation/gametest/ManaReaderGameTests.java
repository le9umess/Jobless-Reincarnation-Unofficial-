package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.ManaData;
import dev.joblessreincarnation.npc.RoxyTutorEntity;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ManaReaderGameTests {
    private static final InteractionHand MAIN = InteractionHand.MAIN_HAND;
    private static final String PREFIX = "message.jobless_reincarnation.mana_reader.";

    private static final class Reader extends FakePlayer {
        final List<TranslatableContents> notices = new ArrayList<>();
        boolean spectator;
        Reader(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "Crystal" + UUID.randomUUID().toString().substring(0, 6)));
        }
        @Override public boolean isSpectator() { return spectator; }
        @Override public void displayClientMessage(Component msg, boolean overlay) {
            if (overlay && msg.getContents() instanceof TranslatableContents text) notices.add(text);
        }
    }

    private static Reader player(GameTestHelper h, double z) {
        Reader p = new Reader(h.getLevel());
        p.setPos(h.absoluteVec(new Vec3(4.5, 2, z)));
        h.getLevel().addNewPlayer(p);
        p.setItemInHand(MAIN, ModContent.MANA_READER.get().getDefaultInstance());
        p.setYRot(0);
        p.setXRot(0);
        return p;
    }

    private static Mob mob(GameTestHelper h, EntityType<? extends Mob> type, double z) {
        Mob n = type.create(h.getLevel());
        if (n == null) throw new IllegalStateException(type.toString());
        n.setNoAi(true);
        n.setPos(h.absoluteVec(new Vec3(4.5, 2, z)));
        h.getLevel().addFreshEntity(n);
        return n;
    }

    private static void look(Reader p, Entity target) {
        Vec3 ray = target.getBoundingBox().getCenter().subtract(p.getEyePosition());
        p.setYRot((float) -Math.toDegrees(Math.atan2(ray.x, ray.z)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(ray.y, Math.hypot(ray.x, ray.z))));
    }

    private static TranslatableContents notice(GameTestHelper h, Reader p, String name) {
        h.assertValueEqual(p.notices.size(), 1, "One private action bar reading");
        var msg = p.notices.getFirst();
        h.assertValueEqual(msg.getKey(), PREFIX + name, "Correct reading type");
        return msg;
    }
    private static void reset(Reader p) {
        p.notices.clear();
        p.getCooldowns().removeCooldown(ModContent.MANA_READER.get());
    }
    private static void on(GameTestHelper h, Reader p, Entity target, boolean specific) {
        if (specific) {
            var e = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteractSpecific(p, MAIN, target, Vec3.ZERO));
            h.assertTrue(e.isCanceled() && e.getCancellationResult().consumesAction(), "Specific target consumed exactly here");
        } else {
            var e = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(p, MAIN, target));
            h.assertTrue(e.isCanceled() && e.getCancellationResult().consumesAction(), "Fallback target consumed exactly here");
        }
    }

    @GameTest(template = "empty")
    public static void manaReaderSelfSnapshotAndCooldown(GameTestHelper h) {
        var p=player(h,2.5);
        try {
            p.setData(ModAttachments.MANA,new ManaData(17,113,50,8));
            ModContent.MANA_READER.get().use(h.getLevel(),p,MAIN);
            var a=notice(h,p,"self").getArgs();
            h.assertTrue(a[0].equals(17)&&a[1].equals(113),"Actual server balance and capacity");
            ModContent.MANA_READER.get().use(h.getLevel(),p,MAIN);
            h.assertValueEqual(p.notices.size(),1,"Held key or duplicate request respects cooldown");
            h.assertValueEqual(p.getData(ModAttachments.MANA).mana(),17,"Measuring spends no mana");
            h.assertValueEqual(p.getData(ModAttachments.MANA).cooldown(),8,"Spell cooldown untouched");
        } finally {p.discard();}
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void manaReaderPlayerAndCrouchedSelfStayPrivate(GameTestHelper h) {
        var p=player(h,2.5);var other=player(h,4.7);
        try {
            p.setData(ModAttachments.MANA,new ManaData(18,101,0,0));
            other.setData(ModAttachments.MANA,new ManaData(37,145,0,0));
            look(p,other);on(h,p,other,true);
            var a=notice(h,p,"player").getArgs();
            h.assertTrue(a.length==3&&a[1].equals(37)&&a[2].equals(145),"Read the other player, not the caster");
            h.assertTrue(other.notices.isEmpty(),"Target receives no reading");
            reset(p);p.setShiftKeyDown(true);on(h,p,other,false);
            var own=notice(h,p,"self").getArgs();
            h.assertTrue(own[0].equals(18)&&own[1].equals(101),"Sneak overrides target");
        } finally {p.discard();other.discard();}
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void manaReaderAllFiveStoryResidentsOnlyExposeCapacity(GameTestHelper h) {
        var p=player(h,2.5);
        try {
            var types=List.of(ModContent.ROXY_TUTOR.get(),ModContent.ZENITH_GREYRAT.get(),ModContent.SYLPHIETTE.get(),ModContent.LILIA_GREYRAT.get(),ModContent.PAUL_GREYRAT.get());
            var caps=List.of(180,120,60,40,30);
            for(int i=0;i<types.size();i++){
                var target=mob(h,types.get(i),4.6);
                try {
                    reset(p);look(p,target);on(h,p,target,true);on(h,p,target,false);
                    var args=notice(h,p,"npc").getArgs();
                    h.assertTrue(args.length==2&&args[1].equals(caps.get(i)),"Only the named NPC and its capacity");
                    h.assertFalse(target.hasData(ModAttachments.MANA),"Reading creates no NPC mana pool");
                    if(target instanceof RoxyTutorEntity tutor) h.assertFalse(tutor.isConversing(),"Measuring never starts a lesson");
                } finally {target.discard();}
            }
        } finally {p.discard();}
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void manaReaderRejectsOccludedAndUnpointedSubjects(GameTestHelper h) {
        var p=player(h,2.5);var other=player(h,4.9);
        try {
            other.setData(ModAttachments.MANA,new ManaData(23,150,0,0));
            p.setYRot(180);on(h,p,other,false);notice(h,p,"unreadable");
            reset(p);look(p,other);var block=BlockPos.containing(p.getEyePosition().add(0,0,1));
            var original=h.getLevel().getBlockState(block);h.getLevel().setBlockAndUpdate(block,Blocks.STONE.defaultBlockState());
            try {on(h,p,other,true);notice(h,p,"unreadable");} finally {h.getLevel().setBlockAndUpdate(block,original);}
            reset(p);other.setPos(other.position().add(0,0,7));look(p,other);on(h,p,other,true);notice(h,p,"unreadable");
        } finally {p.discard();other.discard();}
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void manaReaderUnknownTargetStopsRayAndDoesNotAllocate(GameTestHelper h) {
        var p=player(h,2.5);var cow=mob(h,EntityType.COW,4.0);var other=player(h,5.1);
        try {
            look(p,cow);on(h,p,cow,true);notice(h,p,"unreadable");h.assertFalse(cow.hasData(ModAttachments.MANA),"No synthetic mana for animals");
            reset(p);on(h,p,other,true);notice(h,p,"unreadable");
            h.assertTrue(other.notices.isEmpty(),"No target read through intervening mob");
        } finally {p.discard();cow.discard();other.discard();}
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void manaReaderOffhandAndSpectatorCannotTakeReadings(GameTestHelper h) {
        var p=player(h,2.5);
        try {
            p.setItemInHand(InteractionHand.OFF_HAND,p.getMainHandItem().copy());
            p.setItemInHand(MAIN,ItemStack.EMPTY);
            h.assertFalse(ModContent.MANA_READER.get().use(h.getLevel(),p,InteractionHand.OFF_HAND).getResult().consumesAction(),"Reader is a main-hand tool");
            h.assertTrue(p.notices.isEmpty(),"No offhand reading");
            p.setItemInHand(MAIN,ModContent.MANA_READER.get().getDefaultInstance());p.spectator=true;
            ModContent.MANA_READER.get().use(h.getLevel(),p,MAIN);h.assertTrue(p.notices.isEmpty(),"Spectator cannot inspect");
        } finally {p.discard();}
        h.succeed();
    }
}
