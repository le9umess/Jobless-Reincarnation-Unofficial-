package dev.joblessreincarnation.worldgen;

import dev.joblessreincarnation.JoblessReincarnation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import dev.joblessreincarnation.worldgen.buena.BuenaVillageStructure;
import dev.joblessreincarnation.worldgen.buena.BuenaVillagePiece;
import dev.joblessreincarnation.worldgen.buena.BuenaVegetation;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModStructures {
    private static final DeferredRegister<StructureType<?>> TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, JoblessReincarnation.MOD_ID);
    private static final DeferredRegister<StructurePieceType> PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE, JoblessReincarnation.MOD_ID);
    private static final DeferredRegister<PlacementModifierType<?>> PLACEMENT = DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE,JoblessReincarnation.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends BiomeModifier>> BIOME_MODIFIERS = DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS,JoblessReincarnation.MOD_ID);
    public static final DeferredHolder<PlacementModifierType<?>,PlacementModifierType<BuenaVegetation.Clearance>> BUENA_CLEARANCE =
            PLACEMENT.register("buena_clearance",()->()->BuenaVegetation.Clearance.CODEC);
    public static final DeferredHolder<MapCodec<? extends BiomeModifier>,MapCodec<BuenaVegetation>> BUENA_VEGETATION =
            BIOME_MODIFIERS.register("buena_vegetation",()->BuenaVegetation.CODEC);
    public static final DeferredHolder<StructureType<?>,StructureType<BuenaVillageStructure>> BUENA_VILLAGE =
            TYPES.register("buena_village",()->()->BuenaVillageStructure.CODEC);
    public static final DeferredHolder<StructurePieceType,StructurePieceType> BUENA_PIECE =
            PIECES.register("buena_village_piece",()->BuenaVillagePiece::new);
    public static final DeferredHolder<StructureType<?>, StructureType<SylphietteMeetingStructure>> SYLPHIETTE_MEETING =
            TYPES.register("sylphiette_meeting", () -> () -> SylphietteMeetingStructure.CODEC);
    public static final DeferredHolder<StructureType<?>, StructureType<GreyratHomeStructure>> GREYRAT_HOME =
            TYPES.register("greyrat_home", () -> () -> GreyratHomeStructure.CODEC);
    private ModStructures() { }
    public static void register(IEventBus bus) { TYPES.register(bus); PIECES.register(bus); PLACEMENT.register(bus); BIOME_MODIFIERS.register(bus); }
}
