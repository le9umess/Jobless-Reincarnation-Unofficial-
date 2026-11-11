package dev.joblessreincarnation.worldgen.buena;

import com.mojang.serialization.MapCodec;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.*;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import dev.joblessreincarnation.worldgen.ModStructures;

/** Stops late neighbouring decoration from planting through the village, without changing any other settlement. */
public final class BuenaVegetation implements BiomeModifier {
    public static final MapCodec<BuenaVegetation> CODEC=MapCodec.unit(BuenaVegetation::new);
    private final Map<PlacedFeature,Holder<PlacedFeature>> wrapped=new IdentityHashMap<>();
    @Override public void modify(Holder<Biome> biome,Phase phase,ModifiableBiomeInfo.BiomeInfo.Builder builder){
        if(phase!=Phase.AFTER_EVERYTHING)return;
        var features=builder.getGenerationSettings().getFeatures(GenerationStep.Decoration.VEGETAL_DECORATION);
        for(int i=0;i<features.size();i++){
            var original=features.get(i).value();
            if(original.placement().contains(Clearance.INSTANCE))continue;
            features.set(i,wrapped.computeIfAbsent(original,feature->{
                var modifiers=new ArrayList<>(feature.placement());modifiers.add(Clearance.INSTANCE);
                return Holder.direct(new PlacedFeature(feature.feature(),List.copyOf(modifiers)));
            }));
        }
    }
    @Override public MapCodec<? extends BiomeModifier> codec(){return CODEC;}
    public static final class Clearance extends PlacementFilter {
        public static final Clearance INSTANCE=new Clearance();
        public static final MapCodec<Clearance> CODEC=MapCodec.unit(INSTANCE);
        @Override protected boolean shouldPlace(PlacementContext context,RandomSource random,BlockPos pos){
            var level=context.getLevel().getLevel();if(level.dimension()!=Level.OVERWORLD)return true;
            var active=BuenaVillageGeneration.active(level.getChunkSource().randomState());
            if(active==null)return true;
            return permits(active.plan(),pos.getX(),pos.getZ());
        }
        /** The margin also covers tree crowns and random-patch offsets from neighbouring chunks. */
        public static boolean permits(BuenaVillagePlan plan,int worldX,int worldZ){
            int x=worldX-plan.originX,z=worldZ-plan.originZ;
            return x<16||x>191||z< -16||z>191;
        }
        @Override public PlacementModifierType<?> type(){return ModStructures.BUENA_CLEARANCE.get();}
    }
}
