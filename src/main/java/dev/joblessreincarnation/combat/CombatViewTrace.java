package dev.joblessreincarnation.combat;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.registry.ModAttachments;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Development diagnostics for the combat view's aim, off unless the JVM runs with -Djobless.combatview.trace=true.
 * Each line starts with "CombatViewTrace" and carries the game time, so the client's aim snapshot, the rotation the
 * server received, the rotation it used for the release and the spell's projectile can be paired up afterwards
 * (tools/combat-view-trace.mjs in the workspace). Nothing is recorded, sent or changed when it is off.
 */
public final class CombatViewTrace {
    public static final boolean ENABLED = Boolean.getBoolean("jobless.combatview.trace");

    public static void log(String kind, long gameTime, String fields) {
        if (ENABLED) JoblessReincarnation.LOGGER.info("CombatViewTrace {} gt={} {}", kind, gameTime, fields);
    }
    public static String vec(Vec3 v) { return String.format(Locale.ROOT, "%.4f,%.4f,%.4f", v.x, v.y, v.z); }
    public static String deg(float v) { return String.format(Locale.ROOT, "%.4f", v); }
    /** A movement packet's kind, as vanilla's sendPosition chooses it: pos_rot, pos, rot or status_only. */
    public static String moveKind(ServerboundMovePlayerPacket packet) {
        return packet.hasPosition() ? packet.hasRotation() ? "pos_rot" : "pos" : packet.hasRotation() ? "rot" : "status_only";
    }
    /**
     * A movement packet's own fields, exactly (shortest round-trip decimal), with no spaces: the client writes it as it
     * sends the packet and the server as it handles one, so the reader can tell the very same packet on both sides.
     */
    public static String rawMove(ServerboundMovePlayerPacket packet) {
        return (packet.hasPosition() ? packet.getX(0) + "," + packet.getY(0) + "," + packet.getZ(0) : "-") + "/"
                + (packet.hasRotation() ? packet.getYRot(0) + "," + packet.getXRot(0) : "-") + "/" + packet.isOnGround();
    }

    /** The release the server is handling right now (server thread), for the projectile lines it causes. */
    private static String releaseFields;
    private static long releaseGameTime = Long.MIN_VALUE;
    private static UUID releaseCaster;

    /**
     * Server, before it handles a release: the release as the spell will see it. used_ticks is counted as
     * Item.releaseUsing is given it (use duration less the remaining ticks), with the active spell, its chant threshold,
     * its form stage and the charge it gives that count. Projectile lines written during this release carry the same.
     */
    public static String beginRelease(ServerPlayer player) {
        int used = player.isUsingItem() ? player.getUseItem().getUseDuration(player) - player.getUseItemRemainingTicks() : -1;
        SpellCatalog spell = SpellCatalog.active(player);
        CastingData data = player.hasData(ModAttachments.CASTING) ? player.getData(ModAttachments.CASTING) : null;
        releaseFields = "used_ticks=" + used + " spell=" + (spell == null ? "none" : spell.name().toLowerCase(Locale.ROOT))
                + " chant_ticks=" + (spell == null ? "-" : String.valueOf(spell.chantTicks(player)))
                + " form=" + (data == null ? "-" : String.valueOf(data.activeFormStage())) + " overcasting=" + (data != null && data.overcasting())
                + " charge=" + (spell == null || used < 0 ? "-" : String.format(Locale.ROOT, "%.4f", spell.charge(player, used)));
        releaseGameTime = player.level().getGameTime();
        releaseCaster = player.getUUID();
        return releaseFields;
    }
    public static void endRelease() { releaseFields = null; releaseCaster = null; }

    /** Server: where and how fast a spell projectile of this mod leaves, with its caster's movement at that moment. */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!ENABLED || event.getLevel().isClientSide() || !(event.getEntity() instanceof Projectile projectile)) return;
        if (!BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).getNamespace().equals(JoblessReincarnation.MOD_ID)) return;
        if (!(projectile.getOwner() instanceof ServerPlayer caster)) return;
        long now = event.getLevel().getGameTime();
        boolean fromRelease = releaseFields != null && releaseGameTime == now && caster.getUUID().equals(releaseCaster);
        log("projectile", now, "type=" + BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType())
                + " pos=" + vec(projectile.position()) + " vel=" + vec(projectile.getDeltaMovement())
                + " caster_eye=" + vec(caster.getEyePosition()) + " caster_yaw=" + deg(caster.getYRot()) + " caster_pitch=" + deg(caster.getXRot())
                + " caster_motion=" + vec(caster.getKnownMovement()) + " caster_on_ground=" + caster.onGround()
                + " from_release=" + fromRelease + (fromRelease ? " " + releaseFields : ""));
    }

    private CombatViewTrace() {}
}
