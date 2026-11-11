package dev.joblessreincarnation.network;

import dev.joblessreincarnation.magic.SpellCasting;
import dev.joblessreincarnation.magic.EarthWallDismantling;
import dev.joblessreincarnation.registry.ModAttachments;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ModNetworking {
    public static void register(RegisterPayloadHandlersEvent event) {
        // PayloadRegistrar defaults to the logical main thread.
        var registrar = event.registrar("31"); // 0.1.0, the first public release. "30" was its first playtest package (boss bars, burst kinds and naming changed since); development packages used other values.
        registrar.playToClient(BossBarPayload.TYPE, BossBarPayload.STREAM_CODEC, (payload, context) -> payload.applyClient());
        registrar.playToClient(RemoveBossBarPayload.TYPE, RemoveBossBarPayload.STREAM_CODEC, (payload, context) -> payload.applyClient());
        registrar.playToServer(NamingAnswerPayload.TYPE, NamingAnswerPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player)
                dev.joblessreincarnation.naming.Naming.answer(player, payload.later() ? null : payload.choice());
        });
        registrar.playToServer(OpenJournalPayload.TYPE, OpenJournalPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) SpellCasting.openJournal(player);
        });
        registrar.playToServer(SelectSpellPayload.TYPE, SelectSpellPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player && player.getData(ModAttachments.CASTING).revision() == payload.revision())
                SpellCasting.select(player, payload.spell());
        });
        registrar.playToServer(RecallSpellPayload.TYPE, RecallSpellPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                SpellCasting.recall(player, payload.slot(), payload.spell(), payload.revision());
                // Selected attachment updates are sent before this acknowledgement, also unblock rejected requests.
                PacketDistributor.sendToPlayer(player, new RecallAckPayload(payload.requestId()));
            }
        });
        registrar.playToClient(RecallAckPayload.TYPE, RecallAckPayload.STREAM_CODEC, (payload, context) ->
                dev.joblessreincarnation.client.RecallSelectionRequests.acknowledge(payload.requestId()));
        registrar.playToServer(MoveSpellSlotPayload.TYPE, MoveSpellSlotPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) SpellCasting.moveSlot(player, payload.spell(), payload.slot(), payload.revision());
        });
        registrar.playToServer(DismissPayload.TYPE, DismissPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) EarthWallDismantling.handle(player, payload);
        });
        registrar.playToServer(CourseRecordsRequestPayload.TYPE, CourseRecordsRequestPayload.STREAM_CODEC, (payload, context) -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !player.isAlive() || player.isRemoved() || player.isSpectator()) return;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    CourseRecordsResponsePayload.fromPlayer(payload.nonce(), player));
        });
        registrar.playToClient(DismissStatusPayload.TYPE, DismissStatusPayload.STREAM_CODEC, (payload, context) ->
                dev.joblessreincarnation.client.DismissInputState.receive(payload));
        registrar.playToClient(CourseRecordsResponsePayload.TYPE, CourseRecordsResponsePayload.STREAM_CODEC, (payload, context) ->
                dev.joblessreincarnation.client.CourseRecordsScreen.receive(payload));
    }
    private ModNetworking() {}
}
