package dev.joblessreincarnation.gametest.client;

import com.mojang.logging.LogUtils;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.gametest.InputProbeControls;
import dev.joblessreincarnation.magic.WaterMagic;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.client.SpellKeys;
import dev.joblessreincarnation.client.StaffInput;
import dev.joblessreincarnation.client.DismissInputState;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/** Logical key regression probe. Vanilla handleKeybinds and StaffInput remain in charge. */
@EventBusSubscriber(modid = JoblessReincarnation.MOD_ID, value = Dist.CLIENT)
public final class InputProbeClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private record Case(String name, boolean toggle, boolean initialShift, boolean releaseShift,
            boolean switchOnRelease, boolean earlyRelease, boolean expectCast, int switchMode) {
        Case(String name, boolean toggle, boolean initialShift, boolean releaseShift,
                boolean switchOnRelease, boolean earlyRelease, boolean expectCast) {
            this(name, toggle, initialShift, releaseShift, switchOnRelease, earlyRelease, expectCast, 0);
        }
    }
    private static final Case[] CASES = {
            new Case("normal_full_release", false, false, false, false, false, true),
            new Case("same_tick_enter_crouch_cancel", false, false, true, false, false, false),
            new Case("same_tick_exit_crouch_release", false, true, false, false, false, true),
            new Case("toggle_same_tick_enter_cancel", true, false, true, false, false, false),
            new Case("toggle_same_tick_exit_release", true, true, false, false, false, true),
            new Case("identical_wand_slot_change_cancel", false, false, false, true, false, false),
            // Each case has a server reset: this verifies an independent short hold, not a held-key slot transition.
            new Case("new_wand_starts_fresh_early_cancel", false, false, false, false, true, false),
            new Case("same_tick_cycle_and_release_cancel", false, false, false, false, false, false, 1),
            new Case("held_cycle_waits_for_release_then_fresh_earth_chant", false, false, false, false, false, false, 2),
            new Case("dismiss_full_hold_release",false,false,false,false,false,false),
            new Case("dismiss_early_release_free",false,false,false,false,true,false),
            new Case("dismiss_same_tick_G_release_cancel",false,false,false,false,false,false),
            new Case("dismiss_same_tick_equal_wand_slot_release_cancel",false,false,false,true,false,false),
            new Case("dismiss_same_tick_sneak_release_cancel",false,false,true,false,false,false),
            new Case("dismiss_toggle_same_tick_sneak_release_cancel",true,false,true,false,false,false),
            new Case("dismiss_blocks_held_right_until_release_then_fresh_chant",false,false,false,false,false,false),
            new Case("three_spell_G_cycle_water_earth_air_water",false,false,false,false,false,false),
            new Case("G_skips_unlearned_air",false,false,false,false,false,false),
            new Case("air_ready_then_normal_release",false,false,false,false,false,true),
            new Case("H_cannot_dismiss_while_air_selected",false,false,false,false,false,false),
            new Case("air_early_release_free",false,false,false,false,true,false),
            new Case("air_same_tick_sneak_release_free",false,false,true,false,false,false),
            new Case("four_spell_G_cycle_water_earth_air_healing_water",false,false,false,false,false,false),
            new Case("G_skips_unlearned_healing",false,false,false,false,false,false),
            new Case("healing_ready_then_self_release",false,false,false,false,false,true),
            new Case("healing_full_health_release_free",false,false,false,false,false,false),
            new Case("H_cannot_dismiss_while_healing_selected",false,false,false,false,false,false),
            new Case("healing_early_release_free",false,false,false,false,true,false),
            new Case("healing_same_tick_sneak_release_free",false,false,true,false,false,false)
    };
    private enum Phase { WAIT_FIXTURE, WAIT_CASE, SETTLE, HOLD, HELD_SWITCH, HELD_CHECK_WAIT, SWITCH_SETTLE, RESTART_HOLD, RELEASE_WAIT, CHECK_WAIT, DISMISS_HOLD, DISMISS_RIGHT_BEFORE, DISMISS_RELEASE_WAIT, DISMISS_RIGHT_WAIT, DISMISS_HELD_CHECK, DISMISS_RELEASE_RIGHT, DISMISS_REPRESS, DISMISS_REPRESS_HOLD, AIR_CYCLE_WAIT }
    private static MinecraftServer attemptedServer;
    private static MinecraftServer server;
    private static UUID playerId;
    private static Phase phase;
    private static int phaseTicks;
    private static int totalTicks;
    private static int warmupTicks;
    private static int caseId;
    private static boolean originalToggle;
    private static boolean originalShift;
    private static boolean desiredUse;
    private static boolean desiredDismiss;
    private static boolean dismissScreenshot;
    private static int airCycleStep;
    private static boolean desiredShift;
    private static long releaseServerTick;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ClientTickEvent.Pre event) {
        if (!InputProbeControls.enabled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer current = minecraft.getSingleplayerServer();
        if (server == null) {
            if (current == null) { attemptedServer = null; warmupTicks = 0; return; }
            if (current == attemptedServer || !usable(minecraft, current)) return;
            if (++warmupTicks < 80) return;
            attemptedServer = current;
            begin(minecraft, current);
            return;
        }
        if (current != server || !usable(minecraft, server)) {
            abort(minecraft, "screen/overlay, pause, lost focus, changed world, or published server");
            return;
        }
        if (++totalTicks > 3900 || ++phaseTicks > 240) {
            abort(minecraft, "client timeout in " + phase + ", case " + caseId);
            return;
        }
        try {
            tick(minecraft);
            if (server != null) {
                // Set only logical inputs. No startUsingItem, releaseUsingItem, selected-slot assignment,
                // direct packet send, or client use-state mirroring is performed by this probe.
                minecraft.options.keyUse.setDown(desiredUse);
                SpellKeys.DISMISS.setDown(desiredDismiss);
                setShift(minecraft, desiredShift);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("[JR_INPUT_PROBE] CLIENT_EXCEPTION", failure);
            abort(minecraft, "client exception");
        }
    }

    private static boolean usable(Minecraft minecraft, MinecraftServer candidate) {
        return minecraft.hasSingleplayerServer() && !candidate.isPublished()
                && minecraft.player != null && minecraft.player.isAlive() && !minecraft.player.isSpectator()
                && minecraft.gameMode != null && !minecraft.player.getAbilities().instabuild
                && minecraft.screen == null && minecraft.getOverlay() == null
                && !minecraft.isPaused() && minecraft.isWindowActive();
    }

    private static void begin(Minecraft minecraft, MinecraftServer candidate) {
        server = candidate;
        playerId = minecraft.player.getUUID();
        originalToggle = minecraft.options.toggleCrouch().get();
        originalShift = minecraft.options.keyShift.isDown();
        minecraft.options.toggleCrouch().set(false);
        desiredUse = false;
        desiredShift = false;
        minecraft.options.keyUse.setDown(false);
        setShift(minecraft, false);
        totalTicks = 0;
        caseId = -1;
        changePhase(Phase.WAIT_FIXTURE);
        MinecraftServer target = server;
        UUID id = playerId;
        target.execute(() -> InputProbeControls.begin(target, id));
        LOGGER.info("[JR_INPUT_PROBE] CLIENT_BEGIN: logical key input, no visible screen; this is not manual keyboard/mouse or two-client acceptance");
    }

    private static void tick(Minecraft minecraft) {
        InputProbeControls.Receipt receipt = InputProbeControls.receipt(server, playerId);
        if (phase == Phase.WAIT_FIXTURE) {
            if (receipt != null && minecraft.player.getInventory().getItem(1).is(ModContent.TRAINING_STAFF.get())) nextCase(minecraft);
            return;
        }
        if (receipt == null) { abort(minecraft, "server fixture ended"); return; }
        Case current = CASES[caseId];
        if (caseId >= 22) { tickHealing(minecraft, receipt); return; }
        if (caseId >= 16) { tickAir(minecraft, receipt); return; }
        if (caseId >= 9) { tickDismiss(minecraft, receipt); return; }
        int initialSlot = current.earlyRelease() ? 1 : 0;
        switch (phase) {
            case WAIT_CASE -> {
                if (receipt.caseId() != caseId || !receipt.result().equals("READY")
                        || receipt.selectedSpell() != SpellCatalog.WATER_BOLT
                        || SpellCatalog.selected(minecraft.player) != SpellCatalog.WATER_BOLT
                        || (caseId >= 7 && !SpellCatalog.EARTH_WALL.knows(minecraft.player))
                        || !WaterMagic.isLearned(minecraft.player)
                        || WaterMagic.practiceCount(minecraft.player) != 0
                        || !minecraft.player.hasData(ModAttachments.MANA)
                        || minecraft.player.getData(ModAttachments.MANA).mana() != receipt.maximum()
                        || minecraft.player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) return;
                if (receipt.chantTicks() < 6) { abort(minecraft, "early-release regression requires chantTicks >= 6"); return; }
                minecraft.options.toggleCrouch().set(current.toggle());
                desiredShift = current.initialShift();
                KeyMapping.click(minecraft.options.keyHotbarSlots[initialSlot].getKey());
                changePhase(Phase.SETTLE);
            }
            case SETTLE -> {
                if (phaseTicks < 8 || receipt.slot() != initialSlot || receipt.sneaking() != current.initialShift()
                        || minecraft.player.getInventory().selected != initialSlot
                        || minecraft.player.isShiftKeyDown() != current.initialShift()) return;
                desiredUse = true;
                KeyMapping.click(minecraft.options.keyUse.getKey());
                LOGGER.info("[JR_INPUT_PROBE] CLIENT_HOLD case={} name={} toggle={} slot={} shift={}",
                        caseId, current.name(), current.toggle(), initialSlot, desiredShift);
                changePhase(Phase.HOLD);
            }
            case HOLD -> {
                if (!receipt.using() || !minecraft.player.isUsingItem()) return;
                int targetTicks = current.earlyRelease() ? 2 : receipt.fullTicks() + 3;
                if (receipt.useTicks() < targetTicks) return;
                if (current.earlyRelease() && receipt.useTicks() >= receipt.chantTicks()) {
                    abort(minecraft, "early-release observation exceeded chant boundary"); return;
                }
                if (current.switchMode() != 0) {
                    KeyMapping.click(SpellKeys.CYCLE.getKey());
                    LOGGER.info("[JR_INPUT_PROBE] CLIENT_CYCLE_INTENT case={} held={} serverUseTicks={} clientTick={}",
                            caseId, current.switchMode() == 2, receipt.useTicks(), minecraft.player.tickCount);
                    if (current.switchMode() == 2) {
                        releaseServerTick = receipt.tick();
                        changePhase(Phase.HELD_SWITCH);
                        return;
                    }
                }
                desiredShift = current.releaseShift();
                desiredUse = false;
                if (current.switchOnRelease()) KeyMapping.click(minecraft.options.keyHotbarSlots[1].getKey());
                releaseServerTick = receipt.tick();
                LOGGER.info("[JR_INPUT_PROBE] CLIENT_RELEASE_INTENT case={} name={} serverUseTicks={} clientUseTicks={} oldShift={} nextShift={} switchSlot={} clientTick={}",
                        caseId, current.name(), receipt.useTicks(), minecraft.player.getTicksUsingItem(),
                        minecraft.player.isShiftKeyDown(), desiredShift, current.switchOnRelease(), minecraft.player.tickCount);
                changePhase(Phase.RELEASE_WAIT);
            }
            case HELD_SWITCH -> {
                if (receipt.selectedSpell() != SpellCatalog.EARTH_WALL) return;
                if (receipt.unexpectedUse() || receipt.using() || receipt.activeSpell() != null) {
                    abort(minecraft, "held use restarted the switched earth spell"); return;
                }
                if (receipt.blockedTicks() < 42 || receipt.tick() < releaseServerTick + 42) return;
                if (!desiredUse || !minecraft.options.keyUse.isDown() || !StaffInput.waitingForRelease()
                        || minecraft.player.isUsingItem()) {
                    abort(minecraft, "logical held-use guard did not remain active"); return;
                }
                MinecraftServer target = server;
                UUID id = playerId;
                target.execute(() -> InputProbeControls.checkHeldSwitch(target, id));
                changePhase(Phase.HELD_CHECK_WAIT);
            }
            case HELD_CHECK_WAIT -> {
                if (receipt.result().equals("FAIL")) { abort(minecraft, "held-switch server observation failed"); return; }
                if (!receipt.result().equals("HELD_PASS")) return;
                desiredUse = false;
                changePhase(Phase.SWITCH_SETTLE);
            }
            case SWITCH_SETTLE -> {
                if (phaseTicks < 8 || StaffInput.waitingForRelease() || receipt.using() || minecraft.player.isUsingItem()) return;
                if (receipt.selectedSpell() != SpellCatalog.EARTH_WALL || SpellCatalog.selected(minecraft.player) != SpellCatalog.EARTH_WALL) return;
                desiredUse = true;
                KeyMapping.click(minecraft.options.keyUse.getKey());
                LOGGER.info("[JR_INPUT_PROBE] CLIENT_EARTH_REPRESS case={}: use was released, beginning a fresh independent chant", caseId);
                changePhase(Phase.RESTART_HOLD);
            }
            case RESTART_HOLD -> {
                if (!receipt.using() || !minecraft.player.isUsingItem() || receipt.useTicks() < 2) return;
                if (receipt.activeSpell() != SpellCatalog.EARTH_WALL || receipt.useTicks() >= receipt.chantTicks()) {
                    abort(minecraft, "repressed earth chant was not independent or exceeded early boundary"); return;
                }
                desiredUse = false;
                releaseServerTick = receipt.tick();
                LOGGER.info("[JR_INPUT_PROBE] CLIENT_EARLY_EARTH_RELEASE case={} useTicks={} chantTicks={}",
                        caseId, receipt.useTicks(), receipt.chantTicks());
                changePhase(Phase.RELEASE_WAIT);
            }
            case RELEASE_WAIT -> {
                if (receipt.tick() < releaseServerTick + 6 || phaseTicks < 6) return;
                MinecraftServer target = server;
                UUID id = playerId;
                int index = caseId;
                int finalSlot = current.switchOnRelease() ? 1 : initialSlot;
                target.execute(() -> InputProbeControls.checkCase(target, id, index,
                        current.expectCast(), finalSlot, current.releaseShift()));
                changePhase(Phase.CHECK_WAIT);
            }
            case CHECK_WAIT -> {
                if (receipt.result().equals("FAIL")) { abort(minecraft, "server assertion failed: " + current.name()); return; }
                if (receipt.result().equals("PASS")) nextCase(minecraft);
            }
            case WAIT_FIXTURE -> throw new AssertionError("Handled above");
            default -> throw new IllegalStateException("Unexpected original-case phase " + phase);
        }
    }

    private static void tickAir(Minecraft minecraft, InputProbeControls.Receipt receipt) {
        boolean cycle = caseId == 16 || caseId == 17;
        SpellCatalog initial = cycle ? SpellCatalog.WATER_BOLT : SpellCatalog.AIR_BURST;
        switch (phase) {
            case WAIT_CASE -> {
                if (receipt.caseId() != caseId || !receipt.result().equals("READY")
                        || receipt.selectedSpell() != initial || SpellCatalog.selected(minecraft.player) != initial
                        || !SpellCatalog.EARTH_WALL.knows(minecraft.player)
                        || SpellCatalog.AIR_BURST.knows(minecraft.player) != (caseId != 17)
                        || receipt.airPractice() != 0 || receipt.mana() != receipt.maximum()
                        || minecraft.player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) return;
                minecraft.options.toggleCrouch().set(false);
                desiredShift = false; desiredDismiss = false; desiredUse = false; airCycleStep = 0;
                KeyMapping.click(minecraft.options.keyHotbarSlots[0].getKey());
                changePhase(Phase.SETTLE);
            }
            case SETTLE -> {
                if (phaseTicks < 8 || receipt.slot() != 0 || minecraft.player.getInventory().selected != 0
                        || receipt.sneaking() || minecraft.player.isShiftKeyDown()
                        || StaffInput.waitingForRelease() || DismissInputState.blocksUse()) return;
                if (cycle) {
                    KeyMapping.click(SpellKeys.CYCLE.getKey());
                    changePhase(Phase.AIR_CYCLE_WAIT);
                } else if (caseId == 19) {
                    desiredDismiss = true;
                    changePhase(Phase.DISMISS_HOLD);
                } else {
                    desiredUse = true;
                    KeyMapping.click(minecraft.options.keyUse.getKey());
                    changePhase(Phase.HOLD);
                }
            }
            case AIR_CYCLE_WAIT -> {
                SpellCatalog[] sequence = caseId == 16
                        ? new SpellCatalog[]{SpellCatalog.EARTH_WALL, SpellCatalog.AIR_BURST, SpellCatalog.WATER_BOLT}
                        : new SpellCatalog[]{SpellCatalog.EARTH_WALL, SpellCatalog.WATER_BOLT};
                if (receipt.selectedSpell() != sequence[airCycleStep]
                        || SpellCatalog.selected(minecraft.player) != sequence[airCycleStep] || phaseTicks < 6) return;
                LOGGER.info("[JR_INPUT_PROBE] CYCLE_ACK case={} step={} spell={}", caseId, airCycleStep, sequence[airCycleStep]);
                if (++airCycleStep < sequence.length) {
                    KeyMapping.click(SpellKeys.CYCLE.getKey());
                    changePhase(Phase.AIR_CYCLE_WAIT);
                } else {
                    requestAirCheck(); changePhase(Phase.CHECK_WAIT);
                }
            }
            case HOLD -> {
                if (!receipt.using() || !minecraft.player.isUsingItem()) return;
                if (receipt.activeSpell() != SpellCatalog.AIR_BURST) { abort(minecraft, "air input locked another spell"); return; }
                int target = caseId == 20 ? 2 : receipt.chantTicks() + 10;
                if (receipt.useTicks() < target) return;
                if (caseId == 20 && receipt.useTicks() >= receipt.chantTicks()) { abort(minecraft, "air early release passed chant boundary"); return; }
                if (caseId == 18) {
                    String name = "air_burst_ready_" + System.currentTimeMillis() + ".png";
                    net.minecraft.client.Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message ->
                            LOGGER.info("[JR_INPUT_PROBE] AIR_SCREENSHOT_RESULT name={} result={}", name, message.getString()));
                }
                desiredShift = caseId == 21;
                desiredUse = false;
                releaseServerTick = receipt.tick();
                LOGGER.info("[JR_INPUT_PROBE] AIR_RELEASE_INTENT case={} used={} chant={} sneak={}",caseId,receipt.useTicks(),receipt.chantTicks(),desiredShift);
                changePhase(Phase.RELEASE_WAIT);
            }
            case DISMISS_HOLD -> {
                if (receipt.dismissActive() || DismissInputState.accepted() || receipt.using()) {
                    abort(minecraft, "H incorrectly began while Air Burst was selected"); return;
                }
                if (phaseTicks < receipt.dismissHoldTicks() + 8) return;
                desiredDismiss = false; releaseServerTick = receipt.tick();
                changePhase(Phase.RELEASE_WAIT);
            }
            case RELEASE_WAIT -> {
                if (phaseTicks < 8 || receipt.tick() < releaseServerTick + 8) return;
                requestAirCheck(); changePhase(Phase.CHECK_WAIT);
            }
            case CHECK_WAIT -> {
                if (receipt.result().equals("FAIL")) { abort(minecraft, "air/cycle server assertion failed: " + CASES[caseId].name()); return; }
                if (receipt.result().equals("PASS")) nextCase(minecraft);
            }
            default -> throw new IllegalStateException("Unexpected air phase " + phase);
        }
    }
    private static void requestAirCheck() {
        MinecraftServer target = server; UUID id = playerId; int index = caseId;
        target.execute(() -> InputProbeControls.checkAirCase(target, id, index));
    }
    private static void tickHealing(Minecraft minecraft, InputProbeControls.Receipt receipt) {
        boolean cycle = caseId == 22 || caseId == 23;
        SpellCatalog initial = cycle ? SpellCatalog.WATER_BOLT : SpellCatalog.HEALING;
        switch (phase) {
            case WAIT_CASE -> {
                if (receipt.caseId() != caseId || !receipt.result().equals("READY")
                        || receipt.selectedSpell() != initial || SpellCatalog.selected(minecraft.player) != initial
                        || !SpellCatalog.EARTH_WALL.knows(minecraft.player)
                        || SpellCatalog.HEALING.knows(minecraft.player) != (caseId != 23)
                        || Math.abs(minecraft.player.getHealth() - receipt.health()) > 0.001F
                        || receipt.healingPractice() != 0 || receipt.mana() != receipt.maximum()
                        || minecraft.player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) return;
                minecraft.options.toggleCrouch().set(false);
                desiredShift = false; desiredDismiss = false; desiredUse = false; airCycleStep = 0;
                KeyMapping.click(minecraft.options.keyHotbarSlots[0].getKey());
                changePhase(Phase.SETTLE);
            }
            case SETTLE -> {
                if (phaseTicks < 8 || receipt.slot() != 0 || minecraft.player.getInventory().selected != 0
                        || receipt.sneaking() || minecraft.player.isShiftKeyDown()
                        || StaffInput.waitingForRelease() || DismissInputState.blocksUse()) return;
                if (cycle) {
                    KeyMapping.click(SpellKeys.CYCLE.getKey());
                    changePhase(Phase.AIR_CYCLE_WAIT);
                } else if (caseId == 26) {
                    desiredDismiss = true;
                    changePhase(Phase.DISMISS_HOLD);
                } else {
                    desiredUse = true;
                    KeyMapping.click(minecraft.options.keyUse.getKey());
                    changePhase(Phase.HOLD);
                }
            }
            case AIR_CYCLE_WAIT -> {
                SpellCatalog[] sequence = caseId == 22
                        ? new SpellCatalog[]{SpellCatalog.EARTH_WALL, SpellCatalog.AIR_BURST, SpellCatalog.HEALING, SpellCatalog.WATER_BOLT}
                        : new SpellCatalog[]{SpellCatalog.EARTH_WALL, SpellCatalog.AIR_BURST, SpellCatalog.WATER_BOLT};
                if (receipt.selectedSpell() != sequence[airCycleStep]
                        || SpellCatalog.selected(minecraft.player) != sequence[airCycleStep] || phaseTicks < 6) return;
                LOGGER.info("[JR_INPUT_PROBE] CYCLE_ACK case={} step={} spell={}", caseId, airCycleStep, sequence[airCycleStep]);
                if (++airCycleStep < sequence.length) {
                    KeyMapping.click(SpellKeys.CYCLE.getKey());
                    changePhase(Phase.AIR_CYCLE_WAIT);
                } else {
                    requestHealingCheck(); changePhase(Phase.CHECK_WAIT);
                }
            }
            case HOLD -> {
                if (!receipt.using() || !minecraft.player.isUsingItem()) return;
                if (receipt.activeSpell() != SpellCatalog.HEALING) { abort(minecraft, "healing input locked another spell"); return; }
                int target = caseId == 27 ? 2 : receipt.chantTicks() + 10;
                if (receipt.useTicks() < target) return;
                if (caseId == 27 && receipt.useTicks() >= receipt.chantTicks()) { abort(minecraft, "healing early release passed chant boundary"); return; }
                if (caseId == 24) {
                    String name = "healing_ready_" + System.currentTimeMillis() + ".png";
                    net.minecraft.client.Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message ->
                            LOGGER.info("[JR_INPUT_PROBE] HEALING_SCREENSHOT_RESULT name={} result={}", name, message.getString()));
                }
                desiredShift = caseId == 28;
                desiredUse = false;
                releaseServerTick = receipt.tick();
                LOGGER.info("[JR_INPUT_PROBE] HEALING_RELEASE_INTENT case={} used={} chant={} sneak={}",caseId,receipt.useTicks(),receipt.chantTicks(),desiredShift);
                changePhase(Phase.RELEASE_WAIT);
            }
            case DISMISS_HOLD -> {
                if (receipt.dismissActive() || DismissInputState.accepted() || receipt.using()) {
                    abort(minecraft, "H incorrectly began while Healing was selected"); return;
                }
                if (phaseTicks < receipt.dismissHoldTicks() + 8) return;
                desiredDismiss = false; releaseServerTick = receipt.tick();
                changePhase(Phase.RELEASE_WAIT);
            }
            case RELEASE_WAIT -> {
                if (phaseTicks < 8 || receipt.tick() < releaseServerTick + 8) return;
                requestHealingCheck(); changePhase(Phase.CHECK_WAIT);
            }
            case CHECK_WAIT -> {
                if (receipt.result().equals("FAIL")) { abort(minecraft, "healing/cycle server assertion failed: " + CASES[caseId].name()); return; }
                if (receipt.result().equals("PASS")) nextCase(minecraft);
            }
            default -> throw new IllegalStateException("Unexpected healing phase " + phase);
        }
    }
    private static void requestHealingCheck() {
        MinecraftServer target = server; UUID id = playerId; int index = caseId;
        target.execute(() -> InputProbeControls.checkHealingCase(target, id, index));
    }
    private static void tickDismiss(Minecraft minecraft, InputProbeControls.Receipt receipt) {
        Case current = CASES[caseId];
        switch (phase) {
            case WAIT_CASE -> {
                if (receipt.caseId()!=caseId || !receipt.result().equals("READY")
                        || receipt.selectedSpell()!=SpellCatalog.EARTH_WALL || SpellCatalog.selected(minecraft.player)!=SpellCatalog.EARTH_WALL
                        || receipt.wallRemaining()!=6 || receipt.mana()!=receipt.maximum()
                        || minecraft.player.getCooldowns().isOnCooldown(ModContent.TRAINING_STAFF.get())) return;
                minecraft.options.toggleCrouch().set(current.toggle()); desiredShift=false; desiredDismiss=false;
                KeyMapping.click(minecraft.options.keyHotbarSlots[0].getKey());
                changePhase(Phase.SETTLE);
            }
            case SETTLE -> {
                if(phaseTicks<8 || receipt.slot()!=0 || minecraft.player.getInventory().selected!=0 || receipt.sneaking()
                        || DismissInputState.blocksUse() || StaffInput.waitingForRelease())return;
                desiredDismiss=true;
                LOGGER.info("[JR_INPUT_PROBE] CLIENT_H_DOWN case={} name={}: logical H key, normal mod networking",caseId,current.name());
                changePhase(Phase.DISMISS_HOLD);
            }
            case DISMISS_HOLD -> {
                if(!receipt.dismissActive() || !DismissInputState.accepted())return;
                if (receipt.dismissHoldTicks()<6) { abort(minecraft,"H early-release probe requires holdTicks >= 6"); return; }
                int target=current.earlyRelease()?3:receipt.dismissHoldTicks()+5;
                if(receipt.dismissTicks()<target)return;
                if(current.earlyRelease()&&receipt.dismissTicks()>=receipt.dismissHoldTicks()){abort(minecraft,"H early release exceeded configured hold boundary");return;}
                if(caseId==9&&!dismissScreenshot&&DismissInputState.ready()) {
                    dismissScreenshot=true;
                    String name="earth_wall_dismiss_ready_"+System.currentTimeMillis()+".png";
                    net.minecraft.client.Screenshot.grab(minecraft.gameDirectory,name,minecraft.getMainRenderTarget(),message ->
                            LOGGER.info("[JR_INPUT_PROBE] H_SCREENSHOT_RESULT name={} result={}",name,message.getString()));
                    LOGGER.info("[JR_INPUT_PROBE] H_SCREENSHOT_REQUESTED {}: actual ready HUD and six-cell outline; callback reports save result",name);
                }
                if(caseId==15) {
                    desiredUse=true; KeyMapping.click(minecraft.options.keyUse.getKey());
                    changePhase(Phase.DISMISS_RIGHT_BEFORE); return;
                }
                if(caseId==11)KeyMapping.click(SpellKeys.CYCLE.getKey());
                if(caseId==12)KeyMapping.click(minecraft.options.keyHotbarSlots[1].getKey());
                desiredShift=current.releaseShift(); desiredDismiss=false; releaseServerTick=receipt.tick();
                LOGGER.info("[JR_INPUT_PROBE] CLIENT_H_UP case={} serverHeld={} sameTickG={} sameTickSlot={} sameTickShift={}",caseId,receipt.dismissTicks(),caseId==11,caseId==12,desiredShift);
                changePhase(Phase.DISMISS_RELEASE_WAIT);
            }
            case DISMISS_RIGHT_BEFORE -> {
                if(receipt.using()||receipt.activeSpell()!=null){abort(minecraft,"right use began during H");return;}
                if(phaseTicks<10)return;
                desiredDismiss=false; releaseServerTick=receipt.tick();
                changePhase(Phase.DISMISS_RELEASE_WAIT);
            }
            case DISMISS_RELEASE_WAIT -> {
                if(phaseTicks<6||receipt.tick()<releaseServerTick+6||receipt.dismissActive())return;
                if(caseId==15){releaseServerTick=receipt.tick();changePhase(Phase.DISMISS_RIGHT_WAIT);return;}
                requestDismissCheck();changePhase(Phase.CHECK_WAIT);
            }
            case DISMISS_RIGHT_WAIT -> {
                if(receipt.using()||receipt.unexpectedUse()||!desiredUse||!StaffInput.waitingForRelease()) {
                    abort(minecraft,"held right restarted after H release or input gate disappeared");return;
                }
                if(receipt.tick()<releaseServerTick+42)return;
                MinecraftServer target=server;UUID id=playerId;
                target.execute(()->InputProbeControls.checkDismissHeldRight(target,id));
                changePhase(Phase.DISMISS_HELD_CHECK);
            }
            case DISMISS_HELD_CHECK -> {
                if(receipt.result().equals("FAIL")){abort(minecraft,"H held-right server check failed");return;}
                if(!receipt.result().equals("HELD_PASS"))return;
                desiredUse=false;changePhase(Phase.DISMISS_RELEASE_RIGHT);
            }
            case DISMISS_RELEASE_RIGHT -> {
                if(phaseTicks<8||StaffInput.waitingForRelease()||DismissInputState.blocksUse()||receipt.using())return;
                desiredUse=true;KeyMapping.click(minecraft.options.keyUse.getKey());
                changePhase(Phase.DISMISS_REPRESS_HOLD);
            }
            case DISMISS_REPRESS_HOLD -> {
                if(!receipt.using()||!minecraft.player.isUsingItem()||receipt.useTicks()<2)return;
                if(receipt.activeSpell()!=SpellCatalog.EARTH_WALL||receipt.useTicks()>=receipt.chantTicks()) {
                    abort(minecraft,"fresh right use after H did not begin an independent early earth chant");return;
                }
                desiredUse=false;releaseServerTick=receipt.tick();changePhase(Phase.DISMISS_REPRESS);
            }
            case DISMISS_REPRESS -> {
                if(phaseTicks<6||receipt.tick()<releaseServerTick+6)return;
                requestDismissCheck();changePhase(Phase.CHECK_WAIT);
            }
            case CHECK_WAIT -> {
                if(receipt.result().equals("FAIL")){abort(minecraft,"H server assertion failed: "+current.name());return;}
                if(receipt.result().equals("PASS"))nextCase(minecraft);
            }
            default -> throw new IllegalStateException("Unexpected H phase "+phase);
        }
    }
    private static void requestDismissCheck() {
        MinecraftServer target=server;UUID id=playerId;int index=caseId;
        target.execute(()->InputProbeControls.checkDismissCase(target,id,index));
    }
    private static void nextCase(Minecraft minecraft) {
        desiredDismiss = false; dismissScreenshot = false;
        SpellKeys.DISMISS.setDown(false);
        desiredUse = false;
        desiredShift = false;
        setShift(minecraft, false);
        if (++caseId == CASES.length) {
            LOGGER.info("[JR_INPUT_PROBE] ALL_PASS {}/{}: real logical client input to integrated server; manual input and remote multiplayer remain untested", CASES.length, CASES.length);
            finish(minecraft, "PASS " + CASES.length + "/" + CASES.length);
            return;
        }
        MinecraftServer target = server;
        UUID id = playerId;
        int index = caseId;
        target.execute(() -> InputProbeControls.prepareCase(target, id, index, CASES[index].name()));
        changePhase(Phase.WAIT_CASE);
    }

    private static void setShift(Minecraft minecraft, boolean down) {
        if (minecraft.options.keyShift.isDown() == down) return;
        // ToggleKeyMapping.setDown(true) flips its logical state. False does not release a toggle.
        minecraft.options.keyShift.setDown(minecraft.options.toggleCrouch().get() || down);
    }

    private static void changePhase(Phase value) { phase = value; phaseTicks = 0; }

    private static void abort(Minecraft minecraft, String reason) {
        LOGGER.error("[JR_INPUT_PROBE] ABORT case={} phase={}: {}", caseId, phase, reason);
        finish(minecraft, "ABORT: " + reason);
    }

    private static void finish(Minecraft minecraft, String reason) {
        desiredDismiss = false; SpellKeys.DISMISS.setDown(false);
        minecraft.options.keyUse.setDown(false);
        setShift(minecraft, false);
        minecraft.options.toggleCrouch().set(originalToggle);
        setShift(minecraft, originalShift);
        MinecraftServer target = server;
        UUID id = playerId;
        server = null;
        playerId = null;
        desiredUse = false;
        desiredShift = false;
        if (target != null) target.execute(() -> {
            InputProbeControls.finish(target, id, reason);
            // Return to the client thread only after the server has restored the disposable fixture.
            minecraft.execute(() -> {
                if (InputProbeControls.enabled() && minecraft.getSingleplayerServer() == target && minecraft.level != null) {
                    minecraft.level.disconnect();
                    minecraft.disconnect(new net.minecraft.client.gui.screens.TitleScreen());
                    LOGGER.info("[JR_INPUT_PROBE] DISCONNECTED: fixture restored; explicit probe client exiting normally");
                    minecraft.stop();
                }
            });
        });
        LOGGER.info("[JR_INPUT_PROBE] CLIENT_RESTORED: use key released; crouch option/state restored without saving options");
    }

    private InputProbeClient() {}
}
