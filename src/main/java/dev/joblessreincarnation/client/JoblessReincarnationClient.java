package dev.joblessreincarnation.client;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.npc.FamilyRendering;
import dev.joblessreincarnation.client.npc.SylphietteRendering;
import dev.joblessreincarnation.client.npc.NpcHeldModels;
import dev.joblessreincarnation.client.npc.RoxyRendering;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = JoblessReincarnation.MOD_ID, dist = Dist.CLIENT)
public final class JoblessReincarnationClient {
    public JoblessReincarnationClient(IEventBus modEventBus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, HudConfig.SPEC);
        dev.joblessreincarnation.client.epic.EpicV01Client.install();
        dev.joblessreincarnation.client.epic.EpicNpcRemodelClient.install();
        dev.joblessreincarnation.client.aura.AuraLayer.install();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.aura.AuraBlade::onRenderHand);
        PunchyFirstPerson.install();
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(dev.joblessreincarnation.client.aura.AuraOverlay::register);
        modEventBus.addListener(dev.joblessreincarnation.client.aura.AshOverlay::register);
        modEventBus.addListener(dev.joblessreincarnation.client.aura.FormHud::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.aura.FormHud::hideInnateIcon);
        modEventBus.addListener(dev.joblessreincarnation.client.aura.SwordChargeHud::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.aura.SwordChargeHud::hideEpicFightBar);
        modEventBus.addListener(NpcHeldModels::register);
        modEventBus.addListener(ReadingTomeModels::register);
        modEventBus.addListener(ReadingTomeModels::bake);
        modEventBus.addListener(WandHeldModel::register);
        modEventBus.addListener(WandHeldModel::bake);
        modEventBus.addListener(this::registerParticles);
        modEventBus.addListener(this::registerHud);
        modEventBus.addListener(this::registerScreens);
        modEventBus.addListener(this::registerItemExtensions);
        modEventBus.addListener(SpellKeys::register);
        // A resource pack may add or drop the NPC eye skins: look for them again after every reload.
        modEventBus.addListener((net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) -> event.registerReloadListener(
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> dev.joblessreincarnation.client.epic.NpcEyes.forget()));
        yesman.epicfight.api.client.event.EpicFightClientEventHooks.Camera.LOCK_ON_START.registerEvent(SpellKeys::keepCycleFromLockingOn);
        NeoForge.EVENT_BUS.addListener(ManaReadingPanel::onSystemMessage);
        NeoForge.EVENT_BUS.addListener(ManaReadingPanel::hideItemName);
        NeoForge.EVENT_BUS.addListener(StaffInput::beforeClientTick);
        NeoForge.EVENT_BUS.addListener(StaffInput::onInteraction);
        NeoForge.EVENT_BUS.addListener(StaffInput::afterClientTick);
        NeoForge.EVENT_BUS.addListener(SpellRecall::onScroll);
        NeoForge.EVENT_BUS.addListener(SpellRecallBar::hideVanilla);
        NeoForge.EVENT_BUS.addListener(JournalInput::onKeyInput);
        NeoForge.EVENT_BUS.addListener(JournalInput::onMouseInput);
        NeoForge.EVENT_BUS.addListener(DismissInputState::onKeyInput);
        NeoForge.EVENT_BUS.addListener(DismissInputState::onMouseInput);
        NeoForge.EVENT_BUS.addListener(EarthWallPreview::render);
        NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.boss.ArenaRing::render);
        // Boss bars: ours sit under any vanilla ones, and are dropped when leaving a world.
        NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.boss.BossBarHud::beforeGui);
        NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.boss.BossBarHud::afterVanillaBar);
        NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.boss.ClientBossBars::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.boss.ClientBossBars::onClone);
        NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.boss.BossBarDemo::register);
        NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.boss.BossBarDemo::poll);
        // Choosing what the NPCs call you: the screen is shown by the server side; these two are for development previews only.
        NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.naming.NamingDemo::register);
        NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.naming.NamingDemo::poll);
        NeoForge.EVENT_BUS.addListener(dev.joblessreincarnation.client.naming.NamingClient::tick);
    }

    private void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpecial(ModContent.FIRE_FOCUS.get(), new FireFocusParticle.Provider());
        event.registerSpriteSet(ModContent.SPELL_SPRITE.get(), SpellSpriteParticle.Provider::new);
        event.registerSpecial(ModContent.SPELL_BURST.get(), new SpellBurst());
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModContent.WATER_BOLT.get(), WaterBoltRenderer::new);
        event.registerEntityRenderer(ModContent.RASTA_BEAR.get(), dev.joblessreincarnation.client.monster.RastaBearRenderer::new);
        event.registerEntityRenderer(ModContent.FIRE_BALL.get(), FireBallRenderer::new);
        event.registerEntityRenderer(ModContent.WATER_SHIELD.get(), WaterShieldRenderer::new);
        event.registerEntityRenderer(ModContent.MIRE.get(), MireRenderer::new);
        event.registerEntityRenderer(ModContent.STONE_CANNON.get(), StoneCannonRenderer::new);
        // Formal OFF3-02 geometry, animation and texture ship in main resources for ordinary clients.
        event.registerEntityRenderer(ModContent.PRACTICE_DUMMY.get(), PracticeDummyRenderer::new);
        event.registerEntityRenderer(ModContent.ROXY_TUTOR.get(), RoxyRendering::create);
        event.registerEntityRenderer(ModContent.PAUL_GREYRAT.get(), FamilyRendering.provider(FamilyResidentEntity.Member.PAUL));
        event.registerEntityRenderer(ModContent.ZENITH_GREYRAT.get(), FamilyRendering.provider(FamilyResidentEntity.Member.ZENITH));
        event.registerEntityRenderer(ModContent.LILIA_GREYRAT.get(), FamilyRendering.provider(FamilyResidentEntity.Member.LILIA));
        event.registerEntityRenderer(ModContent.SYLPHIETTE.get(), SylphietteRendering::create);
    }

    private void registerHud(RegisterGuiLayersEvent event) {
        event.registerAbove(net.neoforged.neoforge.client.gui.VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "spell_recall"), SpellRecallBar::render);
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "mana"), ManaHud::render);
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "mana_reading"), ManaReadingPanel::render);
        event.registerAbove(net.neoforged.neoforge.client.gui.VanillaGuiLayers.BOSS_OVERLAY, ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "boss_bars"), dev.joblessreincarnation.client.boss.BossBarHud::render);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModContent.TRAINING_BOARD_MENU.get(), TrainingBoardScreen::new);
    }

    private void registerItemExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new WandClientExtensions(), ModContent.TRAINING_STAFF.get());
        var reading = new TomeReading();
        for (var spell : dev.joblessreincarnation.magic.SpellCatalog.values()) event.registerItem(reading, spell.tomeItem());
    }
}
