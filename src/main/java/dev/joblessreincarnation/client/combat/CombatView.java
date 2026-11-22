package dev.joblessreincarnation.client.combat;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.HudConfig;
import dev.joblessreincarnation.combat.CombatViewRules;
import dev.joblessreincarnation.combat.CombatViewRules.View;
import dev.joblessreincarnation.combat.CombatViewTrace;
import dev.joblessreincarnation.combat.CrosshairReach;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;
import yesman.epicfight.client.camera.EpicFightTpsCameraDisableState;
import yesman.epicfight.client.gui.screen.config.TPSSettingScreen;
import yesman.epicfight.config.ClientConfig;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/**
 * The combat view: Epic Fight's own over-the-shoulder camera, at the mod's framing, while the player is in Epic
 * Fight's combat mode. Epic Fight's settings and its in-memory copy of them are never written: the camera mixins read
 * {@link #overriding()} where Epic Fight reads its settings. Each round is classified at the moment Epic Fight enters
 * combat mode, with the setting as it is then: a protected round holds Epic Fight's own perspective switches back both
 * ways, an Epic Fight round leaves them to Epic Fight (see {@link CombatViewRules}). When the framing and TPS are
 * overridden ({@link #overriding()}) is a separate matter and unchanged by that classification.
 */
public final class CombatView {
    /** The two places the crosshair is picked: once per client tick, and once per frame before the level is drawn. */
    public enum PickChain { TICK, RENDER }

    private static final CombatViewRules RULES = new CombatViewRules();
    private static volatile boolean overriding;
    private static LocalPlayer seen;
    private static java.util.UUID seenId;
    private static final boolean[] pickRan = new boolean[PickChain.values().length], pickWarned = new boolean[PickChain.values().length];
    private static final CrosshairReach.Verdict[] lastVerdict = new CrosshairReach.Verdict[PickChain.values().length];
    private static int pickWatchTicks;

    /** Combat view framing and TPS are in force now: setting on, Epic Fight's TPS available, combat mode, this round not yielded to F5, Epic Fight's TPS position screen not open. */
    public static boolean overriding() { return overriding; }

    private static boolean enabled() { return HudConfig.SPEC.isLoaded() && HudConfig.COMBAT_VIEW.get(); }
    private static boolean tpsAvailable() { return EpicFightTpsCameraDisableState.getReason() == null; }

    /**
     * Epic Fight is switching its local player into combat mode, at the point where it would switch the view itself:
     * the round is classified here. Returns whether Epic Fight's own perspective switch still happens.
     */
    public static boolean onEnterRequest(Object player, boolean epicFightSwitches) {
        Minecraft minecraft = Minecraft.getInstance();
        if (player == null || player != minecraft.player || !adopt(minecraft.player)) return epicFightSwitches;
        // The setting and Epic Fight's TPS as they are at this moment, not as the last tick saw them.
        boolean guardedBefore = RULES.guarded();
        return decide(minecraft, "enter", guardedBefore, RULES.enter(enabled() && tpsAvailable(), view(minecraft.options.getCameraType())), epicFightSwitches);
    }
    /** Epic Fight is switching its local player out of combat mode; as {@link #onEnterRequest}, with the setting and TPS as they are now. */
    public static boolean onExitRequest(Object player, boolean epicFightSwitches) {
        Minecraft minecraft = Minecraft.getInstance();
        if (player == null || player != minecraft.player || !adopt(minecraft.player)) return epicFightSwitches;
        boolean guardedBefore = RULES.guarded();
        return decide(minecraft, "exit", guardedBefore, RULES.exit(enabled(), tpsAvailable(), view(minecraft.options.getCameraType())), epicFightSwitches);
    }
    private static boolean decide(Minecraft minecraft, String request, boolean guardedBefore, CombatViewRules.Switch decision, boolean epicFightSwitches) {
        if (decision.request() != null) setView(minecraft, decision.request());
        if (CombatViewTrace.ENABLED) CombatViewTrace.log("client_switch", minecraft.player.level().getGameTime(), "request=" + request
                + " by=" + (decision.holdEpicFight() ? "combat_view" : "epic_fight") + " to=" + (decision.request() == null ? "-" : decision.request())
                + " epic_fight_auto=" + epicFightSwitches + " guarded_before=" + guardedBefore + " guarded=" + RULES.guarded() + " owns=" + RULES.owns()
                + " view=" + view(minecraft.options.getCameraType()));
        return epicFightSwitches && !decision.holdEpicFight();
    }

    /**
     * Takes on the current local player entity. Returns whether the rules may act for it now: false only before the
     * first tick of a new connection. A new entity of the same local player in the same connection (respawn, another
     * dimension) keeps the round's data, none of which refers to an entity: whether it is protected, ownership, and the
     * view from before entering. Everything that belonged to the old entity is dropped (the staff's aim snapshot and
     * next-frame check, a held release, the pick watch); this class keeps no entity or world reference beyond the
     * entity itself. A different player identity, a new connection (the logging-out event), or no player at all starts
     * over. Whether the view is still ours is the rules' own business: ownership ends the moment the view changes
     * elsewhere (F5, another mod) or the camera moves to another entity, and nothing is given back after that.
     */
    private static boolean adopt(LocalPlayer player) {
        if (player == seen) return seen != null;
        if (player == null) { RULES.reset(); seen = null; seenId = null; StaffAim.reset(); resetPickWatch(); return false; }
        boolean sameConnection = seen != null && player.getUUID().equals(seenId);
        if (!sameConnection) RULES.reset();
        seen = player; seenId = player.getUUID(); StaffAim.reset(); resetPickWatch();
        return sameConnection;
    }

    public static void afterClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        // A new entity first: anything held for the old one is dropped before the end of the tick sends anything.
        adopt(player);
        // A release held for this tick's movement packet goes now if the packet never came; an unused hold lapses.
        StaffAim.endOfTick(minecraft);
        if (player == null) { overriding = false; return; }
        var patch = EpicFightCapabilities.getLocalPlayerPatch(player);
        // No patch yet (a new entity before Epic Fight has attached it): nothing is known about the mode, so nothing is decided.
        if (patch == null) { overriding = false; return; }
        boolean combat = patch.isEpicFightMode();
        // The camera on another entity (spectating it): the view is no longer ours to give back.
        if (minecraft.getCameraEntity() != player) RULES.cameraLeftPlayer();
        boolean enabled = enabled(), available = tpsAvailable();
        var step = RULES.tick(enabled, available, combat, view(minecraft.options.getCameraType()));
        if (step.logTpsLost()) JoblessReincarnation.LOGGER.info("Combat view stands down: Epic Fight's TPS camera is disabled by {}",
                EpicFightTpsCameraDisableState.getReason() == null ? "another mod" : EpicFightTpsCameraDisableState.getReason().getModName());
        if (step.logTpsBack()) JoblessReincarnation.LOGGER.info("Combat view available again: Epic Fight's TPS camera is enabled");
        if (step.request() != null) {
            setView(minecraft, step.request());
            if (CombatViewTrace.ENABLED) CombatViewTrace.log("client_switch", player.level().getGameTime(), "request=tick by=combat_view to=" + step.request()
                    + " guarded=" + RULES.guarded() + " owns=" + RULES.owns());
        }
        boolean was = overriding;
        // Epic Fight's TPS position screen previews the player's own camera settings: stand aside while it is open.
        overriding = enabled && available && combat && !RULES.yielded() && player.isAlive() && !player.isSleeping()
                && !(minecraft.screen instanceof TPSSettingScreen);
        if (CombatViewTrace.ENABLED && was != overriding) CombatViewTrace.log("client_override", player.level().getGameTime(), "on=" + overriding
                + " enabled=" + enabled + " tps_available=" + available + " combat=" + combat + " guarded=" + RULES.guarded() + " yielded=" + RULES.yielded() + " owns=" + RULES.owns()
                + " alive=" + player.isAlive() + " sleeping=" + player.isSleeping() + " tps_screen=" + (minecraft.screen instanceof TPSSettingScreen));
        watchPicks(minecraft);
    }

    /**
     * Right after a crosshair pick has finished, every wrapper around it included (Epic Fight's own among them), and
     * before anything uses the result: the result keeps the player's own reach, as in first person. A hit Epic Fight
     * dropped only for its camera distance comes back; a hit it kept beyond the player's reach (its camera pulled in by
     * a wall) becomes a miss at the same point. Called from the instruction after each pick, so it sees each chain's
     * final result whatever order the wrappers were applied in.
     */
    public static void afterPick(PickChain chain) {
        if (!overriding) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        var api = EpicFightCameraAPI.getInstance();
        if (player == null || minecraft.getCameraEntity() != player || !api.isTPSMode()) return;
        pickRan[chain.ordinal()] = true;
        HitResult raw = api.getCrosshairHitResult();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition(), eye = player.getEyePosition();
        var verdict = CrosshairReach.judge(raw, minecraft.hitResult, camera, eye, player.blockInteractionRange(), player.entityInteractionRange(), ClientConfig.cameraZoom);
        if (verdict == CrosshairReach.Verdict.RESTORE) minecraft.hitResult = raw;
        else if (verdict == CrosshairReach.Verdict.CUT) minecraft.hitResult = CrosshairReach.missAt(raw, eye);
        if (CombatViewTrace.ENABLED && verdict != lastVerdict[chain.ordinal()]) {
            lastVerdict[chain.ordinal()] = verdict;
            CombatViewTrace.log("client_pick", player.level().getGameTime(), "chain=" + chain.name().toLowerCase(Locale.ROOT) + " verdict=" + verdict
                    + " type=" + (raw == null ? "NONE" : raw.getType()) + " point=" + (raw == null ? "-" : CombatViewTrace.vec(raw.getLocation()))
                    + " eye=" + CombatViewTrace.vec(eye) + " camera=" + CombatViewTrace.vec(camera));
        }
    }

    /** Each pick chain must reach the reach check while the combat view's TPS camera is on; say so once per chain if one does not. */
    private static void watchPicks(Minecraft minecraft) {
        if (!overriding || minecraft.noRender || !EpicFightCameraAPI.getInstance().isTPSMode()) return;
        if (++pickWatchTicks < 40) return;
        pickWatchTicks = 0;
        for (PickChain chain : PickChain.values()) {
            int i = chain.ordinal();
            if (!pickRan[i] && !pickWarned[i]) {
                pickWarned[i] = true;
                JoblessReincarnation.LOGGER.warn("Combat view: the crosshair reach check did not run after the {} pick for 40 ticks of the TPS camera; nearby blocks may be out of reach",
                        chain.name().toLowerCase(Locale.ROOT));
            }
            pickRan[i] = false;
        }
    }
    private static void resetPickWatch() {
        Arrays.fill(pickRan, false); Arrays.fill(lastVerdict, null); pickWatchTicks = 0;
    }

    /** Leaving the world ends everything: the next connection starts over. */
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RULES.reset(); seen = null; seenId = null; overriding = false; StaffAim.reset(); resetPickWatch();
    }

    static View view(CameraType type) {
        return switch (type) {
            case FIRST_PERSON -> View.FIRST_PERSON;
            case THIRD_PERSON_BACK -> View.THIRD_PERSON_BACK;
            case THIRD_PERSON_FRONT -> View.THIRD_PERSON_FRONT;
        };
    }
    /** As vanilla's F5 does it, so post effects and chunk culling follow the new point of view. */
    private static void setView(Minecraft minecraft, View view) {
        CameraType old = minecraft.options.getCameraType(), next = switch (view) {
            case FIRST_PERSON -> CameraType.FIRST_PERSON;
            case THIRD_PERSON_BACK -> CameraType.THIRD_PERSON_BACK;
            case THIRD_PERSON_FRONT -> CameraType.THIRD_PERSON_FRONT;
        };
        if (old == next) return;
        minecraft.options.setCameraType(next);
        if (old.isFirstPerson() != next.isFirstPerson()) minecraft.gameRenderer.checkEntityPostEffect(next.isFirstPerson() ? minecraft.getCameraEntity() : null);
        minecraft.levelRenderer.needsUpdate();
    }

    private CombatView() {}
}
