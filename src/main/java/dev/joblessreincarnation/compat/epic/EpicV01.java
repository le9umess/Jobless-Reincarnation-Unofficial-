package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.registry.ModContent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import yesman.epicfight.api.event.EpicFightEventHooks;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.registry.entries.EpicFightAttributes;

/** Isolated V-01 integration. Gameplay remains in this mod; EF owns the adapted pose. */
public final class EpicV01 {
    public static void install(IEventBus modBus) {
        EpicFightEventHooks.Registry.ENTITY_PATCH.registerEvent(event ->
                event.registerEntityPatch(ModContent.ROXY_TUTOR.get(), RoxyEpicPatch::new));
        modBus.addListener(EpicV01::setup);
        modBus.addListener(EpicV01::attributes);
        modBus.addListener(EpicCastAnimations::register);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, EpicCastController::onTick);
        NeoForge.EVENT_BUS.addListener(EpicCastController::onLogout);
        NeoForge.EVENT_BUS.addListener(EpicCastController::onServerStopped);
    }
    private static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> Armatures.registerEntityTypeArmature(ModContent.ROXY_TUTOR.get(), Armatures.BIPED));
    }
    private static void attributes(EntityAttributeModificationEvent event) {
        var type = ModContent.ROXY_TUTOR.get();
        event.add(type, EpicFightAttributes.WEIGHT);
        event.add(type, EpicFightAttributes.ARMOR_NEGATION);
        event.add(type, EpicFightAttributes.IMPACT);
        event.add(type, EpicFightAttributes.MAX_STRIKES);
        event.add(type, EpicFightAttributes.STUN_ARMOR);
    }
    private EpicV01() {}
}
