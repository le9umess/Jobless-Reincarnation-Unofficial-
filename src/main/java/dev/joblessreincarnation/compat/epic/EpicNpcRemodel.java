package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.registry.ModContent;
import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import yesman.epicfight.api.event.EpicFightEventHooks;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.registry.entries.EpicFightAttributes;

/** V-02 model integration, isolated from the formal release. */
public final class EpicNpcRemodel {
    public static void install(IEventBus bus) {
        EpicFightEventHooks.Registry.ENTITY_PATCH.registerEvent(event -> {
            event.registerEntityPatch(ModContent.PAUL_GREYRAT.get(), PaulDuelCombat.Patch::new);
            event.registerEntityPatch(ModContent.ZENITH_GREYRAT.get(), entity -> new FriendlyNpcEpicPatch<>(entity));
            event.registerEntityPatch(ModContent.LILIA_GREYRAT.get(), entity -> new FriendlyNpcEpicPatch<>(entity));
            event.registerEntityPatch(ModContent.SYLPHIETTE.get(), entity -> new FriendlyNpcEpicPatch<>(entity));
        });
        bus.addListener(EpicNpcRemodel::setup);
        bus.addListener(EpicNpcRemodel::attributes);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(NpcEpicGestures::tick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(NpcEpicGestures::stopped);
    }
    private static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            Armatures.registerEntityTypeArmature(ModContent.PAUL_GREYRAT.get(), Armatures.BIPED);
            Armatures.registerEntityTypeArmature(ModContent.ZENITH_GREYRAT.get(), Armatures.BIPED);
            Armatures.registerEntityTypeArmature(ModContent.LILIA_GREYRAT.get(), Armatures.BIPED);
            Armatures.registerEntityTypeArmature(ModContent.SYLPHIETTE.get(), Armatures.BIPED);
        });
    }
    private static void attributes(EntityAttributeModificationEvent event) {
        event.add(ModContent.PAUL_GREYRAT.get(), net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, 4.0);
        for (var type : List.of(ModContent.PAUL_GREYRAT.get(), ModContent.ZENITH_GREYRAT.get(),
                ModContent.LILIA_GREYRAT.get(), ModContent.SYLPHIETTE.get())) {
            event.add(type, EpicFightAttributes.WEIGHT);
            event.add(type, EpicFightAttributes.ARMOR_NEGATION);
            event.add(type, EpicFightAttributes.IMPACT);
            event.add(type, EpicFightAttributes.MAX_STRIKES);
            event.add(type, EpicFightAttributes.STUN_ARMOR);
        }
    }
    private EpicNpcRemodel() {}
}
