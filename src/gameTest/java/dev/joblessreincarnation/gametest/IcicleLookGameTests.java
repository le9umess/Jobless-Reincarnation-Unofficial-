package dev.joblessreincarnation.client;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.IcicleFieldEntity;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Tests the common-world sampling helper, not GPU output or actual client visibility. */
@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public class IcicleLookGameTests {
    @GameTest(template="empty",batch="icicleLookF01")
    public static void firstSampleAndWaterIceWaterHeightsAreRefreshed(GameTestHelper h){
        for(int x=6;x<=10;x++)for(int z=6;z<=10;z++)h.setBlock(new BlockPos(x,1,z),Blocks.WATER);
        var p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"IceLook"));
        var field=new IcicleFieldEntity(h.getLevel(),p,h.absoluteVec(new Vec3(8.5,2,8.5)));
        var look=IcicleFieldLook.of(field);
        h.assertTrue(look.refreshed!=Long.MIN_VALUE,"first refresh must not subtract the MIN sentinel and overflow");
        int picked=-1;
        for(int i=0;i<look.stamps.size();i++)if(look.seen[i]&&look.ground[i]<field.getY()-.05){picked=i;break;}
        h.assertTrue(picked>=0,"actual water-top sample is visible on first lookup");
        var stamp=look.stamps.get(picked);BlockPos cell=BlockPos.containing(stamp.x(),field.getY()-1,stamp.z());
        h.getLevel().setBlockAndUpdate(cell,Blocks.FROSTED_ICE.defaultBlockState());look.refreshed=h.getLevel().getGameTime()-4;
        look=IcicleFieldLook.of(field);h.assertTrue(Math.abs(look.ground[picked]-field.getY())<1e-6,"decal rises to full ice surface");
        h.getLevel().setBlockAndUpdate(cell,Blocks.WATER.defaultBlockState());look.refreshed=h.getLevel().getGameTime()-4;
        look=IcicleFieldLook.of(field);h.assertTrue(look.ground[picked]<field.getY()-.05,"decal follows water after melting");
        h.succeed();
    }
}
