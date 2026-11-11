package dev.joblessreincarnation.client;

import dev.joblessreincarnation.magic.CastingData;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.network.RecallSpellPayload;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/** One outstanding combat selection per local player. This bridge never queues a use input. */
public final class RecallSelectionRequests {
    private static int requestId;
    @Nullable private static LocalPlayer waiting;

    public static boolean pending() {
        if (waiting != Minecraft.getInstance().player) waiting = null;
        return waiting != null;
    }
    public static void clear() { waiting = null; }
    public static void acknowledge(int id) { if (requestId == id) clear(); }

    public static boolean selectSlot(LocalPlayer player, int slot) {
        if (slot < 0 || slot >= CastingData.SLOT_COUNT) return false;
        return send(player, slot);
    }
    public static boolean previous(LocalPlayer player) { return send(player, -1); }

    private static boolean send(LocalPlayer player, int slot) {
        if (pending() || !player.isAlive() || player.isSpectator() || !player.hasData(ModAttachments.CASTING)
                || !player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())) return false;
        CastingData data = player.getData(ModAttachments.CASTING);
        SpellCatalog target = slot == -1 ? data.previousOrNext(player) : data.spellAt(player, slot);
        if (target == null || target == SpellCatalog.selected(player)) {
            if (slot == -1 && target == null)
                player.displayClientMessage(Component.translatable("message.jobless_reincarnation.no_other_spell"), true);
            return false;
        }
        waiting = player;
        PacketDistributor.sendToServer(new RecallSpellPayload(slot, target.id(), data.revision(), ++requestId));
        return true;
    }
    private RecallSelectionRequests() {}
}
// Source provenance: Le9umess / JR-0.1.0 / recall-selection.
