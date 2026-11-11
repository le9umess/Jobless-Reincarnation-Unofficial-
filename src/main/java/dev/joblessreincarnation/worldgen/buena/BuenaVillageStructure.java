package dev.joblessreincarnation.worldgen.buena;

import com.mojang.serialization.MapCodec;
import dev.joblessreincarnation.worldgen.ModStructures;
import java.util.Optional;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

/** A single reserved start, with ordinary per-chunk structure placement after vegetation. */
public final class BuenaVillageStructure extends Structure {
    public static final MapCodec<BuenaVillageStructure> CODEC=simpleCodec(BuenaVillageStructure::new);
    public BuenaVillageStructure(StructureSettings settings){super(settings);}
    @Override public Optional<GenerationStub> findGenerationPoint(GenerationContext context){
        var handle=BuenaVillageGeneration.active(context.randomState());
        if(handle==null||!handle.plan().anchor().equals(context.chunkPos()))return Optional.empty();
        var plan=handle.plan();var layout=handle.layout();
        return Optional.of(new GenerationStub(plan.centre(),builder->{
            builder.addPiece(BuenaVillagePiece.terrain(BuenaVillagePiece.Step.GROUND,plan,layout));
            for(var part:layout.parts)if(plan.hasPart(part.key()))builder.addPiece(BuenaVillagePiece.template(plan,part,context.structureTemplateManager()));
            builder.addPiece(BuenaVillagePiece.terrain(BuenaVillagePiece.Step.SURFACE,plan,layout));
            for(var person:layout.residents)builder.addPiece(BuenaVillagePiece.resident(plan,layout,person));
            builder.addPiece(BuenaVillagePiece.terrain(BuenaVillagePiece.Step.FINISH,plan,layout));
        }));
    }
    @Override public StructureType<?> type(){return ModStructures.BUENA_VILLAGE.get();}
}
