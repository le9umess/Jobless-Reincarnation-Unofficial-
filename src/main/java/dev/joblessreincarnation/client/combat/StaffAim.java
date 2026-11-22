package dev.joblessreincarnation.client.combat;

import dev.joblessreincarnation.combat.AimMath;
import dev.joblessreincarnation.combat.CombatViewTrace;
import dev.joblessreincarnation.combat.HeldRelease;
import dev.joblessreincarnation.combat.ReleaseEye;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;
import yesman.epicfight.api.client.event.types.camera.BuildCameraTransform;

/**
 * The staff's aim in the combat view: the player's own eyes are turned to the point under the camera's crosshair, so
 * every staff rule that reads the player's look (the earth wall preview, the healing target, H's dismissal, the
 * release itself) keeps reading it unchanged, and the server recomputes everything from that look as it always did.
 * This is the adapter's own coupling: Epic Fight's coupling state is never touched.
 * <p>
 * Turned every frame once Epic Fight has placed its camera, and at the start of every client tick. Before an action
 * (starting a cast, a release, H's request) the camera is placed again for this very moment, from the player's position
 * now and Epic Fight's camera rotation now, so a quick turn just before letting go is not answered with an older view.
 * <p>
 * Where the mouse comes in (the sync point). Each frame Minecraft runs its client ticks first, then
 * MouseHandler.handleAccumulatedMovement, then draws. That call consumes the mouse movement gathered since the last
 * frame, once, and Epic Fight's turnCamera adds it to its current camera angles. An action happens inside a client tick,
 * so it uses every mouse movement consumed up to the last frame: placing the camera at partial tick 1 takes exactly those
 * current angles (Epic Fight's in-between interpolation is not involved at 1), at the player's position now. Movement
 * after that frame is still pending; vanilla consumes it after this tick, once, as it does for a first-person release.
 * Nothing here reads or consumes the pending movement: the diagnostics only record how much of it there was. Picking a
 * point ({@link #aim}) never moves the camera; only {@link #placeNow} does, and the camera event it causes is ignored.
 */
public final class StaffAim {
    private static AimMath.Aim sent;
    private static boolean rebuilding, nextFrame;
    /** Camera events skipped because this class was placing the camera itself: during the last placement, and since the last action line. */
    private static int skippedInPlace, skippedSinceAction;
    /**
     * A release prepared this tick: vanilla's own release is held, with the use it belongs to, until this tick's movement
     * packet goes (see {@link #prepareRelease}). It is settled then, once, for the entity it was armed for, and never past
     * the end of the tick.
     */
    private static final HeldRelease HOLD = new HeldRelease();
    /** The crosshair input of the release being held: its world point is aimed at again when the movement packet is about to go. */
    private static AimMath.Input heldInput;
    /** The player's pose when the held release was prepared: the server still has it when it handles the release (F01, {@link ReleaseEye}). */
    private static Pose heldPose;
    /** The look the held release is aimed with (as the player holds it), which aim gave it (1: at the input; 2: again before the movement packet), and which release this is. */
    private static float aimedYaw, aimedPitch;
    private static int aimGeneration, releaseId;
    /**
     * This tick's natural movement packet while a release is armed: null until vanilla's sendPosition runs (it does not
     * when the player's chunk is not loaded), NONE when it sends nothing. Its look decides whether the release needs one.
     */
    private static MovePacket thisTickPacket;
    /** Movement packets vanilla's sendPosition has sent from this client (diagnostics). */
    private static int movePackets;

    /** One movement packet as vanilla sent it: its number on this client, kind, look, and every field exactly as the server reads them. */
    private record MovePacket(int seq, String kind, boolean hasRotation, float yaw, float pitch, String raw) {
        static final MovePacket NONE = new MovePacket(0, "none", false, 0, 0, "-");
    }

    /** The staff aims at the crosshair: combat view in force, Epic Fight's TPS camera on the player, the staff in the main hand, able to act. */
    public static boolean canSync(Minecraft minecraft, LocalPlayer player) {
        return player != null && CombatView.overriding() && EpicFightCameraAPI.getInstance().isTPSMode()
                && minecraft.getCameraEntity() == player && player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())
                && player.isAlive() && !player.isSpectator() && !player.isSleeping() && !minecraft.isPaused()
                && minecraft.isWindowActive() && minecraft.screen == null && minecraft.getOverlay() == null;
    }
    /** As {@link #canSync}, and kept turned between actions: not while riding, where the mount follows the rider's yaw. */
    public static boolean aligning(Minecraft minecraft, LocalPlayer player) { return canSync(minecraft, player) && !player.isPassenger(); }

    /** Epic Fight's camera for this frame is final: turn the player to its crosshair before anything is drawn. */
    public static void afterCameraBuilt(BuildCameraTransform.Post event) {
        if (rebuilding) { skippedInPlace++; skippedSinceAction++; return; }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || event.getCamera() != minecraft.gameRenderer.getMainCamera() || event.getCamera().getEntity() != player) return;
        Vec3 eye = player.getEyePosition(event.getPartialTick());
        if (nextFrame) {
            nextFrame = false;
            if (CombatViewTrace.ENABLED && sent != null) {
                var seen = aim(player, event.getCamera(), eye);
                trace(player, "next_frame", seen, event.getCamera(), eye, "angle_to_sent=" + CombatViewTrace.deg((float)AimMath.angle(sent.yaw(), sent.pitch(), seen.yaw(), seen.pitch())));
            }
        }
        if (aligning(minecraft, player)) turn(player, aim(player, event.getCamera(), eye));
    }
    /** Start of a client tick, before vanilla handles keys or sends this tick's movement. */
    public static void beforeTick(Minecraft minecraft, LocalPlayer player) {
        if (!aligning(minecraft, player)) return;
        Camera camera = placeNow(minecraft, player, "tick");
        turn(player, aim(player, camera, player.getEyePosition()));
    }
    /** Turn to the crosshair as it is now and send that look, ahead of the packet that acts on it (a release, H's request). */
    public static void syncNow(Minecraft minecraft, LocalPlayer player, String reason) {
        if (!canSync(minecraft, player)) return;
        Camera camera = placeNow(minecraft, player, reason);
        var aim = aim(player, camera, player.getEyePosition());
        turn(player, aim);
        player.connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround()));
        sent = aim; nextFrame = true;
        trace(player, reason, aim, camera, player.getEyePosition(), "sent=rot");
    }
    /**
     * The use key is up and vanilla will release the staff later in this tick. The crosshair's world point is taken now,
     * the moment of input, and the player is turned to it; vanilla's release (the whole call, the client's own stop of
     * the use included) is then held until this tick's movement packet ({@code LocalPlayer.sendPosition}). Right before
     * vanilla builds that packet ({@link #beforeMovementSent}) the player is turned to the same point again from the eyes
     * as they are after this tick's movement, where the server will have the caster when it handles the release. The
     * packet carries that look and this tick's position change, which the server keeps as the caster's known movement and
     * every projectile adds (vanilla {@code Projectile.shootFromRotation}), as after a first-person release; a separate
     * rotation-only packet would reset that movement to zero. Riders keep the rotation packet first: vanilla sends one for
     * them every tick anyway, and their release is not held.
     */
    public static void prepareRelease(Minecraft minecraft, LocalPlayer player) {
        if (!canSync(minecraft, player)) return;
        if (player.isPassenger()) { syncNow(minecraft, player, "release"); return; }
        Camera camera = placeNow(minecraft, player, "release");
        Vec3 eye = player.getEyePosition();
        heldPose = player.getPose();
        heldInput = input(player, camera, eye);
        var aim = heldInput != null ? heldInput.first() : aim(player, camera, eye);
        turn(player, aim);
        aimedYaw = player.getYRot(); aimedPitch = player.getXRot(); aimGeneration = 1; releaseId++;
        sent = aim; nextFrame = true; HOLD.arm(player); thisTickPacket = null;
        trace(player, "release", aim, camera, eye, "plan=hold_for_move release_id=" + releaseId + " gen=1");
    }
    /**
     * Vanilla is releasing the staff now (Minecraft.handleKeybinds): hold it, with the use it belongs to, while a prepared
     * release waits for the movement packet. The whole of vanilla's release waits, so this tick still moves as a charging
     * player (slowed) and the client counts one more charge tick; the server's count is not affected (impl-design 13).
     */
    public static boolean holdRelease(Player player) {
        return player instanceof LocalPlayer local && HOLD.hold(context(local));
    }
    /**
     * Vanilla's sendPosition is starting: this tick's movement is done and the movement packet is about to be built. A
     * held release is aimed again now, at its input's world point from the eyes as they are now, so the packet carries it.
     */
    public static void beforeMovementSent(LocalPlayer player) {
        if (!HOLD.armed()) return;
        thisTickPacket = MovePacket.NONE;
        if (!HOLD.pendingFor(player) || heldInput == null) return;
        // From the eyes the server will release from: the position after this tick's movement, at the eye height of the pose
        // the server still has (the one at this tick's start). The client may have changed pose this tick already (F01).
        Vec3 clientEye = player.getEyePosition();
        Vec3 eye = heldPose == null ? clientEye : ReleaseEye.serverEye(player, player.position(), heldPose, player.getPose());
        float yawBefore = aimedYaw, pitchBefore = aimedPitch;
        var aim = heldInput.from(eye);
        turn(player, aim);
        aimedYaw = player.getYRot(); aimedPitch = player.getXRot(); aimGeneration = 2;
        sent = aim; nextFrame = true;
        if (CombatViewTrace.ENABLED) CombatViewTrace.log("client_reaim", player.level().getGameTime(), "release_id=" + releaseId + " gen=2"
                + " input_eye=" + CombatViewTrace.vec(heldInput.eye()) + " eye=" + CombatViewTrace.vec(eye) + " client_eye=" + CombatViewTrace.vec(clientEye)
                + " pose_then=" + heldPose + " pose_now=" + player.getPose() + " moved=" + CombatViewTrace.vec(eye.subtract(heldInput.eye()))
                + " point=" + CombatViewTrace.vec(aim.point()) + " source=" + aim.source() + " yaw=" + CombatViewTrace.deg(aim.yaw()) + " pitch=" + CombatViewTrace.deg(aim.pitch())
                + " turned_deg=" + CombatViewTrace.deg((float) AimMath.angle(yawBefore, pitchBefore, aimedYaw, aimedPitch)) + " ticks_using=" + player.getTicksUsingItem());
    }
    /** Each packet vanilla's sendPosition sends; while a release is armed, this tick's movement packet among them is kept (its look is checked when the release goes). */
    public static void sawMovePacket(Packet<?> packet) {
        if (!(packet instanceof ServerboundMovePlayerPacket move)) return;
        movePackets++;
        if (HOLD.armed()) thisTickPacket = new MovePacket(movePackets, CombatViewTrace.moveKind(move), move.hasRotation(), move.getYRot(0), move.getXRot(0), CombatViewTrace.rawMove(move));
    }
    /** Diagnostics: every vanilla staff release on this client, first person included, held or not, with the client's own charge count. */
    public static void traceVanillaRelease(Player player, boolean held) {
        if (!CombatViewTrace.ENABLED || !(player instanceof LocalPlayer local) || !local.getUseItem().is(ModContent.TRAINING_STAFF.get())) return;
        CombatViewTrace.log("client_vanilla_release", local.level().getGameTime(), "held=" + held + " resend=" + HOLD.sending()
                + (held ? " release_id=" + releaseId : "")
                + " view=" + Minecraft.getInstance().options.getCameraType().name().toLowerCase(java.util.Locale.ROOT)
                + " yaw=" + CombatViewTrace.deg(local.getYRot()) + " pitch=" + CombatViewTrace.deg(local.getXRot())
                + " ticks_using=" + local.getTicksUsingItem() + " using=" + local.isUsingItem() + " pos=" + CombatViewTrace.vec(local.position()));
    }
    /**
     * This tick's movement packet has just gone, or vanilla had nothing to send: settle a held release. It goes only if
     * its use is still there; otherwise it is dropped and nothing replaces it. Vanilla calls sendPosition only for a
     * player not riding (a rider's release is not held) and only while the player's chunk is loaded; otherwise the held
     * release is settled at the end of the tick.
     */
    public static void afterMovementSent(LocalPlayer player) {
        if (!HOLD.pendingFor(player)) return;
        HeldRelease.Context held = HOLD.heldContext();
        settle(player, HOLD.afterMove(player, context(player), usable(player)), "after_move", held);
    }
    /** End of the client tick, after a change of player entity has been taken in: a held release that never met a movement packet is settled now; a hold vanilla never used lapses. */
    public static void endOfTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        HeldRelease.Context held = HOLD.heldContext();
        boolean check = held != null && player != null;
        settle(player, HOLD.endOfTick(player, check ? context(player) : null, check && usable(player)), "tick_end", held);
    }
    /** The use a held release belongs to: the player's world and connection, the item in use, its hand and the hotbar slot. */
    private static HeldRelease.Context context(LocalPlayer player) {
        return new HeldRelease.Context(player.level(), player.connection, player.getUseItem().getItem(), player.getUsedItemHand(), player.getInventory().selected);
    }
    /** Still able to release that use: alive, not a spectator, using the training staff in the main hand, which still holds it. */
    private static boolean usable(LocalPlayer player) {
        return player.isAlive() && !player.isSpectator() && player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && player.getUseItem().is(ModContent.TRAINING_STAFF.get()) && ItemStack.isSameItem(player.getMainHandItem(), player.getUseItem());
    }
    private static void settle(LocalPlayer player, HeldRelease.Outcome outcome, String path, HeldRelease.Context held) {
        if (outcome == HeldRelease.Outcome.SEND) sendHeld(player, path);
        else if (outcome == HeldRelease.Outcome.DROP && CombatViewTrace.ENABLED && player != null)
            CombatViewTrace.log("client_release_dropped", player.level().getGameTime(), "release_id=" + releaseId + " at=" + path + " reason=" + dropReason(player, held)
                    + " packet=" + (thisTickPacket == null ? "not_called" : thisTickPacket.kind()) + " ticks_using=" + player.getTicksUsingItem());
    }
    private static String dropReason(LocalPlayer player, HeldRelease.Context held) {
        if (held == null) return "nothing_held";
        var now = context(player);
        if (now.level() != held.level()) return "world";
        if (now.connection() != held.connection()) return "connection";
        if (!player.isAlive() || player.isSpectator()) return "dead_or_spectating";
        if (!player.isUsingItem()) return "use_ended";
        if (now.slot() != held.slot() || now.item() != held.item() || !ItemStack.isSameItem(player.getMainHandItem(), player.getUseItem())) return "item_or_slot";
        return "other";
    }
    private static void sendHeld(LocalPlayer player, String where) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null) return;
        // The look this release is aimed with must reach the server ahead of it: in this tick's own movement packet, or
        // already in an earlier one (vanilla puts a look in a movement packet only when it differs from the last one it
        // sent). If neither, it goes now in a rotation packet of its own, which, like any packet without a position, sets
        // the server's known movement to zero; that happens only when vanilla sent no movement packet with this look.
        var last = (LastSentLook) player;
        String sync;
        if (thisTickPacket != null && thisTickPacket.hasRotation() && thisTickPacket.yaw() == aimedYaw && thisTickPacket.pitch() == aimedPitch) sync = "in_packet";
        else if (last.jobless$sentYaw() == aimedYaw && last.jobless$sentPitch() == aimedPitch) sync = "already_sent";
        else {
            sync = "own_rot";
            player.connection.send(new ServerboundMovePlayerPacket.Rot(aimedYaw, aimedPitch, player.onGround()));
        }
        // The client's own count of charge ticks when the release goes: one more than at the tick's start when the player
        // has ticked in between. The spell uses the server's count, which this does not change.
        int ticksUsing = player.getTicksUsingItem();
        HOLD.sending(true);
        try { minecraft.gameMode.releaseUsingItem(player); }
        finally { HOLD.sending(false); }
        if (CombatViewTrace.ENABLED) CombatViewTrace.log("client_release_sent", player.level().getGameTime(), "release_id=" + releaseId + " at=" + where
                + " gen=" + aimGeneration + " sync=" + sync + " aimed=" + CombatViewTrace.deg(aimedYaw) + "," + CombatViewTrace.deg(aimedPitch)
                + " move=" + (thisTickPacket == null ? "not_called" : thisTickPacket.kind())
                + (thisTickPacket != null && thisTickPacket != MovePacket.NONE ? " move_seq=" + thisTickPacket.seq() + " move_raw=" + thisTickPacket.raw() : "")
                + " last_sent=" + CombatViewTrace.deg(last.jobless$sentYaw()) + "," + CombatViewTrace.deg(last.jobless$sentPitch())
                + " eye=" + CombatViewTrace.vec(player.getEyePosition()) + " ticks_using=" + ticksUsing + " pos=" + CombatViewTrace.vec(player.position())
                + " still_using=" + player.isUsingItem());
    }

    /** The use packet that starts a cast carries the look itself: turn first, send nothing extra. */
    public static void beforeUse(Minecraft minecraft, LocalPlayer player) {
        if (!canSync(minecraft, player)) return;
        Camera camera = placeNow(minecraft, player, "use_start");
        var aim = aim(player, camera, player.getEyePosition());
        turn(player, aim);
        trace(player, "use_start", aim, camera, player.getEyePosition(), "sent=in_use_packet");
    }
    /** A new player entity or connection: nothing of the old one is kept, a release held for it included (it is dropped, not sent). */
    public static void reset() {
        if (HOLD.pending() && CombatViewTrace.ENABLED) CombatViewTrace.log("client_release_dropped", 0, "release_id=" + releaseId + " at=reset reason=entity_or_connection_changed");
        sent = null; nextFrame = false; skippedInPlace = 0; skippedSinceAction = 0; HOLD.clear(); heldInput = null; heldPose = null; thisTickPacket = null;
    }

    /**
     * Epic Fight places the main camera again for this moment (partial tick 1), with its collision and our framing. The
     * camera event this causes is skipped and counted; with diagnostics on, each placement writes a client_place line:
     * the camera Epic Fight placed, the eyes, Epic Fight's current angles (all mouse movement consumed so far), the
     * camera's own angles and partial tick, and how many camera events were skipped while placing.
     */
    private static Camera placeNow(Minecraft minecraft, LocalPlayer player, String reason) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        skippedInPlace = 0;
        rebuilding = true;
        try { camera.setup(minecraft.level, player, true, false, 1.0F); }
        finally { rebuilding = false; }
        if (CombatViewTrace.ENABLED) {
            var api = EpicFightCameraAPI.getInstance();
            CombatViewTrace.log("client_place", player.level().getGameTime(), "reason=" + reason + " partial=" + CombatViewTrace.deg(camera.getPartialTickTime())
                    + " camera=" + CombatViewTrace.vec(camera.getPosition()) + " eye=" + CombatViewTrace.vec(player.getEyePosition())
                    + " camera_rot=" + CombatViewTrace.deg(camera.getXRot()) + "," + CombatViewTrace.deg(camera.getYRot())
                    + " ef_rot=" + CombatViewTrace.deg(api.getCameraXRot()) + "," + CombatViewTrace.deg(api.getCameraYRot())
                    + " skipped=" + skippedInPlace);
        }
        return camera;
    }
    static AimMath.Aim aim(LocalPlayer player, Camera camera, Vec3 eye) {
        // Before the first frame the camera has no place yet: keep the look the player already has.
        var in = input(player, camera, eye);
        return in == null ? new AimMath.Aim(player.getYRot(), player.getXRot(), eye, AimMath.Source.CAMERA) : in.first();
    }
    /** The crosshair input for this moment: the eyes, the camera and its look, and the first block or entity its ray hits; null before the camera has a place. */
    static AimMath.Input input(LocalPlayer player, Camera camera, Vec3 eye) {
        if (!camera.isInitialized() || camera.getEntity() != player) return null;
        Vec3 from = camera.getPosition(), look = new Vec3(camera.getLookVector());
        Vec3 to = from.add(look.scale(AimMath.PICK_RANGE));
        HitResult block = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        AABB box = new AABB(from, end).inflate(1.0);
        var entity = ProjectileUtil.getEntityHitResult(player, from, end, box,
                e -> !e.isSpectator() && e.isPickable() && e != player && !player.isPassengerOfSameVehicle(e), from.distanceToSqr(end));
        Vec3 hit = entity != null ? entity.getLocation() : block.getType() == HitResult.Type.MISS ? null : block.getLocation();
        return new AimMath.Input(eye, from, look, hit, entity != null);
    }
    private static void turn(LocalPlayer player, AimMath.Aim aim) {
        player.setYRot(AimMath.continuous(player.getYRot(), aim.yaw()));
        player.setXRot(aim.pitch());
        player.setYHeadRot(AimMath.continuous(player.getYHeadRot(), aim.yaw()));
    }
    private static void trace(LocalPlayer player, String reason, AimMath.Aim aim, Camera camera, Vec3 eye, String extra) {
        if (!CombatViewTrace.ENABLED) return;
        var mouse = Minecraft.getInstance().mouseHandler;
        var api = EpicFightCameraAPI.getInstance();
        // An action line (not the next-frame check) also says how many camera events the guard skipped: during this
        // action's own placement, and since the previous action line.
        String skips = "";
        if (!reason.equals("next_frame")) {
            skips = " rebuild_skips_this=" + skippedInPlace + " rebuild_skips_since_last=" + skippedSinceAction;
            skippedSinceAction = 0;
        }
        // mouse_pending: movement gathered since the last frame and not yet consumed (it reaches the camera after this tick).
        CombatViewTrace.log("client_" + reason, player.level().getGameTime(), "yaw=" + CombatViewTrace.deg(aim.yaw()) + " pitch=" + CombatViewTrace.deg(aim.pitch())
                + " source=" + aim.source() + " point=" + CombatViewTrace.vec(aim.point()) + " eye=" + CombatViewTrace.vec(eye)
                + " camera=" + CombatViewTrace.vec(camera.getPosition()) + " look=" + CombatViewTrace.vec(new Vec3(camera.getLookVector()))
                + " camera_rot=" + CombatViewTrace.deg(camera.getXRot()) + "," + CombatViewTrace.deg(camera.getYRot())
                + " ef_rot=" + CombatViewTrace.deg(api.getCameraXRot()) + "," + CombatViewTrace.deg(api.getCameraYRot())
                + " mouse_pending=" + String.format(java.util.Locale.ROOT, "%.2f,%.2f", mouse.getXVelocity(), mouse.getYVelocity())
                + skips + " ticks_using=" + player.getTicksUsingItem() + " pos=" + CombatViewTrace.vec(player.position())
                + " motion=" + CombatViewTrace.vec(player.getDeltaMovement()) + " " + extra);
    }

    private StaffAim() {}
}
