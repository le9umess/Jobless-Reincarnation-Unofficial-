package dev.joblessreincarnation.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.attachment.AttachmentSyncHandler;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.jetbrains.annotations.Nullable;

/** Owner-only updates, including the NeoForge 21.1.251 initial-tracking compatibility guard. */
public final class OwnerOnlyAttachmentSync<T> implements AttachmentSyncHandler<T> {
    private final StreamCodec<? super RegistryFriendlyByteBuf, T> codec;
    private OwnerOnlyAttachmentSync(StreamCodec<? super RegistryFriendlyByteBuf, T> codec) { this.codec = codec; }
    public static <T> OwnerOnlyAttachmentSync<T> of(StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        return new OwnerOnlyAttachmentSync<>(codec);
    }
    @Override
    public boolean sendToPlayer(IAttachmentHolder holder, ServerPlayer recipient) { return holder == recipient; }
    @Override
    public void write(RegistryFriendlyByteBuf buffer, T attachment, boolean initialSync) {
        // In 21.1.251, AttachmentSync.syncInitialAttachments never checks sendToPlayer.
        // Its documented zero-byte contract removes this type AND its presence marker from
        // the initial payload. ManaEvents explicitly syncs all private data to the owner
        // on login/respawn/dimension change; those update paths enforce sendToPlayer.
        if (!initialSync) codec.encode(buffer, attachment);
    }
    @Override
    public T read(IAttachmentHolder holder, RegistryFriendlyByteBuf buffer, @Nullable T previousValue) {
        return codec.decode(buffer);
    }
}
// Source provenance: 4C6539756D657373 / JR-0.1.0 / owner-attachment-sync.
